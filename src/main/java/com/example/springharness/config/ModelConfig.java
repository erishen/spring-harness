package com.example.springharness.config;

import java.util.List;
import java.util.Map;

/**
 * 可用模型配置：用户账户下的 14 个免费模型，按厂商分组。
 */
public final class ModelConfig {

    private ModelConfig() {}

    /**
     * 模型元信息：模型 ID、厂商、定位描述。
     */
    public record ModelInfo(String id, String vendor, String description) {}

    /** 全部可用模型列表（按推荐优先级排序） */
    public static final List<ModelInfo> AVAILABLE_MODELS = List.of(
            // ===== 经测试可正常调用的模型 =====
            new ModelInfo("glm-5.2", "智谱清言", "旗舰，中文优化，通用对话（当前默认）"),
            new ModelInfo("qwen-plus", "通义千问", "均衡型，通用对话推荐"),
            new ModelInfo("qwen-turbo", "通义千问", "轻量快速，成本低"),
            new ModelInfo("qwen-max", "通义千问", "旗舰能力，复杂推理"),
            new ModelInfo("qwen3-coder-plus", "通义千问", "代码生成专用"),
            new ModelInfo("deepseek-v4-pro-0813", "深度求索", "旗舰，代码/数学推理强"),
            new ModelInfo("deepseek-v4-flash-0731", "深度求索", "轻量，代码生成快速"),
            // ===== agnes 模型（OpenAI 兼容接口，免费） =====
            new ModelInfo("agnes-2.0-flash", "Agnes", "免费，轻量快速，OpenAI兼容接口"),
            // ===== 账户免费但暂有 URL 兼容问题的模型（Spring AI Alibaba 版本更新后可支持） =====
            new ModelInfo("qwen3.8-max", "通义千问", "旗舰最新版（暂需原生API端点）"),
            new ModelInfo("qwen3.8-max-0902", "通义千问", "旗舰 0902 版（暂需原生API端点）"),
            new ModelInfo("qwen3.8-27b", "通义千问", "中规模（暂需原生API端点）"),
            new ModelInfo("qwen3.8-2.4t-a95b", "通义千问", "小模型（暂需原生API端点）"),
            new ModelInfo("qwen3.8-flash", "通义千问", "轻量快速（暂需原生API端点）"),
            new ModelInfo("qwen3.7-flash", "通义千问", "轻量快速稳定版（暂需原生API端点）"),
            new ModelInfo("qwen3.7-flash-2026-07-15", "通义千问", "轻量快速 0715 版（暂需原生API端点）"),
            new ModelInfo("qwen3.7-max-2026-06-08", "通义千问", "旗舰旧版（暂需原生API端点）"),
            new ModelInfo("qwen3.5-ocr", "通义千问", "OCR 专用（暂需原生API端点）"),
            new ModelInfo("kimi-k3", "月之暗面", "长文本（暂需原生API端点）"),
            new ModelInfo("kimi-k2.7-code", "月之暗面", "代码专用（暂需原生API端点）")
    );

    /** 按厂商分组 */
    public static final Map<String, List<ModelInfo>> BY_VENDOR = Map.of(
            "通义千问", AVAILABLE_MODELS.stream().filter(m -> m.vendor().equals("通义千问")).toList(),
            "智谱清言", AVAILABLE_MODELS.stream().filter(m -> m.vendor().equals("智谱清言")).toList(),
            "月之暗面", AVAILABLE_MODELS.stream().filter(m -> m.vendor().equals("月之暗面")).toList(),
            "深度求索", AVAILABLE_MODELS.stream().filter(m -> m.vendor().equals("深度求索")).toList(),
            "Agnes", AVAILABLE_MODELS.stream().filter(m -> m.vendor().equals("Agnes")).toList()
    );
}
