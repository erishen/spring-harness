package com.example.springharness.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * SPA 前端路由 fallback：将不含点号的路径（即非静态资源请求）转发到 index.html，
 * 使 Vue Router 的 history 模式在生产部署（前端 dist 打包进后端 static）下刷新不 404。
 * /api/**、/actuator/**、/rag/** 等有 @RequestMapping 映射的路径优先匹配 controller，不受影响。
 */
@Configuration
public class SpaConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // 一级路径：/chat、/settings 等
        registry.addViewController("/{spring:[^.]*}")
                .setViewName("forward:/index.html");
        // 多级路径：/chat/history、/settings/profile 等
        registry.addViewController("/**/{spring:[^.]*}")
                .setViewName("forward:/index.html");
    }
}
