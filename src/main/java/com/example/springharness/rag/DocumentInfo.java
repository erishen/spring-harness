package com.example.springharness.rag;

import java.util.List;

/**
 * 文档元信息 DTO。
 */
public record DocumentInfo(
        String docId,
        String fileName,
        long size,
        int chunkCount,
        String uploadTime,
        List<String> chunkIds
) {}
