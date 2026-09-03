package com.example.springharness.controller;

import com.example.springharness.task.LongTask;
import com.example.springharness.task.TaskManager;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 长时任务接口：提交 / 查询 / 取消 / 删除异步 Agent 任务。
 *
 * <ul>
 *   <li>POST /api/tasks?type=pse&message=xxx&model=xxx —— 提交任务（立即返回任务 ID）</li>
 *   <li>GET /api/tasks —— 任务列表</li>
 *   <li>GET /api/tasks/{id} —— 单个任务详情（含步骤/结果）</li>
 *   <li>DELETE /api/tasks/{id}?cancel=true —— 取消运行中的任务；cancel=false 删除终态任务</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/tasks")
public class LongTaskController {

    private final TaskManager taskManager;

    public LongTaskController(TaskManager taskManager) {
        this.taskManager = taskManager;
    }

    /**
     * 提交长时任务。
     *
     * @param type    chat / agent / pse
     * @param message 用户消息 / 任务描述
     * @param model   模型名（可空）
     */
    @PostMapping
    public Map<String, Object> submit(
            @RequestParam String type,
            @RequestParam String message,
            @RequestParam(required = false) String model) {
        if (message == null || message.isBlank()) {
            return Map.of("error", "message 不能为空");
        }
        String id = taskManager.submit(type, message, model);
        return Map.of("id", id, "status", LongTask.STATUS_PENDING);
    }

    /** 任务列表（按创建时间倒序） */
    @GetMapping
    public List<Map<String, Object>> list() {
        return taskManager.list().stream()
                .map(LongTask::toMap)
                .toList();
    }

    /** 单个任务详情 */
    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        LongTask task = taskManager.get(id);
        if (task == null) {
            return Map.of("error", "任务不存在", "id", id);
        }
        return task.toMap();
    }

    /**
     * 取消或删除任务。
     *
     * @param cancel true=取消运行中任务；false=删除终态任务
     */
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id,
                                      @RequestParam(defaultValue = "false") boolean cancel) {
        if (cancel) {
            boolean ok = taskManager.cancel(id);
            return ok
                    ? Map.of("id", id, "status", "cancelled")
                    : Map.of("error", "任务不存在或已结束");
        }
        boolean ok = taskManager.delete(id);
        return ok
                ? Map.of("id", id, "deleted", true)
                : Map.of("error", "任务不存在");
    }

    /**
     * 续跑（重跑）：用相同参数重新提交任务，返回新任务 ID。
     * 原任务保留（completed/cancelled）供查看对比。
     */
    @PostMapping("/{id}/restart")
    public Map<String, Object> restart(@PathVariable String id) {
        String newId = taskManager.restart(id);
        if (newId == null) {
            return Map.of("error", "原任务不存在");
        }
        return Map.of("id", newId, "originalId", id, "status", LongTask.STATUS_PENDING);
    }
}
