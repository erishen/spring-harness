package com.example.springharness.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;

import java.util.ArrayList;
import java.util.List;

/**
 * 段落切分器：按空行（连续两个以上换行）分段。
 *
 * 切分规则：
 * 1. 按空行（\n\n 或更多）将文本分成多个段落
 * 2. 每个段落作为一个独立块
 * 3. 如果单个段落超过 maxChunkTokens，用 TokenTextSplitter 二次切分
 * 4. 过短的段落（小于 minChunkChars）会合并到前一段
 *
 * 适用于 PDF、DOC 等按段落组织的文档。
 */
public class ParagraphTextSplitter extends TextSplitter {

    private final int maxChunkTokens;
    private final int minChunkChars;
    private final TokenTextSplitter fallbackSplitter;

    public ParagraphTextSplitter() {
        this(800, 100);
    }

    public ParagraphTextSplitter(int maxChunkTokens, int minChunkChars) {
        this.maxChunkTokens = maxChunkTokens;
        this.minChunkChars = minChunkChars;
        this.fallbackSplitter = new TokenTextSplitter();
    }

    @Override
    protected List<String> splitText(String text) {
        List<String> result = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return result;
        }

        String[] paragraphs = text.split("\\n\\s*\\n", -1);
        StringBuilder merged = new StringBuilder();

        for (String para : paragraphs) {
            String trimmed = para.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            if (trimmed.length() < minChunkChars && merged.length() > 0) {
                merged.append("\n\n").append(trimmed);
            } else {
                if (merged.length() > 0) {
                    addParagraph(result, merged.toString());
                }
                merged = new StringBuilder(trimmed);
            }
        }

        if (merged.length() > 0) {
            addParagraph(result, merged.toString());
        }

        return result;
    }

    private void addParagraph(List<String> result, String content) {
        int estimatedTokens = estimateTokens(content);

        if (estimatedTokens <= maxChunkTokens) {
            result.add(content);
        } else {
            List<String> subChunks = splitWithFallback(content);
            result.addAll(subChunks);
        }
    }

    /**
     * 用 TokenTextSplitter 二次切分（通过 apply 方法，因为 splitText 是 protected）。
     */
    private List<String> splitWithFallback(String content) {
        List<Document> docs = fallbackSplitter.apply(List.of(new Document(content)));
        return docs.stream().map(Document::getText).toList();
    }

    private int estimateTokens(String text) {
        int chineseChars = 0;
        int otherChars = 0;
        for (char c : text.toCharArray()) {
            if (c >= 0x4E00 && c <= 0x9FFF) {
                chineseChars++;
            } else {
                otherChars++;
            }
        }
        return (int) (chineseChars / 1.5 + otherChars / 4.0);
    }
}
