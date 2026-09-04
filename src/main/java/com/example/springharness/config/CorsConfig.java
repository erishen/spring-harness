package com.example.springharness.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;
import java.util.List;

/**
 * CORS（跨域资源共享）配置。
 *
 * <p>安全配置：默认只允许前端开发服务器（localhost:5174）访问，
 * 可通过环境变量 CORS_ALLOWED_ORIGINS 配置允许的来源（逗号分隔）。
 *
 * <p>生产环境部署时，应将 CORS_ALLOWED_ORIGINS 设置为实际的前端域名，
 * 不要使用 "*"（允许所有来源），防止 CSRF 攻击。
 *
 * <p>配置项（.env）：
 * <ul>
 *   <li>CORS_ALLOWED_ORIGINS - 允许的来源，逗号分隔，默认 http://localhost:5174</li>
 *   <li>CORS_ALLOW_CREDENTIALS - 是否允许携带凭证，默认 true</li>
 * </ul>
 */
@Configuration
public class CorsConfig {

    private static final Logger log = LoggerFactory.getLogger(CorsConfig.class);

    /** 允许的来源，逗号分隔，默认前端开发服务器 */
    @Value("${CORS_ALLOWED_ORIGINS:http://localhost:5174}")
    private String allowedOrigins;

    /** 是否允许携带凭证（Cookie、Authorization 头），默认 true */
    @Value("${CORS_ALLOW_CREDENTIALS:true}")
    private boolean allowCredentials;

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();

        // 解析允许的来源
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        if (origins.contains("*")) {
            // 不允许 "*" + allowCredentials=true（浏览器会拒绝）
            log.warn("[CORS] 检测到 '*' 通配符，allowCredentials 自动设为 false。生产环境建议指定具体域名。");
            config.addAllowedOriginPattern("*");
            config.setAllowCredentials(false);
        } else {
            origins.forEach(config::addAllowedOrigin);
            config.setAllowCredentials(allowCredentials);
        }

        // 允许的 HTTP 方法
        config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));

        // 允许的请求头
        config.setAllowedHeaders(Arrays.asList(
                "Origin", "Content-Type", "Accept", "Authorization",
                "X-Requested-With", "Cache-Control", "Pragma"
        ));

        // 暴露的响应头（前端可读取）
        config.setExposedHeaders(Arrays.asList("Content-Disposition", "X-Total-Count"));

        // 预检请求缓存时间（秒）
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        log.info("[CORS] 已配置，允许来源: {}, 允许凭证: {}", origins, allowCredentials);

        return new CorsFilter(source);
    }
}
