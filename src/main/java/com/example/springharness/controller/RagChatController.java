package com.example.springharness.controller;

import com.alibaba.cloud.ai.advisor.RetrievalRerankAdvisor;
import com.alibaba.cloud.ai.model.RerankModel;
import com.example.springharness.service.MultiModelService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * RAG 增强对话接口：向量检索 → Rerank 重排序 → 注入 Prompt → LLM 生成回答。
 *
 * 使用 RetrievalRerankAdvisor 一站式完成检索和重排序：
 * 1. 先用 VectorStore 检索 topK 个候选文档
 * 2. 再用 RerankModel（qwen3.7-text-rerank）对候选文档精排
 * 3. 最后把重排序后的文档注入 Prompt，LLM 基于更精准的上下文回答
 */
@RestController
@RequestMapping("/chat/rag")
public class RagChatController {

    private final MultiModelService multiModelService;
    private final VectorStore vectorStore;
    private final RerankModel rerankModel;

    @Value("${MAX_TOKENS:4096}")
    private int maxTokens;

    public RagChatController(MultiModelService multiModelService,
                              VectorStore vectorStore,
                              RerankModel rerankModel) {
        this.multiModelService = multiModelService;
        this.vectorStore = vectorStore;
        this.rerankModel = rerankModel;
    }

    @GetMapping
    public Map<String, Object> chat(
            @RequestParam(defaultValue = "你好") String message,
            @RequestParam(required = false) String model) {

        // RetrievalRerankAdvisor：先检索 10 个候选，再用 rerank 精排
        // topK 设大一些（10），让 rerank 有更多候选可选，最终取最相关的
        RetrievalRerankAdvisor advisor = new RetrievalRerankAdvisor(
                vectorStore,
                rerankModel,
                SearchRequest.builder().query(message).topK(10).build()
        );

        var prompt = multiModelService.createChatClientBuilder(model).build()
                .prompt()
                .user(message)
                .advisors(advisor)
                .system("""
                        你是一个知识库问答助手。
                        请基于检索到的文档内容回答用户问题。
                        如果文档中没有相关信息，请如实告知用户，不要编造内容。
                        用中文回答，回答简洁明了。
                        """);
        if (model != null && !model.isBlank()) {
            prompt.options(ChatOptions.builder().model(model).maxTokens(maxTokens).build());
        }
        String answer = prompt.call().content();

        return Map.of(
                "answer", answer,
                "mode", "rag-rerank"
        );
    }
}
