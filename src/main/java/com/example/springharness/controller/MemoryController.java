package com.example.springharness.controller;

import com.example.springharness.memory.MemoryItem;
import com.example.springharness.memory.MemoryService;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 长期记忆管理接口。
 *
 * <ul>
 *   <li>GET  /api/memory            —— 记忆列表 + 开关状态</li>
 *   <li>POST /api/memory/toggle     —— 开启/关闭记忆</li>
 *   <li>POST /api/memory/clear      —— 清空全部记忆</li>
 *   <li>DELETE /api/memory/{id}     —— 删除单条记忆</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/memory")
public class MemoryController {

    private final MemoryService memoryService;

    public MemoryController(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    /** 记忆列表与状态 */
    @GetMapping
    public Map<String, Object> list() {
        List<MemoryItem> items = memoryService.listAll();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", memoryService.isEnabled());
        result.put("count", items.size());
        result.put("items", items);
        return result;
    }

    /** 开关记忆 */
    @PostMapping("/toggle")
    public Map<String, Object> toggle(@RequestBody(required = false) Map<String, Object> body) {
        boolean on = body != null && body.get("enabled") instanceof Boolean b && b;
        memoryService.setEnabled(on);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", memoryService.isEnabled());
        return result;
    }

    /** 清空记忆 */
    @PostMapping("/clear")
    public Map<String, Object> clear() {
        memoryService.clear();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", 0);
        return result;
    }

    /** 删除单条记忆 */
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        memoryService.delete(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deleted", true);
        return result;
    }
}
