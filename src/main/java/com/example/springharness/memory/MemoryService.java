package com.example.springharness.memory;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 长期记忆门面服务（方案 A）。
 *
 * <p>职责：
 * <ul>
 *   <li>抽取：从用户消息中识别记忆信号 → LLM 抽取 → 去重后写入 SQLite（异步，不阻塞主流程）</li>
 *   <li>检索：按当前问题检索相关记忆 → 生成可注入 System prompt 的文本</li>
 *   <li>管理：列表 / 删除 / 清空 / 开关（供前端面板与 API 使用）</li>
 * </ul>
 *
 * <p>记忆格式：
 * <pre>
 * 【关于用户的长期记忆】
 * - [偏好] 用户喜欢简洁的回答
 * - [事实] 用户常住上海
 * 请在回答时合理参考这些记忆。
 * </pre>
 */
@Service
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    private static final Pattern CHINESE_SEQ_PATTERN = Pattern.compile("[\\u4e00-\\u9fa5]{2,}");
    private static final Pattern ENGLISH_WORD_PATTERN = Pattern.compile("[a-zA-Z][a-zA-Z0-9_-]{1,}");
    private static final Set<String> STOP_WORDS = Set.of(
            "这个", "那个", "什么", "怎么", "为什么", "可以", "需要", "一下", "一个",
            "你们", "我们", "他们", "自己", "现在", "今天", "昨天", "明天", "时候",
            "还有", "然后", "但是", "因为", "所以", "如果", "就是", "请问", "帮我"
    );

    private final MemoryStore store;
    private final MemoryExtractor extractor;

    private ExecutorService extractPool;

    @Value("${MEMORY_ENABLED:true}")
    private boolean enabled;

    @Value("${MEMORY_EXTRACT_ENABLED:true}")
    private boolean extractEnabled;

    @Value("${MEMORY_TOP_K:5}")
    private int topK;

    @Value("${MEMORY_MAX_ITEMS:200}")
    private int maxItems;

    public MemoryService(MemoryStore store, MemoryExtractor extractor) {
        this.store = store;
        this.extractor = extractor;
    }

    @PostConstruct
    public void init() {
        this.extractPool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "memory-extract");
            t.setDaemon(true);
            return t;
        });
        log.info("长期记忆服务就绪: enabled={}, extract={}, topK={}", enabled, extractEnabled, topK);
    }

    @PreDestroy
    public void shutdown() {
        if (extractPool != null) {
            extractPool.shutdownNow();
        }
    }

    /**
     * 检索与 query 相关的记忆并生成注入文本。
     * 无相关记忆或记忆关闭时返回空串。
     */
    public String buildMemoryContext(String query) {
        if (!enabled) return "";
        List<MemoryItem> items = retrieve(query, topK);
        if (items.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("\n【关于用户的长期记忆】\n");
        for (MemoryItem item : items) {
            sb.append("- [").append(item.categoryLabel()).append("] ").append(item.getContent()).append("\n");
        }
        sb.append("请在回答时合理参考以上记忆，无需逐条复述。\n");
        return sb.toString();
    }

    /**
     * 按 query 检索相关记忆（按命中数 + 更新时间排序），并累加访问次数。
     */
    public List<MemoryItem> retrieve(String query, int limit) {
        if (!enabled) return List.of();
        List<String> terms = tokenize(query);
        List<MemoryItem> items = store.search(terms, limit);
        // 异步累加访问次数（不阻塞检索）
        if (!items.isEmpty()) {
            for (MemoryItem item : items) {
                bumpAccess(item.getId());
            }
        }
        return items;
    }

    /**
     * 异步抽取并存储记忆。预过滤信号词，命中才调用 LLM。
     * 不阻塞主流程，失败静默降级。
     */
    public void extractAndStore(String text, String source) {
        if (!enabled || !extractEnabled || text == null || text.isBlank()) {
            log.debug("记忆抽取跳过: enabled={}, extractEnabled={}, text空={}", enabled, extractEnabled, text == null || text.isBlank());
            return;
        }
        if (!extractor.hasSignal(text)) {
            log.debug("记忆抽取跳过(无信号词): source={}", source);
            return;
        }

        extractPool.submit(() -> {
            try {
                List<MemoryExtractor.ExtractedMemory> extracted = extractor.extract(text);
                for (MemoryExtractor.ExtractedMemory mem : extracted) {
                    upsert(mem, source);
                }
                if (!extracted.isEmpty()) {
                    log.info("记忆抽取完成: source={}, 新增/更新 {} 条", source, extracted.size());
                }
            } catch (Exception e) {
                log.warn("记忆抽取任务失败: source={}, err={}", source, e.getMessage());
            }
        });
    }

    /** 插入或更新（按内容精确去重，更新时刷新时间戳） */
    private void upsert(MemoryExtractor.ExtractedMemory mem, String source) {
        long now = System.currentTimeMillis();
        MemoryItem existing = store.findByContent(mem.content());
        if (existing != null) {
            existing.setCategory(mem.category());
            existing.setKeywords(mem.keywords());
            existing.setSource(source);
            existing.setUpdatedAt(now);
            store.update(existing);
        } else {
            // 容量上限：超出时删除最旧的
            if (store.count() >= maxItems) {
                List<MemoryItem> oldest = store.recent(10000);
                if (!oldest.isEmpty()) {
                    store.delete(oldest.get(oldest.size() - 1).getId());
                }
            }
            MemoryItem item = new MemoryItem(
                    UUID.randomUUID().toString().replace("-", ""),
                    mem.category(), mem.content(), mem.keywords(), source, now, now, 0);
            store.insert(item);
        }
    }

    private void bumpAccess(String id) {
        // 简化为更新 access_count = access_count + 1
        store.incrementAccess(id);
    }

    /** 分词：中文按 2 字 bigram（去重、过滤停用词），英文按单词 */
    List<String> tokenize(String query) {
        List<String> terms = new ArrayList<>();
        if (query == null || query.isBlank()) return terms;

        Matcher en = ENGLISH_WORD_PATTERN.matcher(query);
        while (en.find()) {
            String w = en.group().toLowerCase();
            if (!terms.contains(w)) terms.add(w);
        }
        Matcher cn = CHINESE_SEQ_PATTERN.matcher(query);
        while (cn.find()) {
            String seq = cn.group();
            if (seq.length() == 2) {
                addTerm(terms, seq);
            } else {
                for (int i = 0; i + 2 <= seq.length(); i++) {
                    addTerm(terms, seq.substring(i, i + 2));
                }
            }
        }
        return terms;
    }

    private void addTerm(List<String> terms, String term) {
        if (STOP_WORDS.contains(term)) return;
        if (!terms.contains(term)) terms.add(term);
    }

    // ---- 管理接口（供 Controller / 前端面板使用） ----

    public boolean isEnabled() { return enabled; }

    public void setEnabled(boolean on) {
        this.enabled = on;
        log.info("长期记忆开关: {}", on ? "开启" : "关闭");
    }

    public List<MemoryItem> listAll() { return store.listAll(); }

    public void delete(String id) { store.delete(id); }

    public void clear() { store.clear(); }

    public int count() { return store.count(); }
}
