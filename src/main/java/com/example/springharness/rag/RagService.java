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

    @Value("${rag.document-content-file:data/document-contents.json}")
    private String documentContentFile;

    /** 文档元信息存储：docId -> DocumentInfo */
    private final Map<String, DocumentInfo> documentRegistry = new ConcurrentHashMap<>();

    /** 文档内容存储：docId -> 块文本列表（用于预览） */
    private final Map<String, List<String>> documentContents = new ConcurrentHashMap<>();

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
     * 启动时加载文档元信息和内容（向量数据由 RagConfig 在 Bean 创建时加载）。
     */
    @PostConstruct
    public void loadRegistry() {
        // 加载文档元信息
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

        // 加载文档内容（用于预览）
        File contentFile = new File(documentContentFile);
        if (contentFile.exists() && contentFile.length() > 0) {
            try {
                Map<String, List<String>> contents = objectMapper.readValue(contentFile,
                        new TypeReference<Map<String, List<String>>>() {});
                documentContents.putAll(contents);
                log.info("文档内容已从文件加载: {} ({} 个文档)", documentContentFile, contents.size());
            } catch (Exception e) {
                log.warn("文档内容文件加载失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 上传并索引文档。
     * 支持 PDF、TXT、MD 等文本格式。
     */
    public DocumentInfo addDocument(MultipartFile file) throws IOException {
        // 上传校验：后缀白名单 + 大小 + MIME + 魔数（防止伪造后缀/二进制文件）
        validateUpload(file);

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

        // 保存文档内容（用于预览）
        List<String> chunkTexts = chunks.stream().map(Document::getText).toList();
        documentContents.put(docId, chunkTexts);

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
     * 获取文档内容（用于预览），按块顺序拼接。
     */
    public String getDocumentContent(String docId) {
        List<String> chunks = documentContents.get(docId);
        if (chunks == null || chunks.isEmpty()) {
            return null;
        }
        return String.join("\n\n---\n\n", chunks);
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

        // 删除文档内容
        documentContents.remove(docId);

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
            File contentFile = new File(documentContentFile);
            for (File f : List.of(vecFile, regFile, contentFile)) {
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

            // 保存文档内容（用于预览）
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(contentFile, documentContents);

            log.debug("RAG 数据已持久化: {} 个文档, {} 个向量块",
                    documentRegistry.size(),
                    documentRegistry.values().stream().mapToInt(DocumentInfo::chunkCount).sum());
        } catch (Exception e) {
            log.error("RAG 数据持久化失败: {}", e.getMessage(), e);
        }
    }

    // ==================== 私有方法 ====================

    /** 允许上传的文件后缀白名单 */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            ".pdf", ".doc", ".docx", ".txt", ".md", ".markdown",
            ".json", ".xml", ".java", ".py", ".js", ".ts", ".html", ".css",
            ".csv", ".log", ".yaml", ".yml", ".properties", ".sql", ".sh",
            ".go", ".rs", ".c", ".cpp", ".h", ".hpp"
    );

    /** 允许的 MIME 类型（软校验，浏览器常给 octet-stream，故仅作参考） */
    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "application/pdf", "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "text/plain", "text/markdown", "text/x-markdown", "application/json",
            "text/xml", "application/xml", "text/html", "text/css", "text/csv",
            "application/x-yaml", "application/yaml", "text/yaml",
            "application/x-java-source", "text/x-python", "text/x-java-source",
            "application/octet-stream"
    );

    private static final long MAX_FILE_SIZE = 50L * 1024 * 1024;

    /**
     * 上传文件校验：后缀白名单 + 大小 + MIME + 魔数。
     * 防止伪造后缀（如把 .exe 改名为 .pdf）或上传二进制/损坏文件。
     */
    private void validateUpload(MultipartFile file) throws IOException {
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        String lower = name.toLowerCase();
        String ext = lower.contains(".") ? lower.substring(lower.lastIndexOf('.')) : "";
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new IllegalArgumentException("不支持的文件类型: " + ext
                    + "，支持: " + String.join(" / ", ALLOWED_EXTENSIONS));
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("文件过大: " + (file.getSize() / 1024 / 1024) + "MB，上限 50MB");
        }
        // MIME 软校验：不在白名单且不是 octet-stream 时拒绝（防明显伪装）
        String mime = file.getContentType();
        if (mime != null && !mime.isBlank()
                && !ALLOWED_MIME_TYPES.contains(mime.toLowerCase())
                && !mime.startsWith("text/")) {
            throw new IllegalArgumentException("文件 MIME 类型不被允许: " + mime);
        }

        // 魔数校验：读取文件头 8 字节
        byte[] head = file.getInputStream().readNBytes(8);
        if (lower.endsWith(".pdf")) {
            if (head.length < 5 || !"%PDF-".equals(new String(head, 0, 5, StandardCharsets.ISO_8859_1))) {
                throw new IllegalArgumentException("PDF 文件魔数校验失败（文件可能损坏或伪造后缀）");
            }
        } else if (lower.endsWith(".docx")) {
            // DOCX 本质是 ZIP，魔数 PK\x03\x04
            if (head.length < 4 || !(head[0] == 0x50 && head[1] == 0x4B && head[2] == 0x03 && head[3] == 0x04)) {
                throw new IllegalArgumentException("DOCX 文件魔数校验失败（应为 ZIP 格式）");
            }
        } else if (lower.endsWith(".doc")) {
            // 旧版 OLE 复合文档魔数 D0 CF 11 E0 A1 B1 1A E1
            if (head.length < 8 || !(head[0] == (byte) 0xD0 && head[1] == (byte) 0xCF
                    && head[2] == 0x11 && head[3] == (byte) 0xE0)) {
                throw new IllegalArgumentException("DOC 文件魔数校验失败（应为 OLE 复合文档）");
            }
        } else {
            // 文本类文件：检查前 8KB 可打印字符比例，拒绝明显二进制
            byte[] sample = file.getInputStream().readNBytes(8192);
            if (sample.length > 0) {
                int printable = 0;
                for (byte b : sample) {
                    int v = b & 0xFF;
                    // 允许：制表/换行/回车、ASCII 可打印、UTF-8 多字节（>=0x80）
                    if (v == 0x09 || v == 0x0A || v == 0x0D || (v >= 0x20 && v <= 0x7E) || v >= 0x80) {
                        printable++;
                    }
                }
                if ((double) printable / sample.length < 0.85) {
                    throw new IllegalArgumentException("文件内容疑似二进制（可打印字符比例过低），不支持上传");
                }
            }
        }
    }

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
