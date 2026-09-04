package com.example.springharness.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MemoryService 单元测试：记忆检索与注入文本生成（中文 bigram 分词）。
 */
class MemoryServiceTest {

    @TempDir
    Path tempDir;

    private MemoryService service;
    private MemoryStore store;

    @BeforeEach
    void setUp() {
        store = new MemoryStore(tempDir.resolve("svc-" + UUID.randomUUID() + ".db").toString());
        store.init();
        service = new MemoryService(store, null); // extractor 仅抽取用，本测试不触发
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "topK", 5);
        ReflectionTestUtils.setField(service, "maxItems", 200);
    }

    private void add(String category, String content, List<String> keywords) {
        long now = System.currentTimeMillis();
        store.insert(new MemoryItem(UUID.randomUUID().toString().replace("-", ""),
                category, content, keywords, "test", now, now, 0));
    }

    @Test
    void retrieveMatchesByChineseBigram() {
        add("preference", "用户喜欢喝咖啡", List.of("咖啡"));
        add("fact", "用户常住上海", List.of("上海"));

        // query 含「咖啡」应命中偏好记忆
        List<MemoryItem> hits = service.retrieve("你记得我喜欢喝什么吗", 5);
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).getContent()).contains("咖啡");

        // query 与记忆无关时可能无命中
        List<MemoryItem> none = service.retrieve("写个快速排序", 5);
        assertThat(none).isNotNull(); // 空也不抛异常
    }

    @Test
    void buildMemoryContextFormatsInjection() {
        add("preference", "用户喜欢简洁的回答", List.of("简洁"));
        String ctx = service.buildMemoryContext("用户喜欢什么风格的回答");
        assertThat(ctx).contains("【关于用户的长期记忆】");
        assertThat(ctx).contains("[偏好] 用户喜欢简洁的回答");
        assertThat(ctx).contains("合理参考");
    }

    @Test
    void buildMemoryContextReturnsEmptyWhenDisabled() {
        add("fact", "用户喜欢喝咖啡", List.of("咖啡"));
        ReflectionTestUtils.setField(service, "enabled", false);
        assertThat(service.buildMemoryContext("咖啡")).isEmpty();
    }

    @Test
    void buildMemoryContextReturnsEmptyWhenNoMatch() {
        add("fact", "用户常住上海", List.of("上海"));
        // 检索不到相关内容时返回空串
        assertThat(service.buildMemoryContext("今天天气")).isEmpty();
    }

    @Test
    void countReflectsStoredItems() {
        assertThat(service.count()).isZero();
        add("fact", "记忆条目", List.of());
        assertThat(service.count()).isEqualTo(1);
    }
}
