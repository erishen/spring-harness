package com.example.springharness.controller;

import com.example.springharness.config.ModelConfig;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 模型列表接口：返回账户下所有可用的免费模型。
 */
@RestController
@RequestMapping("/models")
public class ModelsController {

    /**
     * 返回全部可用模型列表（扁平列表）。
     */
    @GetMapping
    public List<ModelConfig.ModelInfo> listModels() {
        return ModelConfig.AVAILABLE_MODELS;
    }

    /**
     * 按厂商分组返回模型列表。
     */
    @GetMapping("/grouped")
    public Map<String, List<ModelConfig.ModelInfo>> listModelsGrouped() {
        return ModelConfig.BY_VENDOR;
    }
}
