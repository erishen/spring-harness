package com.example.springharness.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RAG 服务：文档加载、切分、向量化、存储、检索、文档管理。
 *
 * 持久化策略：
 * - 向量数据：SimpleVectorStore.save() → data/vector-store.json
 * - 文档元信息：JSON 序列化 → data/document-registry.json
 * - 启动时自动加载，增删文档后立即保存（实时持久化）
 *
 * 切分策略（按文件类型）：
 * - .md → MarkdownTextSplitter（按标题层级切分）
 * - .pdf / .doc / .docx → ParagraphTextSplitter（按段落切分）
 * - 其他 → TokenTextSplitter（按 token 数切分）
 */
@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private final VectorStore vectorStore;
    private final TokenTextSplitter tokenTextSplitter;
    private final MarkdownTextSplitter markdownTextSplitter;
    private final ParagraphTextSplitter paragraphTextSplitter;

    @Value("${rag.vector-store-file:data/vector-store.json}")
    private String vectorStoreFile;

    @Value("${rag.document-registry-file:data/document-registry.json}")
    private String documentRegistryFile;

    /** 文档元信息存储：docId -> DocumentInfo */
    private final Map<String, DocumentInfo> documentRegistry = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    public RagService(VectorStore vectorStore,
                      TokenTextSplitter tokenTextSplitter,
                      MarkdownTextSplitter markdownTextSplitter,
                      ParagraphTextSplitter paragraphTextSplitter) {
        this.vectorStore = vectorStore;
        this.tokenTextSplitter = tokenTextSplitter;
        this.markdownTextSplitter = markdownTextSplitter;
        this.paragraphTextSplitter = paragraphTextSplitter;
    }

    /**
     * 启动时加载文档元信息（向量数据由 RagConfig 在 Bean 创建时加载）。
     */
    @PostConstruct
    public void loadRegistry() {
        File file = new File(documentRegistryFile);
        if (file.exists() && file.length() > 0) {
            try {
                List<DocumentInfo> list = objectMapper.readValue(file, new TypeReference<List<DocumentInfo>>() {});
                for (DocumentInfo info : list) {
                    documentRegistry.put(info.docId(), info);
                }
                log.info("文档元信息已从文件加载: {} ({} 个文档)", documentRegistryFile, list.size());
            } catch (Exception e) {
                log.warn("文档元信息文件加载失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 上传并索引文档。
     * 支持 PDF、TXT、MD 等文本格式。
     */
    public DocumentInfo addDocument(MultipartFile file) throws IOException {
        String originalFileName = file.getOriginalFilename();
        String docId = UUID.randomUUID().toString().substring(0, 8);
        List<Document> rawDocuments = loadDocuments(file, originalFileName);

        // 按文件类型选择切分器
        TextSplitter splitter = selectSplitter(originalFileName);

        // 文本切分
        List<Document> chunks = splitter.apply(rawDocuments);

        // 空文档保护
        if (chunks == null || chunks.isEmpty()) {
            throw new IllegalArgumentException("文档内容为空或无法解析，请上传包含有效文本内容的文件");
        }

        // 给每个 chunk 打上 docId 元信息
        for (Document chunk : chunks) {
            chunk.getMetadata().put("docId", docId);
            chunk.getMetadata().put("source", originalFileName);
        }

        // 向量化并存储（SimpleVectorStore.add 会自动调用 EmbeddingModel）
        vectorStore.add(chunks);

        // 记录 chunk id 列表（用于后续删除）
        List<String> chunkIds = chunks.stream().map(Document::getId).toList();

        DocumentInfo info = new DocumentInfo(
                docId,
                originalFileName,
                file.getSize(),
                chunks.size(),
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                chunkIds
        );
        documentRegistry.put(docId, info);

        // 实时持久化
        persist();

        return info;
    }

    /**
     * 列出所有已索引文档。
     */
    public List<DocumentInfo> listDocuments() {
        return new ArrayList<>(documentRegistry.values());
    }

    /**
     * 删除文档及其所有向量块。
     */
    public boolean deleteDocument(String docId) {
        DocumentInfo info = documentRegistry.remove(docId);
        if (info == null) {
            return false;
        }
        // 从向量库删除所有 chunk
        if (info.chunkIds() != null && !info.chunkIds().isEmpty()) {
            vectorStore.delete(info.chunkIds());
        }

        // 实时持久化
        persist();

        return true;
    }

    /**
     * 语义检索：返回最相关的 topK 个文本片段。
     */
    public List<Document> search(String query, int topK) {
        return vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(query)
                        .topK(topK)
                        .build()
        );
    }

    /**
     * 获取文档统计信息。
     */
    public Map<String, Object> getStats() {
        int totalChunks = documentRegistry.values().stream()
                .mapToInt(DocumentInfo::chunkCount)
                .sum();
        return Map.of(
                "documentCount", documentRegistry.size(),
                "totalChunks", totalChunks
        );
    }

    // ==================== 持久化 ====================

    /**
     * 实时持久化：同时保存向量数据和文档元信息。
     * 在 addDocument / deleteDocument 后调用。
     */
    private void persist() {
        try {
            // 确保目录存在
            File vecFile = new File(vectorStoreFile);
            File regFile = new File(documentRegistryFile);
            for (File f : List.of(vecFile, regFile)) {
                File parent = f.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
            }

            // 保存向量数据（SimpleVectorStore 特有方法）
            if (vectorStore instanceof SimpleVectorStore simpleStore) {
                simpleStore.save(vecFile);
            }

            // 保存文档元信息
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(regFile, new ArrayList<>(documentRegistry.values()));

            log.debug("RAG 数据已持久化: {} 个文档, {} 个向量块",
                    documentRegistry.size(),
                    documentRegistry.values().stream().mapToInt(DocumentInfo::chunkCount).sum());
        } catch (Exception e) {
            log.error("RAG 数据持久化失败: {}", e.getMessage(), e);
        }
    }

    // ==================== 私有方法 ====================

    /**
     * 根据文件类型选择切分器。
     * - .md → MarkdownTextSplitter（按标题层级切分）
     * - .pdf / .doc / .docx → ParagraphTextSplitter（按段落切分）
     * - 其他 → TokenTextSplitter（按 token 数切分）
     */
    private TextSplitter selectSplitter(String fileName) {
        if (fileName == null) {
            return tokenTextSplitter;
        }
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
            return markdownTextSplitter;
        }
        if (lower.endsWith(".pdf") || lower.endsWith(".doc") || lower.endsWith(".docx")) {
            return paragraphTextSplitter;
        }
        return tokenTextSplitter;
    }

    /**
     * 根据文件类型加载文档。
     */
    private List<Document> loadDocuments(MultipartFile file, String fileName) throws IOException {
        String lowerName = fileName != null ? fileName.toLowerCase() : "";

        if (lowerName.endsWith(".pdf")) {
            return loadPdf(file);
        }

        // 文本文件：TXT、MD、JSON、XML、Java 等
        String content = new String(file.getBytes(), StandardCharsets.UTF_8);
        Document doc = new Document(content);
        doc.getMetadata().put("source", fileName);
        return List.of(doc);
    }

    /**
     * 加载 PDF 文档。
     * 注意：PagePdfDocumentReader 接受 Spring Resource，绝对路径需用 FileSystemResource 包装，
     * 否则会被当作 classpath resource 导致 FileNotFoundException。
     */
    private List<Document> loadPdf(MultipartFile file) throws IOException {
        java.io.File tempFile = java.io.File.createTempFile("rag-", ".pdf");
        try {
            // 用 Files.copy 写入临时文件（比 MultipartFile.transferTo 更可靠）
            try (var in = file.getInputStream()) {
                java.nio.file.Files.copy(in, tempFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            PagePdfDocumentReader reader = new PagePdfDocumentReader(new org.springframework.core.io.FileSystemResource(tempFile));
            return reader.get();
        } finally {
            tempFile.delete();
        }
    }
}
