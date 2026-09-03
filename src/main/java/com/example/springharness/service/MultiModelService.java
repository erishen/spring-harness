package com.example.springharness.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多模型管理服务：支持 DashScope（阿里云百炼）和 OpenAI 兼容接口（如 agnes）。
 *
 * 根据模型名称动态选择对应的 ChatModel：
 * - agnes-* 模型：使用 OpenAI 兼容接口（https://apihub.agnes-ai.com/v1）
 * - 其他模型：使用 DashScope（阿里云百炼）
 *
 * ChatModel 实例缓存，避免重复创建。
 */
@Service
public class MultiModelService {

    private final ChatModel dashScopeChatModel;
    private final Map<String, ChatModel> chatModelCache = new ConcurrentHashMap<>();

    @Value("${agnes.base-url:https://apihub.agnes-ai.com}")
    private String agnesBaseUrl;

    @Value("${agnes.api-key:}")
    private String agnesApiKey;

    @Value("${MAX_TOKENS:4096}")
    private int maxTokens;

    /** agnes 模型 max_tokens 下限：推理模型（如 agnes-2.0-flash）会把配额先消耗在 reasoning 上，
     *  过小会导致 content 为空/截断，故对 agnes 模型至少保证该配额 */
    @Value("${AGNES_MAX_TOKENS:8192}")
    private int agnesMaxTokens;

    /** Agnes 调用最小间隔（毫秒），控制调用频率避免 429 */
    @Value("${AGNES_RATE_INTERVAL_MS:6000}")
    private long agnesRateIntervalMs;

    /** Agnes 429 最大重试次数 */
    @Value("${AGNES_RATE_MAX_RETRIES:3}")
    private int agnesRateMaxRetries;

    /** Agnes 429 退避基数（毫秒），重试等待 = base * 2^(attempt-1) */
    @Value("${AGNES_RATE_BACKOFF_BASE_MS:10000}")
    private long agnesRateBackoffBaseMs;

    /** 共享限流器：所有 agnes 模型共用，统一控制整体调用频率 */
    private AgnesRateLimiter agnesRateLimiter;

    @jakarta.annotation.PostConstruct
    public void initRateLimiter() {
        this.agnesRateLimiter = new AgnesRateLimiter(
                agnesRateIntervalMs, agnesRateMaxRetries, agnesRateBackoffBaseMs);
    }

    /** agnes 模型前缀集合 */
    private static final Set<String> AGNES_MODEL_PREFIXES = Set.of("agnes-");

    public MultiModelService(ChatModel dashScopeChatModel) {
        this.dashScopeChatModel = dashScopeChatModel;
    }

    /**
     * 根据模型名称获取对应的 ChatModel。
     *
     * @param model 模型名称（如 glm-5.2、agnes-2.0-flash）
     * @return 对应的 ChatModel
     */
    public ChatModel getChatModel(String model) {
        if (model == null || model.isBlank()) {
            return dashScopeChatModel;
        }

        // agnes 模型使用 OpenAI 兼容接口
        if (isAgnesModel(model)) {
            return chatModelCache.computeIfAbsent(model, this::createAgnesChatModel);
        }

        // 其他模型使用 DashScope
        return dashScopeChatModel;
    }

    /**
     * 根据模型名称创建 ChatClient.Builder。
     */
    public ChatClient.Builder createChatClientBuilder(String model) {
        return ChatClient.builder(getChatModel(model));
    }

    /**
     * 判断是否为 agnes 模型。
     */
    public boolean isAgnesModel(String model) {
        if (model == null) return false;
        return AGNES_MODEL_PREFIXES.stream().anyMatch(model::startsWith);
    }

    /**
     * 创建 agnes 的 ChatModel（OpenAI 兼容接口）。
     */
    private ChatModel createAgnesChatModel(String model) {
        if (agnesApiKey == null || agnesApiKey.isBlank()) {
            throw new IllegalStateException(
                    "agnes API Key 未配置，请在 .env 中设置 AGNES_API_KEY");
        }

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(agnesBaseUrl)
                .apiKey(agnesApiKey)
                .restClientBuilder(RestClient.builder()
                        .requestInterceptor(agnesRateLimiter))
                .build();

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(model)
                .temperature(0.7)
                .maxTokens(Math.max(maxTokens, agnesMaxTokens))
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(options)
                .build();
    }
}
