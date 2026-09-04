package com.example.springharness.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 长时任务定时清理调度器。
 *
 * <p>隐私保护 + 防止数据库无限增长：每天凌晨 3 点自动清理超过保留天数的已完成任务。
 * 正在运行的任务不会被删除。
 *
 * <p>配置项（.env）：
 * <ul>
 *   <li>TASK_RETENTION_DAYS - 已完成任务保留天数，默认 30 天</li>
 *   <li>TASK_CLEANUP_ENABLED - 是否启用定时清理，默认 true</li>
 * </ul>
 */
@Component
@EnableScheduling
public class TaskCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(TaskCleanupScheduler.class);

    private final LongTaskStore taskStore;

    /** 已完成任务保留天数，默认 30 天 */
    @Value("${TASK_RETENTION_DAYS:30}")
    private int retentionDays;

    /** 是否启用定时清理，默认 true */
    @Value("${TASK_CLEANUP_ENABLED:true}")
    private boolean cleanupEnabled;

    public TaskCleanupScheduler(LongTaskStore taskStore) {
        this.taskStore = taskStore;
    }

    /**
     * 每天凌晨 3 点执行过期任务清理。
     * cron 表达式：秒 分 时 日 月 周
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void scheduledCleanup() {
        if (!cleanupEnabled) {
            return;
        }
        try {
            log.info("[定时清理] 开始清理超过 {} 天的已完成长时任务...", retentionDays);
            int deleted = taskStore.cleanupExpired(retentionDays);
            log.info("[定时清理] 完成，共清理 {} 个任务", deleted);
        } catch (Exception e) {
            log.warn("[定时清理] 执行失败: {}", e.getMessage());
        }
    }

    /**
     * 应用启动时执行一次清理（避免上次运行期间积累的过期任务）。
     */
    @jakarta.annotation.PostConstruct
    public void cleanupOnStartup() {
        if (!cleanupEnabled) {
            return;
        }
        try {
            int deleted = taskStore.cleanupExpired(retentionDays);
            if (deleted > 0) {
                log.info("[启动清理] 已清理 {} 个超过 {} 天的已完成长时任务", deleted, retentionDays);
            }
        } catch (Exception e) {
            log.warn("[启动清理] 执行失败: {}", e.getMessage());
        }
    }
}
