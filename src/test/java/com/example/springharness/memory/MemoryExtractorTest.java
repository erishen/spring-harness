package com.example.springharness.memory;

import com.example.springharness.service.MultiModelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MemoryExtractor 单元测试：信号词预过滤 + LLM 抽取 JSON 的容错解析。
 * 重点回归：keywords 返回 JSON 数组时的反序列化（历史 bug）。
 */
class MemoryExtractorTest {

    private MemoryExtractor extractor;
    private MultiModelService multiModelService;
    private ChatClient.Builder builder;
    private ChatResponse response;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        multiModelService = mock(MultiModelService.class);
        builder = mock(ChatClient.Builder.class);
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        response = mock(ChatResponse.class, RETURNS_DEEP_STUBS);

        when(multiModelService.createChatClientBuilder(any())).thenReturn(builder);
        when(builder.build()).thenReturn(client);
        when(builder.build().prompt().system(anyString()).user(anyString()).call().chatResponse())
                .thenReturn(response);

        extractor = new MemoryExtractor(multiModelService);
    }

    private void mockLlmReturn(String text) {
        // response.getResult().getOutput().getText()
        when(response.getResult().getOutput().getText()).thenReturn(text);
    }

    // ==================== hasSignal ====================

    @Test
    void hasSignalHitsKeywords() {
        assertThat(extractor.hasSignal("我是测试用户")).isTrue();
        assertThat(extractor.hasSignal("我喜欢喝咖啡")).isTrue();
        assertThat(extractor.hasSignal("我的目标是退休")).isTrue();
    }

    @Test
    void hasSignalRejectsOrdinaryMessages() {
        assertThat(extractor.hasSignal("请帮我写冒泡排序")).isFalse();
        assertThat(extractor.hasSignal("今天天气怎么样")).isFalse();
        assertThat(extractor.hasSignal("")).isFalse();
        assertThat(extractor.hasSignal(null)).isFalse();
    }

    // ==================== extract：JSON 解析 ====================

    @Test
    void extractParsesKeywordsArray() {
        // keywords 为 JSON 数组（历史 bug 场景）
        mockLlmReturn("""
                [{"category":"preference","content":"用户喜欢喝咖啡","keywords":["咖啡","喜好"]},
                 {"category":"fact","content":"用户常住上海","keywords":["上海"]}]
                """);
        List<MemoryExtractor.ExtractedMemory> result = extractor.extract("我是用户，我喜欢喝咖啡，我住在上海");
        assertThat(result).hasSize(2);
        assertThat(result.get(0).category()).isEqualTo("preference");
        assertThat(result.get(0).keywords()).containsExactly("咖啡", "喜好");
        assertThat(result.get(1).content()).isEqualTo("用户常住上海");
    }

    @Test
    void extractParsesKeywordsString() {
        // keywords 为逗号分隔字符串（兼容）
        mockLlmReturn(""" 
                [{"category":"fact","content":"用户喜欢简洁的回答","keywords":"简洁, 回答"}]
                """);
        List<MemoryExtractor.ExtractedMemory> result = extractor.extract("我喜欢简洁的回答");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).keywords()).containsExactly("简洁", "回答");
    }

    @Test
    void extractReturnsEmptyWhenNoMemory() {
        mockLlmReturn("[]");
        assertThat(extractor.extract("你好")).isEmpty();
    }

    @Test
    void extractIgnoresBlankContent() {
        mockLlmReturn("""
                [{"category":"preference","content":"","keywords":[]},
                 {"category":"fact","content":"用户喜欢咖啡","keywords":["咖啡"]}]
                """);
        List<MemoryExtractor.ExtractedMemory> result = extractor.extract("我喜欢咖啡");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).content()).isEqualTo("用户喜欢咖啡");
    }

    @Test
    void extractFallsBackSilentlyOnNonJson() {
        // LLM 返回非 JSON：不应抛异常，返回空列表
        mockLlmReturn("抱歉，我无法完成。");
        assertThat(extractor.extract("我是用户")).isEmpty();
    }

    @Test
    void extractHandlesLlmException() {
        // LLM 调用抛异常：静默降级，不向上传播
        when(builder.build().prompt().system(anyString()).user(anyString()).call().chatResponse())
                .thenThrow(new RuntimeException("upstream 429"));
        assertThat(extractor.extract("我是用户，我喜欢咖啡")).isEmpty();
    }

    @Test
    void extractHandlesBlankResponse() {
        mockLlmReturn("");
        assertThat(extractor.extract("我是用户")).isEmpty();
    }
}
