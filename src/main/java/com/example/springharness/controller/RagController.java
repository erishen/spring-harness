package com.example.springharness.controller;

import com.example.springharness.rag.DocumentInfo;
import com.example.springharness.rag.RagService;
import com.example.springharness.tool.RagSearchTool;
import org.springframework.ai.document.Document;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * RAG 文档管理接口：上传、列出、删除、检索。
 */
@RestController
@RequestMapping("/rag")
public class RagController {

    private final RagService ragService;

    public RagController(RagService ragService) {
        this.ragService = ragService;
    }

    /**
     * 上传并索引文档。
     * 支持 PDF、TXT、MD 等文本格式。
     */
    @PostMapping("/documents")
    public ResponseEntity<?> uploadDocument(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "文件为空"));
        }
        try {
            DocumentInfo info = ragService.addDocument(file);
            return ResponseEntity.ok(info);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "文件读取失败：" + e.getMessage()));
        }
    }

    /**
     * 列出所有已索引文档。
     */
    @GetMapping("/documents")
    public List<DocumentInfo> listDocuments() {
        return ragService.listDocuments();
    }

    /**
     * 删除文档及其所有向量块。
     */
    @DeleteMapping("/documents/{docId}")
    public ResponseEntity<Map<String, Object>> deleteDocument(@PathVariable String docId) {
        boolean deleted = ragService.deleteDocument(docId);
        if (deleted) {
            return ResponseEntity.ok(Map.of("deleted", true, "docId", docId));
        }
        return ResponseEntity.notFound().build();
    }

    /**
     * 预览文档内容（按块顺序拼接，用于在线查看）。
     */
    @GetMapping("/documents/{docId}/content")
    public ResponseEntity<Map<String, Object>> getDocumentContent(@PathVariable String docId) {
        String content = ragService.getDocumentContent(docId);
        if (content == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("docId", docId, "content", content));
    }

    /**
     * 文档统计信息。
     */
    @GetMapping("/stats")
    public Map<String, Object> getStats() {
        return ragService.getStats();
    }

    /**
     * 语义检索（调试用）：返回最相关的 topK 个文本片段。
     */
    @GetMapping("/search")
    public List<Map<String, Object>> search(
            @RequestParam String query,
            @RequestParam(defaultValue = "5") int topK) {
        return ragService.search(query, topK).stream()
                .map(doc -> Map.of(
                        "id", doc.getId(),
                        "content", doc.getText(),
                        "source", doc.getMetadata().getOrDefault("source", ""),
                        "docId", doc.getMetadata().getOrDefault("docId", "")
                ))
                .toList();
    }

    /**
     * 最近一次 search_knowledge 工具调用的检索链路（供 Runtime 面板展示流水线）。
     * 返回 { exists, trace: { query, candidateCount, returnedCount, rankMethod, durationMs, fragments: [...] } }
     */
    @GetMapping("/search-trace")
    public Map<String, Object> searchTrace() {
        RagSearchTool.SearchTrace trace = RagSearchTool.getLastTrace();
        if (trace == null) {
            return Map.of("exists", false);
        }
        return Map.of(
                "exists", true,
                "trace", trace
        );
    }
}
