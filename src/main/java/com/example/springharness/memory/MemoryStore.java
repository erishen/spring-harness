package com.example.springharness.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 长期记忆 SQLite 持久化层。
 *
 * <p>表 memory_items：id 主键，keywords 以 JSON 数组存储。
 * 复用 LongTaskStore 的连接模式：每操作独立连接 + PRAGMA busy_timeout + WAL，
 * 避免多线程并发写 "database is locked"。
 */
@Component
public class MemoryStore {

    private static final Logger log = LoggerFactory.getLogger(MemoryStore.class);

    private static final TypeReference<List<String>> KEYWORDS_TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String dbPath;

    public MemoryStore(@Value("${TASK_DB_PATH:data/tasks.db}") String dbPath) {
        this.dbPath = dbPath;
    }

    @PostConstruct
    public void init() {
        try {
            var parent = Paths.get(dbPath).toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Class.forName("org.sqlite.JDBC");
            try (Connection c = openConnection()) {
                createTable(c);
            }
            log.info("SQLite 记忆存储就绪: {}", dbPath);
        } catch (Exception e) {
            log.error("初始化 SQLite 记忆存储失败: {}", dbPath, e);
            throw new IllegalStateException("SQLite 记忆存储初始化失败", e);
        }
    }

    private Connection openConnection() throws SQLException {
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA busy_timeout = 5000");
            st.execute("PRAGMA journal_mode = WAL");
        }
        return c;
    }

    private void createTable(Connection c) throws SQLException {
        String sql = """
                CREATE TABLE IF NOT EXISTS memory_items (
                    id TEXT PRIMARY KEY,
                    category TEXT DEFAULT 'fact',
                    content TEXT NOT NULL,
                    keywords TEXT DEFAULT '[]',
                    source TEXT DEFAULT '',
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    access_count INTEGER DEFAULT 0
                )
                """;
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    /** 新增记忆条目 */
    public void insert(MemoryItem item) {
        String sql = """
                INSERT INTO memory_items (id, category, content, keywords, source, created_at, updated_at, access_count)
                VALUES (?,?,?,?,?,?,?,?)
                """;
        try (Connection c = openConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, item.getId());
            ps.setString(2, item.getCategory());
            ps.setString(3, item.getContent());
            ps.setString(4, toJson(item.getKeywords()));
            ps.setString(5, item.getSource());
            ps.setLong(6, item.getCreatedAt());
            ps.setLong(7, item.getUpdatedAt());
            ps.setInt(8, item.getAccessCount());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("新增记忆失败: {}", e.getMessage());
        }
    }

    /** 更新记忆条目（内容/分类/关键词/时间） */
    public void update(MemoryItem item) {
        String sql = """
                UPDATE memory_items
                SET category=?, content=?, keywords=?, source=?, updated_at=?, access_count=?
                WHERE id=?
                """;
        try (Connection c = openConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, item.getCategory());
            ps.setString(2, item.getContent());
            ps.setString(3, toJson(item.getKeywords()));
            ps.setString(4, item.getSource());
            ps.setLong(5, item.getUpdatedAt());
            ps.setInt(6, item.getAccessCount());
            ps.setString(7, item.getId());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("更新记忆失败: {}", e.getMessage());
        }
    }

    /** 累加记忆访问次数 */
    public void incrementAccess(String id) {
        try (Connection c = openConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE memory_items SET access_count = access_count + 1, updated_at = ? WHERE id = ?")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.debug("累加记忆访问次数失败: {}", e.getMessage());
        }
    }

    /** 按内容精确匹配查找（用于抽取去重合并） */
    public MemoryItem findByContent(String content) {
        String sql = "SELECT * FROM memory_items WHERE content = ? LIMIT 1";
        try (Connection c = openConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, content);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapRow(rs);
                }
            }
        } catch (SQLException e) {
            log.warn("查找记忆失败: {}", e.getMessage());
        }
        return null;
    }

    /** 全文检索：query 词对 content/keywords 做 LIKE 匹配，按命中数与更新时间排序 */
    public List<MemoryItem> search(List<String> terms, int limit) {
        List<MemoryItem> result = new ArrayList<>();
        if (terms.isEmpty()) {
            return recent(limit);
        }
        StringBuilder sql = new StringBuilder("""
                SELECT *, (
                  (CASE WHEN content LIKE ? THEN 1 ELSE 0 END) +
                  (CASE WHEN keywords LIKE ? THEN 1 ELSE 0 END)
                ) AS hit FROM memory_items WHERE """);
        // 动态 OR 条件：content LIKE ? OR keywords LIKE ?（每个词）
        for (int i = 0; i < terms.size(); i++) {
            if (i > 0) sql.append(" OR ");
            sql.append("(content LIKE ? OR keywords LIKE ?)");
        }
        sql.append(" ORDER BY hit DESC, updated_at DESC LIMIT ?");
        try (Connection c = openConnection();
             PreparedStatement ps = c.prepareStatement(sql.toString())) {
            int idx = 1;
            // hit 表达式的两个 ? 用第一个词
            ps.setString(idx++, "%" + terms.get(0) + "%");
            ps.setString(idx++, "%" + terms.get(0) + "%");
            // WHERE 子句每个词的 content/keywords 两个 ?（含第一个词）
            for (String term : terms) {
                ps.setString(idx++, "%" + term + "%");
                ps.setString(idx++, "%" + term + "%");
            }
            ps.setInt(idx, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            log.warn("检索记忆失败: {}", e.getMessage());
        }
        return result;
    }

    /** 最近 N 条记忆（按更新时间倒序） */
    public List<MemoryItem> recent(int limit) {
        List<MemoryItem> result = new ArrayList<>();
        String sql = "SELECT * FROM memory_items ORDER BY updated_at DESC LIMIT ?";
        try (Connection c = openConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            log.warn("加载记忆失败: {}", e.getMessage());
        }
        return result;
    }

    /** 全部记忆（用于前端展示） */
    public List<MemoryItem> listAll() {
        List<MemoryItem> result = new ArrayList<>();
        String sql = "SELECT * FROM memory_items ORDER BY updated_at DESC";
        try (Connection c = openConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                result.add(mapRow(rs));
            }
        } catch (SQLException e) {
            log.warn("加载全部记忆失败: {}", e.getMessage());
        }
        return result;
    }

    /** 记忆总条数 */
    public int count() {
        String sql = "SELECT COUNT(*) AS c FROM memory_items";
        try (Connection c = openConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getInt("c");
            }
        } catch (SQLException e) {
            log.warn("统计记忆失败: {}", e.getMessage());
        }
        return 0;
    }

    /** 删除单条记忆 */
    public void delete(String id) {
        try (Connection c = openConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM memory_items WHERE id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("删除记忆 [{}] 失败: {}", id, e.getMessage());
        }
    }

    /** 清空全部记忆 */
    public void clear() {
        try (Connection c = openConnection();
             Statement st = c.createStatement()) {
            st.execute("DELETE FROM memory_items");
        } catch (SQLException e) {
            log.warn("清空记忆失败: {}", e.getMessage());
        }
    }

    private MemoryItem mapRow(ResultSet rs) throws SQLException {
        return new MemoryItem(
                rs.getString("id"),
                rs.getString("category"),
                rs.getString("content"),
                fromJson(rs.getString("keywords"), KEYWORDS_TYPE, new ArrayList<>()),
                rs.getString("source"),
                rs.getLong("created_at"),
                rs.getLong("updated_at"),
                rs.getInt("access_count")
        );
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException e) {
            return "[]";
        }
    }

    private <T> T fromJson(String json, TypeReference<T> type, T fallback) {
        if (json == null || json.isBlank()) return fallback;
        try {
            return objectMapper.readValue(json, type);
        } catch (IOException e) {
            return fallback;
        }
    }
}
