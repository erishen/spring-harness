package com.example.springharness.memory;

import com.example.springharness.service.MultiModelService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 长期记忆提取器：用 LLM 从用户消息中抽取稳定事实 / 偏好 / 目标。
 *
 * <p>抽取成本控制：
 * <ul>
 *   <li>先做信号词预过滤，命中才调用 LLM（避免每条对话都触发一次调用）</li>
 *   <li>返回 JSON 数组 [{category, content, keywords}]，解析失败时安全降级为不抽取</li>
 * </ul>
 */
@Component
public class MemoryExtractor {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractor.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<List<Map<String, Object>>> MEMORY_LIST_TYPE = new TypeReference<>() {};

    /** 记忆信号词：命中任一条才触发 LLM 抽取 */
    private static final String[] SIGNAL_PATTERNS = {
            "我叫", "我是", "我住在", "我来自", "我在", "我目前", "我喜欢", "我不喜欢",
            "我爱", "我讨厌", "我想要", "我想", "我的目标", "我计划", "我打算", "我习惯",
            "我平时", "我家里", "我女儿", "我儿子", "我老婆", "我先生", "我的工作", "我在做",
            "我有个", "我的生日", "我比较", "我一般", "我一直", "我通常", "我最近在"
    };

    private static final Pattern JSON_BLOCK_PATTERN = Pattern.compile("\\[\\s*\\{(?:[^{}]|\\{(?:[^{}]|\\{[^{}]*\\})*\\})*\\}\\s*\\]");
    private static final Pattern CODE_FENCE_PATTERN = Pattern.compile("```(?:json)?\\s*(.*?)\\s*```", Pattern.DOTALL);

    private final MultiModelService multiModelService;

    /** 记忆抽取模型：留空则跟随主模型 DASHSCOPE_MODEL（若为 agnes 前缀则自动走 OpenAI 兼容路径） */
    @Value("${MEMORY_MODEL:}")
    private String memoryModel;

    /** 主模型（默认值来自 DASHSCOPE_MODEL） */
    @Value("${DASHSCOPE_MODEL:qwen-plus}")
    private String defaultModel;

    public MemoryExtractor(MultiModelService multiModelService) {
        this.multiModelService = multiModelService;
    }

    /**
     * 判断文本是否包含记忆信号（预过滤，避免无谓的 LLM 调用）。
     */
    public boolean hasSignal(String text) {
        if (text == null || text.isBlank()) return false;
        for (String signal : SIGNAL_PATTERNS) {
            if (text.contains(signal)) return true;
        }
        return false;
    }

    /**
     * 用 LLM 从文本中抽取记忆条目。
     *
     * @return 抽取到的记忆条目列表（可能为空）
     */
    public List<ExtractedMemory> extract(String text) {
        if (text == null || text.isBlank()) return List.of();

        String sys = """
                你是一个记忆提取器。从用户的消息中提取关于用户的长期记忆。
                长期记忆指：稳定的个人信息、偏好、习惯、长期目标，会在未来对话中反复有用。
                只提取三类：
                - preference：偏好 / 习惯（如「喜欢用中文回答」「习惯喝咖啡」）
                - fact：稳定事实（如「常住上海」「有一个女儿」）
                - goal：长期目标 / 计划（如「计划 45 岁退休」）
                忽略一次性任务请求（如「帮我写冒泡排序」「查询股票价格」）。
                若没有值得长期记住的内容，返回空数组 []。
                每条内容用简洁的一句话客观陈述，不要包含对话性的表述。
                返回 JSON 数组，格式：
                [{"category":"preference","content":"用户喜欢简洁的回答","keywords":["简洁","回答"]}]
                只返回 JSON，不要多余文字。
                """;
        String user = "用户消息：\n" + text;

        try {
            ChatClient.Builder builder = multiModelService.createChatClientBuilder(resolveModel());
            ChatResponse response = builder.build()
                    .prompt()
                    .system(sys)
                    .user(user)
                    .call()
                    .chatResponse();
            String content = response.getResult().getOutput().getText();
            if (content == null || content.isBlank()) return List.of();

            String json = extractJsonArray(content);
            if (json == null) {
                log.warn("记忆抽取未解析到 JSON，原文: {}", content);
                return List.of();
            }
            List<Map<String, Object>> raw = OBJECT_MAPPER.readValue(json, MEMORY_LIST_TYPE);
            List<ExtractedMemory> result = new ArrayList<>();
            for (Map<String, Object> item : raw) {
                String cat = item.get("category") == null ? "fact" : String.valueOf(item.get("category"));
                Object contentObj = item.get("content");
                String con = contentObj == null ? "" : String.valueOf(contentObj);
                if (con.isBlank()) continue;
                result.add(new ExtractedMemory(cat, con, splitKeywords(item.get("keywords"))));
            }
            return result;
        } catch (Exception e) {
            log.warn("记忆抽取调用失败: err={}", e.toString());
            return List.of();
        }
    }

    private String resolveModel() {
        if (memoryModel != null && !memoryModel.isBlank()) return memoryModel;
        return (defaultModel == null || defaultModel.isBlank()) ? null : defaultModel;
    }
    private List<String> splitKeywords(Object raw) {
        List<String> result = new ArrayList<>();
        if (raw == null) return result;
        // LLM 可能返回 JSON 数组 ["a","b"]
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o != null && !String.valueOf(o).isBlank()) {
                    result.add(String.valueOf(o));
                }
            }
            return result;
        }
        // 也可能是字符串 "a,b" / "a、b" / "a；b"
        String s = String.valueOf(raw);
        if (s.isBlank()) return result;
        String cleaned = s.replaceAll("[\\[\\]\"'\\s]", "");
        for (String part : cleaned.split("[,，、;；]")) {
            if (!part.isBlank()) result.add(part);
        }
        return result;
    }

    /** 从 LLM 输出中提取 JSON 数组（容忍 ```json 围栏与前后噪音） */
    private String extractJsonArray(String content) {
        Matcher fence = CODE_FENCE_PATTERN.matcher(content);
        if (fence.find()) {
            String inner = fence.group(1).trim();
            if (inner.startsWith("[") && inner.endsWith("]")) return inner;
        }
        Matcher matcher = JSON_BLOCK_PATTERN.matcher(content);
        if (matcher.find()) {
            return matcher.group();
        }
        // 兜底：截取第一个 [ 到最后一个 ]
        int start = content.indexOf('[');
        int end = content.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return content.substring(start, end + 1);
        }
        return null;
    }

    /** 抽取结果 DTO */
    public record ExtractedMemory(String category, String content, List<String> keywords) {
    }
}
