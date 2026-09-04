package com.example.springharness.controller;

import com.example.springharness.config.ModelConfig;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 模型列表与管理接口。
 * - GET /models          : 已启用的模型（前端选择器用）
 * - GET /models/all      : 全部模型（含已禁用，管理面板用）
 * - GET /models/grouped  : 按厂商分组（全部模型）
 * - PUT /models/{id}/toggle : 切换模型启用状态
 * - POST /models/reset   : 重置所有模型启用状态为默认
 */
@RestController
@RequestMapping("/models")
public class ModelsController {

    /** 已启用的模型列表（前端模型选择器默认只显示这些） */
    @GetMapping
    public List<ModelConfig.ModelInfo> listModels() {
        return ModelConfig.getEnabledModels();
    }

    /** 全部模型（含已禁用，管理面板用） */
    @GetMapping("/all")
    public List<ModelConfig.ModelInfo> listAllModels() {
        return ModelConfig.getAllModels();
    }

    /** 按厂商分组返回全部模型 */
    @GetMapping("/grouped")
    public Map<String, List<ModelConfig.ModelInfo>> listModelsGrouped() {
        return ModelConfig.getByVendor();
    }

    /** 切换模型启用状态（运行时，内存级别，重启恢复默认） */
    @PutMapping("/{id}/toggle")
    public ResponseEntity<ModelConfig.ModelInfo> toggleModel(@PathVariable String id) {
        try {
            ModelConfig.ModelInfo updated = ModelConfig.toggleEnabled(id);
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /** 重置所有模型启用状态为默认 */
    @PostMapping("/reset")
    public ResponseEntity<String> resetModels() {
        ModelConfig.resetAll();
        return ResponseEntity.ok("已重置所有模型启用状态为默认");
    }
}
