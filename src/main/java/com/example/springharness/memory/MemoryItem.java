package com.example.springharness.memory;

import java.util.List;

/**
 * 长期记忆条目。
 *
 * <p>从用户对话中抽取的稳定事实 / 偏好 / 目标，持久化到 SQLite，
 * 在后续对话 / Agent / PSE 中检索并注入 System prompt，实现跨会话记忆。
 *
 * <p>category 取值：
 * <ul>
 *   <li>preference：用户偏好 / 习惯（如「喜欢用中文回答」）</li>
 *   <li>fact：用户稳定事实（如「常住上海」）</li>
 *   <li>goal：长期目标 / 计划（如「计划 45 岁退休」）</li>
 * </ul>
 */
public class MemoryItem {

    private String id;
    private String category;
    private String content;
    private List<String> keywords;
    private String source;
    private long createdAt;
    private long updatedAt;
    private int accessCount;

    public MemoryItem() {
    }

    public MemoryItem(String id, String category, String content, List<String> keywords,
                      String source, long createdAt, long updatedAt, int accessCount) {
        this.id = id;
        this.category = category;
        this.content = content;
        this.keywords = keywords;
        this.source = source;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.accessCount = accessCount;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public List<String> getKeywords() { return keywords; }
    public void setKeywords(List<String> keywords) { this.keywords = keywords; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

    public int getAccessCount() { return accessCount; }
    public void setAccessCount(int accessCount) { this.accessCount = accessCount; }

    /** 分类中文标签（用于展示与注入） */
    public String categoryLabel() {
        return switch (category == null ? "fact" : category) {
            case "preference" -> "偏好";
            case "goal" -> "目标";
            default -> "事实";
        };
    }
}
