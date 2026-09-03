package com.example.springharness.tool;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具调用记录器：用 ThreadLocal 收集单次请求中的工具调用过程。
 * 每个工具在 apply() 中调用 record() 记录调用信息。
 */
public final class ToolCallRecorder {

    private static final ThreadLocal<List<ToolCallRecord>> RECORDS =
            ThreadLocal.withInitial(ArrayList::new);

    private ToolCallRecorder() {}

    public static void record(String name, Object input, String output, long durationMs) {
        RECORDS.get().add(new ToolCallRecord(name, input, output, durationMs));
    }

    public static List<ToolCallRecord> getRecords() {
        return new ArrayList<>(RECORDS.get());
    }

    public static void clear() {
        RECORDS.get().clear();
    }

    public record ToolCallRecord(String name, Object input, String output, long durationMs) {}
}
