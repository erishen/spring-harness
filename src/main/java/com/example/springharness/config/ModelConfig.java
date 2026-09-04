package com.example.springharness.config;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 可用模型配置：用户账户下的免费模型，按厂商分组。
 * 支持运行时启用/禁用（内存级别，重启恢复默认）。
 */
public final class ModelConfig {

    private ModelConfig() {}

    /**
     * 模型元信息：模型 ID、厂商、定位描述、是否需要原生端点、是否启用。
     */
    public record ModelInfo(
            String id,
            String vendor,
            String description,
            boolean nativeEndpoint,
            boolean enabled
    ) {
        /** 复制并修改 enabled 状态 */
        public ModelInfo withEnabled(boolean newEnabled) {
            return new ModelInfo(id, vendor, description, nativeEndpoint, newEnabled);
        }
    }

    /** 全部可用模型列表（按推荐优先级排序） */
    private static final List<ModelInfo> DEFAULT_MODELS = List.of(
            // ===== 经测试可正常调用的模型 =====
            new ModelInfo("glm-5.2", "智谱清言", "旗舰，中文优化，通用对话", false, true),
            new ModelInfo("qwen-plus", "通义千问", "均衡型，通用对话推荐", false, true),
            new ModelInfo("qwen-turbo", "通义千问", "轻量快速，成本低", false, true),
            new ModelInfo("qwen-max", "通义千问", "旗舰能力，复杂推理", false, true),
            new ModelInfo("qwen3-coder-plus", "通义千问", "代码生成专用", false, true),
            new ModelInfo("deepseek-v4-pro-0813", "深度求索", "旗舰，代码/数学推理强", false, true),
            new ModelInfo("deepseek-v4-flash-0731", "深度求索", "轻量，代码生成快速", false, true),
            // ===== agnes 模型（OpenAI 兼容接口，免费） =====
            new ModelInfo("agnes-2.0-flash", "Agnes", "免费，轻量快速，OpenAI兼容接口", false, true),
            // ===== 账户免费但暂需原生API端点的模型（Spring AI Alibaba 版本更新后可支持） =====
            new ModelInfo("qwen3.8-max", "通义千问", "旗舰最新版", true, false),
            new ModelInfo("qwen3.8-max-0902", "通义千问", "旗舰 0902 版", true, false),
            new ModelInfo("qwen3.8-27b", "通义千问", "中规模", true, false),
            new ModelInfo("qwen3.8-2.4t-a95b", "通义千问", "小模型", true, false),
            new ModelInfo("qwen3.8-flash", "通义千问", "轻量快速", true, false),
            new ModelInfo("qwen3.7-flash", "通义千问", "轻量快速稳定版", true, false),
            new ModelInfo("qwen3.7-flash-2026-07-15", "通义千问", "轻量快速 0715 版", true, false),
            new ModelInfo("qwen3.7-max-2026-06-08", "通义千问", "旗舰旧版", true, false),
            new ModelInfo("qwen3.5-ocr", "通义千问", "OCR 专用", true, false),
            new ModelInfo("kimi-k3", "月之暗面", "长文本", true, false),
            new ModelInfo("kimi-k2.7-code", "月之暗面", "代码专用", true, false)
    );

    /** 运行时启用状态覆盖（key=model id, value=enabled），null 表示用默认值 */
    private static final Map<String, Boolean> ENABLED_OVERRIDES = new ConcurrentHashMap<>();

    /** 获取全部模型（含运行时启用状态覆盖） */
    public static List<ModelInfo> getAllModels() {
        return DEFAULT_MODELS.stream()
                .map(m -> ENABLED_OVERRIDES.containsKey(m.id())
                        ? m.withEnabled(ENABLED_OVERRIDES.get(m.id()))
                        : m)
                .toList();
    }

    /** 获取已启用的模型 */
    public static List<ModelInfo> getEnabledModels() {
        return getAllModels().stream().filter(ModelInfo::enabled).toList();
    }

    /** 按厂商分组（全部模型） */
    public static Map<String, List<ModelInfo>> getByVendor() {
        return getAllModels().stream()
                .collect(java.util.stream.Collectors.groupingBy(ModelInfo::vendor));
    }

    /** 切换模型启用状态（运行时，内存级别） */
    public static ModelInfo toggleEnabled(String modelId) {
        ModelInfo model = DEFAULT_MODELS.stream()
                .filter(m -> m.id().equals(modelId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("模型不存在: " + modelId));
        boolean current = ENABLED_OVERRIDES.getOrDefault(modelId, model.enabled());
        boolean next = !current;
        ENABLED_OVERRIDES.put(modelId, next);
        return model.withEnabled(next);
    }

    /** 重置所有模型启用状态为默认 */
    public static void resetAll() {
        ENABLED_OVERRIDES.clear();
    }
}
