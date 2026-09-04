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
import java.util.Map;

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

    /** 是否启用 Memory 内容加密（默认关闭，开启后 SQL LIKE 检索降级为内存匹配） */
    private final boolean encryptEnabled;

    /** 加密器（encryptEnabled=true 时初始化） */
    private final MemoryEncryptor encryptor;

    public MemoryStore(
            @Value("${TASK_DB_PATH:data/tasks.db}") String dbPath,
            @Value("${MEMORY_ENCRYPT_ENABLED:false}") boolean encryptEnabled,
            @Value("${MEMORY_ENCRYPT_KEY:}") String encryptKey) {
        this.dbPath = dbPath;
        this.encryptEnabled = encryptEnabled;
        if (encryptEnabled) {
            // 未配置密钥时使用基于机器特征的默认密钥
            String key = (encryptKey != null && !encryptKey.isBlank())
                    ? encryptKey
                    : System.getProperty("user.name") + "|" + System.getProperty("user.home") + "|" + System.getProperty("os.name");
            this.encryptor = new MemoryEncryptor(key);
            log.info("Memory 内容加密已启用（AES-256-GCM）");
        } else {
            this.encryptor = null;
        }
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
            // 启动时数据迁移：如果启用加密，自动加密未加密的旧数据
            migratePlaintextToEncrypted();
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
            ps.setString(3, encryptIfEnabled(item.getContent()));
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
            ps.setString(2, encryptIfEnabled(item.getContent()));
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
        if (encryptEnabled) {
            // 加密模式下无法使用 SQL LIKE，加载全部后内存匹配
            return listAll().stream()
                    .filter(m -> content.equals(m.getContent()))
                    .findFirst()
                    .orElse(null);
        }
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
        if (terms.isEmpty()) {
            return recent(limit);
        }
        if (encryptEnabled) {
            // 加密模式下无法使用 SQL LIKE，加载全部后内存匹配
            return listAll().stream()
                    .filter(m -> {
                        String content = m.getContent() != null ? m.getContent().toLowerCase() : "";
                        String keywords = m.getKeywords() != null ? String.join(" ", m.getKeywords()).toLowerCase() : "";
                        return terms.stream().anyMatch(t ->
                                content.contains(t.toLowerCase()) || keywords.contains(t.toLowerCase()));
                    })
                    .limit(limit)
                    .toList();
        }
        List<MemoryItem> result = new ArrayList<>();
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
                decryptIfEnabled(rs.getString("content")),
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

    // ==================== 加密辅助方法 ====================

    /** 如果启用加密，加密文本；否则返回原文 */
    private String encryptIfEnabled(String text) {
        if (!encryptEnabled || encryptor == null || text == null) return text;
        // 已经是加密格式的不重复加密
        if (MemoryEncryptor.isEncrypted(text)) return text;
        return encryptor.encrypt(text);
    }

    /** 如果启用加密，解密文本；否则返回原文 */
    private String decryptIfEnabled(String text) {
        if (!encryptEnabled || encryptor == null || text == null) return text;
        return encryptor.decrypt(text);
    }

    /**
     * 启动时数据迁移：如果启用加密，检测是否有未加密的旧数据，如果有则自动加密。
     * 在 init() 方法末尾调用。
     */
    private void migratePlaintextToEncrypted() {
        if (!encryptEnabled || encryptor == null) return;
        try {
            // 查找未加密的记录（不以 "enc:" 开头）
            String checkSql = "SELECT COUNT(*) AS c FROM memory_items WHERE content NOT LIKE 'enc:%'";
            try (Connection c = openConnection();
                 Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(checkSql)) {
                if (rs.next() && rs.getInt("c") > 0) {
                    int plaintextCount = rs.getInt("c");
                    log.info("[Memory 加密迁移] 检测到 {} 条未加密旧数据，开始加密迁移...", plaintextCount);

                    // 加载所有未加密记录
                    String selectSql = "SELECT id, content FROM memory_items WHERE content NOT LIKE 'enc:%'";
                    List<Map.Entry<String, String>> toUpdate = new ArrayList<>();
                    try (Statement st2 = c.createStatement();
                         ResultSet rs2 = st2.executeQuery(selectSql)) {
                        while (rs2.next()) {
                            toUpdate.add(Map.entry(rs2.getString("id"), rs2.getString("content")));
                        }
                    }

                    // 逐条加密更新
                    String updateSql = "UPDATE memory_items SET content = ? WHERE id = ?";
                    try (PreparedStatement ps = c.prepareStatement(updateSql)) {
                        for (Map.Entry<String, String> entry : toUpdate) {
                            ps.setString(1, encryptor.encrypt(entry.getValue()));
                            ps.setString(2, entry.getKey());
                            ps.executeUpdate();
                        }
                    }
                    log.info("[Memory 加密迁移] 完成，共加密 {} 条记录", toUpdate.size());
                }
            }
        } catch (SQLException e) {
            log.warn("[Memory 加密迁移] 失败: {}", e.getMessage());
        }
    }
}
