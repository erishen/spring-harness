package com.example.springharness.controller;

import com.example.springharness.memory.MemoryStore;
import com.example.springharness.rag.DocumentInfo;
import com.example.springharness.rag.RagService;
import com.example.springharness.task.LongTaskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 隐私数据管理 Controller（GDPR 合规）。
 *
 * <p>提供个人数据的导出、删除、统计功能，符合 GDPR "数据可携带权" 和 "被遗忘权"。
 *
 * <p>接口：
 * <ul>
 *   <li>GET /api/privacy/summary - 个人数据统计（各模块数据量）</li>
 *   <li>GET /api/privacy/export - 导出所有个人数据（JSON 文件下载）</li>
 *   <li>DELETE /api/privacy/delete - 删除所有个人数据（不可恢复）</li>
 * </ul>
 *
 * <p>个人数据范围：
 * <ul>
 *   <li>长时任务记录（含执行步骤、日志、结果）</li>
 *   <li>Memory 长期记忆（用户偏好、事实）</li>
 *   <li>RAG 知识库文档（用户上传的文件及向量数据）</li>
 * </ul>
 *
 * <p>注意：聊天记录存储在前端 localStorage，不在后端，需在前端单独清理。
 */
@RestController
@RequestMapping("/api/privacy")
public class PrivacyController {

    private static final Logger log = LoggerFactory.getLogger(PrivacyController.class);

    private final LongTaskStore taskStore;
    private final MemoryStore memoryStore;
    private final RagService ragService;

    public PrivacyController(LongTaskStore taskStore, MemoryStore memoryStore, RagService ragService) {
        this.taskStore = taskStore;
        this.memoryStore = memoryStore;
        this.ragService = ragService;
    }

    /**
     * 个人数据统计：各模块数据量，用于前端展示。
     */
    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> summary() {
        Map<String, Object> result = new LinkedHashMap<>();

        // 长时任务
        var tasks = taskStore.loadAll();
        Map<String, Object> taskStats = new LinkedHashMap<>();
        taskStats.put("total", tasks.size());
        taskStats.put("completed", tasks.stream().filter(t -> "completed".equals(t.getStatus())).count());
        taskStats.put("failed", tasks.stream().filter(t -> "failed".equals(t.getStatus())).count());
        taskStats.put("running", tasks.stream().filter(t -> "running".equals(t.getStatus())).count());
        result.put("longTasks", taskStats);

        // Memory
        Map<String, Object> memoryStats = new LinkedHashMap<>();
        memoryStats.put("total", memoryStore.count());
        result.put("memory", memoryStats);

        // RAG
        Map<String, Object> ragStats = new LinkedHashMap<>();
        var docs = ragService.listDocuments();
        ragStats.put("documents", docs.size());
        ragStats.put("totalChunks", docs.stream().mapToInt(DocumentInfo::chunkCount).sum());
        result.put("rag", ragStats);

        // 导出时间
        result.put("generatedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

        return ResponseEntity.ok(result);
    }

    /**
     * 导出所有个人数据（JSON 文件下载）。
     * 符合 GDPR "数据可携带权"：用户可以获取其个人数据的结构化副本。
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export() {
        log.info("[隐私保护] 用户请求导出所有个人数据");

        Map<String, Object> export = new LinkedHashMap<>();
        export.put("exportFormat", "spring-harness-privacy-export-v1");
        export.put("generatedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

        // 长时任务
        export.put("longTasks", taskStore.loadAll());

        // Memory
        export.put("memory", memoryStore.listAll());

        // RAG 文档元信息（不含原始文件内容，原始文件需用户自行保留）
        export.put("ragDocuments", ragService.listDocuments());

        // 导出说明
        Map<String, String> notes = new LinkedHashMap<>();
        notes.put("chatHistory", "聊天记录存储在浏览器 localStorage，不在后端数据中，请在前端导出/清理");
        notes.put("ragFiles", "RAG 原始上传文件未保留在服务器，仅保留文本内容和向量数据");
        notes.put("encryption", "如启用 Memory 加密，导出的 memory 内容为解密后的明文");
        export.put("notes", notes);

        try {
            String json = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(export);

            String filename = "spring-harness-privacy-export-" +
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".json";

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("导出个人数据失败", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 删除所有个人数据（不可恢复）。
     * 符合 GDPR "被遗忘权"：用户可以要求删除其所有个人数据。
     *
     * <p>删除范围：
     * <ul>
     *   <li>所有长时任务记录（含步骤、日志、结果）</li>
     *   <li>所有 Memory 长期记忆</li>
     *   <li>所有 RAG 文档及向量数据</li>
     * </ul>
     *
     * <p>注意：聊天记录在前端 localStorage，需用户在浏览器中清理或使用前端的"清空聊天记录"功能。
     */
    @DeleteMapping("/delete")
    public ResponseEntity<Map<String, Object>> deleteAll() {
        log.warn("[隐私保护] 用户请求删除所有个人数据！此操作不可恢复。");

        Map<String, Object> result = new LinkedHashMap<>();

        // 删除长时任务
        int taskCount = 0;
        try {
            var tasks = taskStore.loadAll();
            for (var task : tasks) {
                taskStore.delete(task.getId());
                taskCount++;
            }
            result.put("longTasksDeleted", taskCount);
        } catch (Exception e) {
            log.warn("删除长时任务失败: {}", e.getMessage());
            result.put("longTasksError", e.getMessage());
        }

        // 删除 Memory
        try {
            int memoryCount = memoryStore.count();
            memoryStore.clear();
            result.put("memoryDeleted", memoryCount);
        } catch (Exception e) {
            log.warn("删除 Memory 失败: {}", e.getMessage());
            result.put("memoryError", e.getMessage());
        }

        // 删除 RAG 文档
        try {
            int ragCount = ragService.clearAll();
            result.put("ragDocumentsDeleted", ragCount);
        } catch (Exception e) {
            log.warn("删除 RAG 文档失败: {}", e.getMessage());
            result.put("ragError", e.getMessage());
        }

        result.put("completedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        result.put("note", "聊天记录存储在浏览器 localStorage，请在前端单独清理");

        log.info("[隐私保护] 个人数据删除完成: 任务 {} 个, Memory {} 条, RAG {} 个文档",
                result.get("longTasksDeleted"), result.get("memoryDeleted"), result.get("ragDocumentsDeleted"));

        return ResponseEntity.ok(result);
    }
}
