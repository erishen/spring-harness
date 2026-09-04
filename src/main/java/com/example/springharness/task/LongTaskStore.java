package com.example.springharness.task;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 长时任务 SQLite 持久化层。
 *
 * <p>将 LongTask（含 steps / logs / token 统计）持久化到本地 SQLite 文件，
 * 应用重启后自动恢复，任务不再因进程重启而丢失。
 *
 * <p>表结构：long_tasks（id 主键，列表字段以 JSON 存储）。
 *
 * <p>线程安全：JDBC Connection 非线程安全，单例长连接在多任务线程并发写时
 * 会引发 "database is locked" / 数据错乱。因此本类不持有共享连接，
 * 每个操作临时新建独立连接（try-with-resources 自动释放），
 * 并通过 PRAGMA busy_timeout + WAL 让并发写等待锁而非立即报错。
 */
@Component
public class LongTaskStore {

    private static final Logger log = LoggerFactory.getLogger(LongTaskStore.class);

    private static final TypeReference<List<Map<String, Object>>> STEPS_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<String>> LOGS_TYPE = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String dbPath;

    public LongTaskStore(@Value("${TASK_DB_PATH:data/tasks.db}") String dbPath) {
        this.dbPath = dbPath;
    }

    @PostConstruct
    public void init() {
        try {
            // 确保父目录存在
            var parent = Paths.get(dbPath).toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Class.forName("org.sqlite.JDBC");
            try (Connection c = openConnection()) {
                createTable(c);
            }
            log.info("SQLite 任务存储就绪: {}", dbPath);
        } catch (Exception e) {
            log.error("初始化 SQLite 任务存储失败: {}", dbPath, e);
            throw new IllegalStateException("SQLite 任务存储初始化失败", e);
        }
    }

    /**
     * 打开一个独立连接并应用 SQLite 并发友好 PRAGMA。
     * 每个操作单独调用，用完即关，避免共享 Connection 的线程安全问题。
     */
    private Connection openConnection() throws SQLException {
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        try (Statement st = c.createStatement()) {
            // 多写者并发时等待锁（默认立即抛 "database is locked"）
            st.execute("PRAGMA busy_timeout = 5000");
            // WAL：读不阻塞写、写不阻塞读（首次设置后持久化到 db 文件）
            st.execute("PRAGMA journal_mode = WAL");
        }
        return c;
    }

    private void createTable(Connection c) throws SQLException {
        String sql = """
                CREATE TABLE IF NOT EXISTS long_tasks (
                    id TEXT PRIMARY KEY,
                    type TEXT NOT NULL,
                    model TEXT,
                    message TEXT NOT NULL,
                    status TEXT NOT NULL,
                    steps TEXT DEFAULT '[]',
                    progress_text TEXT DEFAULT '',
                    result TEXT DEFAULT '',
                    error TEXT DEFAULT '',
                    meta TEXT DEFAULT '{}',
                    prompt_tokens INTEGER DEFAULT 0,
                    completion_tokens INTEGER DEFAULT 0,
                    total_tokens INTEGER DEFAULT 0,
                    llm_call_count INTEGER DEFAULT 0,
                    logs TEXT DEFAULT '[]',
                    checkpoint INTEGER DEFAULT 0,
                    created_at INTEGER NOT NULL,
                    started_at INTEGER DEFAULT 0,
                    finished_at INTEGER DEFAULT 0,
                    duration_ms INTEGER DEFAULT 0
                )
                """;
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    /**
     * 保存（upsert）任务到 SQLite。
     */
    public void save(LongTask task) {
        String sql = """
                INSERT INTO long_tasks (id, type, model, message, status, steps, progress_text,
                    result, error, meta, prompt_tokens, completion_tokens, total_tokens,
                    llm_call_count, logs, checkpoint, created_at, started_at, finished_at, duration_ms)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(id) DO UPDATE SET
                    status=excluded.status, steps=excluded.steps, progress_text=excluded.progress_text,
                    result=excluded.result, error=excluded.error, meta=excluded.meta,
                    prompt_tokens=excluded.prompt_tokens, completion_tokens=excluded.completion_tokens,
                    total_tokens=excluded.total_tokens, llm_call_count=excluded.llm_call_count,
                    logs=excluded.logs, checkpoint=excluded.checkpoint,
                    started_at=excluded.started_at, finished_at=excluded.finished_at, duration_ms=excluded.duration_ms
                """;
        try (Connection c = openConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, task.getId());
            ps.setString(2, task.getType());
            ps.setString(3, task.getModel());
            ps.setString(4, task.getMessage());
            ps.setString(5, task.getStatus());
            ps.setString(6, toJson(task.getSteps()));
            ps.setString(7, task.getProgressText());
            ps.setString(8, task.getResult());
            ps.setString(9, task.getError());
            ps.setString(10, toJson(task.getMeta()));
            ps.setLong(11, task.getPromptTokens());
            ps.setLong(12, task.getCompletionTokens());
            ps.setLong(13, task.getTotalTokens());
            ps.setLong(14, task.getLlmCallCount());
            ps.setString(15, toJson(task.getLogs()));
            ps.setInt(16, task.getCheckpoint());
            ps.setLong(17, task.getCreatedAt());
            ps.setLong(18, task.getStartedAt());
            ps.setLong(19, task.getFinishedAt());
            ps.setLong(20, task.getDurationMs());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("保存任务 [{}] 到 SQLite 失败: {}", task.getId(), e.getMessage());
        }
    }

    /**
     * 加载全部任务（用于重启恢复）。
     */
    public List<LongTask> loadAll() {
        List<LongTask> result = new ArrayList<>();
        String sql = """
                SELECT * FROM long_tasks ORDER BY created_at DESC
                """;
        try (Connection c = openConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                result.add(mapRow(rs));
            }
        } catch (SQLException e) {
            log.warn("从 SQLite 加载任务失败: {}", e.getMessage());
        }
        return result;
    }

    /** 删除任务 */
    public void delete(String id) {
        try (Connection c = openConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM long_tasks WHERE id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("删除任务 [{}] 失败: {}", id, e.getMessage());
        }
    }

    /**
     * 清理过期的已完成任务（隐私保护 + 防止数据库无限增长）。
     *
     * <p>只删除已完成（status=completed/failed）且 finished_at 超过 retentionDays 天的任务。
     * 正在运行（running/pending）的任务不会被删除，避免中断执行中的任务。
     *
     * @param retentionDays 保留天数（默认 30 天）
     * @return 被删除的任务数量
     */
    public int cleanupExpired(int retentionDays) {
        long cutoff = System.currentTimeMillis() - (long) retentionDays * 24 * 60 * 60 * 1000;
        String sql = """
                DELETE FROM long_tasks
                WHERE status IN ('completed', 'failed', 'cancelled')
                  AND finished_at > 0
                  AND finished_at < ?
                """;
        try (Connection c = openConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, cutoff);
            int deleted = ps.executeUpdate();
            if (deleted > 0) {
                log.info("[隐私保护] 已清理 {} 个超过 {} 天的已完成长时任务", deleted, retentionDays);
            }
            return deleted;
        } catch (SQLException e) {
            log.warn("清理过期任务失败: {}", e.getMessage());
            return 0;
        }
    }

    private LongTask mapRow(ResultSet rs) throws SQLException {
        return LongTask.restore(
                rs.getString("id"),
                rs.getString("type"),
                rs.getString("model"),
                rs.getString("message"),
                rs.getString("status"),
                fromJson(rs.getString("steps"), STEPS_TYPE, new ArrayList<>()),
                rs.getString("progress_text"),
                rs.getString("result"),
                rs.getString("error"),
                fromJson(rs.getString("meta"), MAP_TYPE, new LinkedHashMap<>()),
                rs.getLong("prompt_tokens"),
                rs.getLong("completion_tokens"),
                rs.getLong("total_tokens"),
                rs.getLong("llm_call_count"),
                fromJson(rs.getString("logs"), LOGS_TYPE, new ArrayList<>()),
                rs.getInt("checkpoint"),
                rs.getLong("created_at"),
                rs.getLong("started_at"),
                rs.getLong("finished_at"),
                rs.getLong("duration_ms")
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
