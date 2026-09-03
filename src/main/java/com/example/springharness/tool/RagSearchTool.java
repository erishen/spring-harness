package com.example.springharness.tool;

import com.example.springharness.rag.RagService;
import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.function.Function;

/**
 * 知识库语义检索工具：基于已上传文档（RAG 向量库）检索与问题最相关的文本片段。
 *
 * 供 ReAct Agent / PSE Specialist / 长时任务共用：
 * 注册进 ToolConfig 后，工具描述会随 ToolDescriptionService 动态注入各模式的 prompt，
 * 模型可在合适时机自主决定是否检索知识库。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonClassDescription("知识库语义检索：基于已上传的知识库文档检索与问题最相关的文本片段")
public class RagSearchTool implements Function<RagSearchTool.Request, RagSearchTool.Response> {

    private final RagService ragService;

    public RagSearchTool(RagService ragService) {
        this.ragService = ragService;
    }

    public record Request(
            @JsonProperty(value = "query")
            @JsonPropertyDescription("要检索的问题或关键词，用中文描述，尽量包含核心实体与主题")
            String query,
            @JsonProperty(value = "topK")
            @JsonPropertyDescription("返回的片段数量，默认 4，最大 8")
            Integer topK
    ) {}

    public record Fragment(String content, String source, String docId) {}

    public record Response(List<Fragment> fragments, int total) {}

    @Override
    public Response apply(Request request) {
        long start = System.currentTimeMillis();
        String query = request.query() != null ? request.query().trim() : "";
        if (query.isBlank()) {
            return new Response(List.of(), 0);
        }
        int topK = request.topK() != null ? Math.min(Math.max(request.topK(), 1), 8) : 4;

        List<Document> docs = ragService.search(query, topK);
        List<Fragment> fragments = docs.stream()
                .map(d -> new Fragment(
                        d.getText(),
                        String.valueOf(d.getMetadata().getOrDefault("source", "")),
                        String.valueOf(d.getMetadata().getOrDefault("docId", ""))
                ))
                .toList();

        Response response = new Response(fragments, fragments.size());
        ToolCallRecorder.record("search_knowledge", request, response.toString(), System.currentTimeMillis() - start);
        return response;
    }
}
