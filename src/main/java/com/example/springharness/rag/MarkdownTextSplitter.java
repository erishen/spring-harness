package com.example.springharness.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;

import java.util.ArrayList;
import java.util.List;

/**
 * Markdown 结构化切分器：按标题层级（# / ## / ###）切分。
 *
 * 切分规则：
 * 1. 遇到标题行（# 开头）时，开始一个新块
 * 2. 每个块包含标题文本 + 标题下的所有内容，直到下一个同级或更高级标题
 * 3. 块内容保留标题作为前缀，便于检索时理解上下文
 * 4. 如果单个块超过 maxChunkTokens，用 TokenTextSplitter 二次切分
 */
public class MarkdownTextSplitter extends TextSplitter {

    private final int maxChunkTokens;
    private final TokenTextSplitter fallbackSplitter;

    public MarkdownTextSplitter() {
        this(800);
    }

    public MarkdownTextSplitter(int maxChunkTokens) {
        this.maxChunkTokens = maxChunkTokens;
        this.fallbackSplitter = new TokenTextSplitter();
    }

    @Override
    protected List<String> splitText(String text) {
        List<String> result = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return result;
        }

        String[] lines = text.split("\n", -1);
        StringBuilder currentSection = new StringBuilder();
        String currentTitle = "";

        for (String line : lines) {
            if (line.matches("^#{1,6}\\s+.+")) {
                if (currentSection.length() > 0) {
                    addSection(result, currentTitle, currentSection.toString());
                }
                currentTitle = line.replaceAll("^#+\\s+", "").trim();
                currentSection = new StringBuilder(line);
            } else {
                if (currentSection.length() > 0) {
                    currentSection.append("\n");
                }
                currentSection.append(line);
            }
        }

        if (currentSection.length() > 0) {
            addSection(result, currentTitle, currentSection.toString());
        }

        return result;
    }

    private void addSection(List<String> result, String title, String content) {
        int estimatedTokens = estimateTokens(content);

        if (estimatedTokens <= maxChunkTokens) {
            result.add(content);
        } else {
            List<String> subChunks = splitWithFallback(content);
            for (String sub : subChunks) {
                if (title != null && !title.isBlank() && !sub.startsWith("#")) {
                    result.add("# " + title + "\n\n" + sub);
                } else {
                    result.add(sub);
                }
            }
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
