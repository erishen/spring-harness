package com.example.springharness.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Agnes 免费 API 限流 + 429 退避重试拦截器。
 *
 * 背景：Agnes 免费账户有调用频率限制，频繁请求会返回 429
 * （"reached the API rate limit for free users"）。
 * 本拦截器在请求发出前做客户端节流（保证两次调用最小间隔），
 * 并在收到 429 时按指数退避自动重试，避免任务直接失败。
 *
 * 注意：Agnes 的多个模型 ChatModel 必须共享同一个拦截器实例，
 * 才能对整体调用频率统一限流。
 */
public class AgnesRateLimiter implements ClientHttpRequestInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AgnesRateLimiter.class);

    /** 两次 Agnes 调用的最小间隔（毫秒） */
    private final long minIntervalMs;
    /** 429 时最大重试次数 */
    private final int maxRetries;
    /** 退避基数（毫秒），重试等待 = base * 2^(attempt-1) */
    private final long backoffBaseMs;

    /** 上次调用结束时间 */
    private final AtomicLong lastCallTime = new AtomicLong(0);

    public AgnesRateLimiter(long minIntervalMs, int maxRetries, long backoffBaseMs) {
        this.minIntervalMs = Math.max(minIntervalMs, 500); // 下限 0.5s 防止配置过小
        this.maxRetries = maxRetries;
        this.backoffBaseMs = Math.max(backoffBaseMs, 1000);
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        // 1. 客户端节流：保证两次调用间隔 >= minIntervalMs
        throttle();

        // 2. 429 退避重试
        IOException lastError = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            ClientHttpResponse response = null;
            try {
                response = execution.execute(request, body);
                // 429 限流：等待退避后重试
                if (response.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS && attempt < maxRetries) {
                    long wait = backoffBaseMs * (1L << attempt);
                    log.warn("[Agnes] 429 限流，第 {} 次重试，等待 {} 秒...",
                            attempt + 1, wait / 1000);
                    response.close();
                    sleep(wait);
                    continue;
                }
                return response;
            } catch (IOException e) {
                // 网络瞬时异常也可重试
                lastError = e;
                if (attempt < maxRetries) {
                    log.warn("[Agnes] 请求异常（{}），第 {} 次重试...", e.getMessage(), attempt + 1);
                    sleep(backoffBaseMs);
                    continue;
                }
                throw e;
            }
        }
        throw lastError;
    }

    /**
     * 客户端节流：若距上次调用不足 minIntervalMs，则等待补齐间隔。
     * 并发线程安全：多个线程竞争时依次排队通过。
     */
    private synchronized void throttle() {
        long now = System.currentTimeMillis();
        long last = lastCallTime.get();
        if (last != 0) {
            long elapsed = now - last;
            if (elapsed < minIntervalMs) {
                long wait = minIntervalMs - elapsed;
                log.debug("[Agnes] 调用过于频繁，节流等待 {} ms", wait);
                sleep(wait);
            }
        }
        lastCallTime.set(System.currentTimeMillis());
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
