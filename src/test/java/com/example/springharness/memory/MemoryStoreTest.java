package com.example.springharness.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MemoryStore 单元测试：验证 SQLite 持久化的增删改查与中文关键词检索。
 */
class MemoryStoreTest {

    @TempDir
    Path tempDir;

    private MemoryStore store;

    @BeforeEach
    void setUp() {
        // 测试中关闭加密，保持原有测试逻辑
        store = new MemoryStore(tempDir.resolve("mem-" + UUID.randomUUID() + ".db").toString(), false, "");
        store.init();
    }

    private MemoryItem item(String category, String content, List<String> keywords) {
        long now = System.currentTimeMillis();
        return new MemoryItem(UUID.randomUUID().toString().replace("-", ""),
                category, content, keywords, "test", now, now, 0);
    }

    @Test
    void insertAndCount() {
        store.insert(item("preference", "用户喜欢喝咖啡", List.of("咖啡")));
        store.insert(item("fact", "用户常住上海", List.of("上海", "居住")));
        assertThat(store.count()).isEqualTo(2);
    }

    @Test
    void findByContent() {
        store.insert(item("preference", "用户喜欢简洁的回答", List.of("简洁")));
        MemoryItem found = store.findByContent("用户喜欢简洁的回答");
        assertThat(found).isNotNull();
        assertThat(found.getCategory()).isEqualTo("preference");
        // 内容不同应返回 null
        assertThat(store.findByContent("不存在的记忆")).isNull();
    }

    @Test
    void searchByChineseBigram() {
        store.insert(item("preference", "用户喜欢喝咖啡", List.of("咖啡", "喜好")));
        store.insert(item("fact", "用户常住上海", List.of("上海")));

        // 查询「咖啡」应命中偏好记忆
        List<MemoryItem> hits = store.search(List.of("咖啡"), 5);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).getContent()).contains("咖啡");

        // 查询「上海」应命中事实记忆
        List<MemoryItem> hits2 = store.search(List.of("上海"), 5);
        assertThat(hits2).hasSize(1);
        assertThat(hits2.get(0).getContent()).contains("上海");
    }

    @Test
    void searchWithLimitAndEmpty() {
        store.insert(item("fact", "记忆甲", List.of()));
        store.insert(item("fact", "记忆乙", List.of()));
        store.insert(item("fact", "记忆丙", List.of()));
        // 空词时回退为返回最近记录（按 limit）
        assertThat(store.search(List.of(), 2).size()).isLessThanOrEqualTo(2);
        // limit 生效
        assertThat(store.search(List.of("记忆"), 2).size()).isLessThanOrEqualTo(2);
    }

    @Test
    void updateRefreshesTimestampAndContent() throws InterruptedException {
        MemoryItem mem = item("preference", "用户喜欢喝咖啡", List.of("咖啡"));
        store.insert(mem);
        Thread.sleep(10);
        MemoryItem updated = new MemoryItem(mem.getId(), "fact", "用户喜欢喝热美式", List.of("咖啡", "美式"),
                "chat", mem.getCreatedAt(), System.currentTimeMillis(), 0);
        store.update(updated);

        MemoryItem reloaded = store.findByContent("用户喜欢喝热美式");
        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getCategory()).isEqualTo("fact");
        assertThat(reloaded.getUpdatedAt()).isGreaterThan(mem.getCreatedAt());
    }

    @Test
    void deleteAndClear() {
        MemoryItem a = item("fact", "记忆A", List.of());
        MemoryItem b = item("fact", "记忆B", List.of());
        store.insert(a);
        store.insert(b);
        assertThat(store.count()).isEqualTo(2);

        store.delete(a.getId());
        assertThat(store.count()).isEqualTo(1);
        assertThat(store.findByContent("记忆A")).isNull();

        store.clear();
        assertThat(store.count()).isZero();
    }

    @Test
    void recentReturnsLatestFirst() {
        long base = System.currentTimeMillis();
        for (int i = 0; i < 3; i++) {
            long now = base + i;
            store.insert(new MemoryItem("id-" + i, "fact", "记忆" + i, List.of(),
                    "test", now, now, 0));
        }
        List<MemoryItem> recent = store.recent(3);
        assertThat(recent).hasSize(3);
        assertThat(recent.get(0).getId()).isEqualTo("id-2");
    }
}
