package com.example.springharness.tool;

import com.alibaba.cloud.ai.model.RerankModel;
import com.alibaba.cloud.ai.model.RerankRequest;
import com.example.springharness.rag.RagService;
import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Function;

/**
 * 知识库语义检索工具（Rerank 增强版）：基于已上传文档检索 → Rerank 精排 → 返回最相关片段。
 *
 * 流程：
 * 1. 向量检索候选片段（topK=10，保证召回）
 * 2. RerankModel（qwen3.7-text-rerank）对候选精排
 * 3. 取精排后的 topN 返回（含相关性分数）
 * 4. Rerank 失败时自动回退纯向量检索，保证工具始终可用
 *
 * 供 ReAct Agent / PSE Specialist / 长时任务共用。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonClassDescription("知识库语义检索（Rerank 精排）：基于已上传的知识库文档检索并精排与问题最相关的文本片段")
@Component
public class RagSearchTool implements Function<RagSearchTool.Request, RagSearchTool.Response> {

    private static final Logger log = LoggerFactory.getLogger(RagSearchTool.class);

    /** 向量检索候选数（召回），再交给 Rerank 精排 */
    private static final int CANDIDATE_K = 10;

    /** 最近一次检索链路（供 Runtime 面板展示"检索→精排→返回"流水线） */
    private static volatile SearchTrace lastTrace;

    /** 检索链路记录：query、候选数、返回数、精排方式、耗时、返回片段摘要 */
    public record SearchTrace(String query, int candidateCount, int returnedCount,
                              String rankMethod, long durationMs, List<FragmentInfo> fragments) {}

    /** 返回片段摘要：来源 + 相关性分数 + 内容片段（前 160 字） */
    public record FragmentInfo(String source, String docId, Double score, String snippet) {}

    private final RagService ragService;
    private final RerankModel rerankModel;

    public RagSearchTool(RagService ragService, RerankModel rerankModel) {
        this.ragService = ragService;
        this.rerankModel = rerankModel;
    }

    /** 获取最近一次检索链路（无则返回 null）。 */
    public static SearchTrace getLastTrace() {
        return lastTrace;
    }

    public record Request(
            @JsonProperty(value = "query")
            @JsonPropertyDescription("要检索的问题或关键词，用中文描述，尽量包含核心实体与主题")
            String query,
            @JsonProperty(value = "topK")
            @JsonPropertyDescription("返回的片段数量，默认 4，最大 8")
            Integer topK
    ) {}

    public record Fragment(String content, String source, String docId, Double score) {}

    public record Response(List<Fragment> fragments, int total, String rankMethod) {}

    @Override
    public Response apply(Request request) {
        long start = System.currentTimeMillis();
        String query = request.query() != null ? request.query().trim() : "";
        if (query.isBlank()) {
            return new Response(List.of(), 0, "rerank");
        }
        int topN = request.topK() != null ? Math.min(Math.max(request.topK(), 1), 8) : 4;

        List<Document> candidates = ragService.search(query, CANDIDATE_K);
        List<Fragment> fragments;
        String rankMethod = "rerank";

        if (candidates.isEmpty()) {
            fragments = List.of();
        } else {
            try {
                // Rerank 精排：对候选片段重新排序，返回精排后的 topN
                var rerankResponse = rerankModel.call(new RerankRequest(query, candidates));
                fragments = rerankResponse.getResults().stream()
                        .limit(topN)
                        .map(dws -> {
                            Document d = dws.getOutput();
                            return new Fragment(
                                    d.getText(),
                                    String.valueOf(d.getMetadata().getOrDefault("source", "")),
                                    String.valueOf(d.getMetadata().getOrDefault("docId", "")),
                                    dws.getScore()
                            );
                        })
                        .toList();
            } catch (Exception e) {
                // Rerank 失败时回退纯向量检索，保证工具可用
                log.warn("Rerank 精排失败，回退纯向量检索: {}", e.getMessage());
                rankMethod = "vector-fallback";
                fragments = candidates.stream()
                        .limit(topN)
                        .map(d -> new Fragment(
                                d.getText(),
                                String.valueOf(d.getMetadata().getOrDefault("source", "")),
                                String.valueOf(d.getMetadata().getOrDefault("docId", "")),
                                null
                        ))
                        .toList();
            }
        }

        Response response = new Response(fragments, fragments.size(), rankMethod);
        // 记录检索链路（供 Runtime 面板展示）
        long durationMs = System.currentTimeMillis() - start;
        lastTrace = new SearchTrace(
                query,
                candidates.size(),
                fragments.size(),
                rankMethod,
                durationMs,
                fragments.stream()
                        .map(f -> new FragmentInfo(f.source(), f.docId(), f.score(),
                                f.content().length() > 160 ? f.content().substring(0, 160) + "…" : f.content()))
                        .toList()
        );
        ToolCallRecorder.record("search_knowledge", request, response.toString(), durationMs);
        return response;
    }
}
