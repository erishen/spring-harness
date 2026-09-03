package com.example.springharness.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;

/**
 * RAG 配置类：向量库、文本切分器。
 *
 * 向量库使用 SimpleVectorStore + 文件持久化：
 * - 启动时从 ${rag.vector-store-file}（默认 data/vector-store.json）加载
 * - 每次增删文档后由 RagService 调用 save() 写入文件
 * - 重启后向量数据不丢失
 *
 * 生产环境可切换为 Redis/PGVector/Milvus 等，仅需替换 VectorStore Bean，
 * 其余代码（RagService、Controller）无需改动。
 */
@Configuration
public class RagConfig {

    private static final Logger log = LoggerFactory.getLogger(RagConfig.class);

    @Value("${rag.vector-store-file:data/vector-store.json}")
    private String vectorStoreFile;

    /**
     * 文件持久化向量库。启动时自动加载已有数据。
     */
    @Bean
    public VectorStore vectorStore(EmbeddingModel embeddingModel) {
        SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();

        File file = new File(vectorStoreFile);
        if (file.exists() && file.length() > 0) {
            try {
                store.load(file);
                log.info("向量库已从文件加载: {} ({} bytes)", vectorStoreFile, file.length());
            } catch (Exception e) {
                log.warn("向量库文件加载失败，将使用空向量库: {}", e.getMessage());
            }
        } else {
            log.info("向量库文件不存在，将创建新的空向量库: {}", vectorStoreFile);
            // 确保父目录存在
            File parentDir = file.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }
        }

        return store;
    }

    /**
     * 文本切分器：按 token 数切分，chunk 之间保留重叠。
     * 使用默认配置（约 800 token/chunk）。
     */
    @Bean
    public TokenTextSplitter textSplitter() {
        return new TokenTextSplitter();
    }

    /**
     * Markdown 结构化切分器：按标题层级（# / ## / ###）切分。
     * 适用于 .md 文件，保留标题作为块元数据。
     */
    @Bean
    public MarkdownTextSplitter markdownTextSplitter() {
        return new MarkdownTextSplitter();
    }

    /**
     * 段落切分器：按空行分段，适用于 PDF、DOC 等按段落组织的文档。
     */
    @Bean
    public ParagraphTextSplitter paragraphTextSplitter() {
        return new ParagraphTextSplitter();
    }
}
