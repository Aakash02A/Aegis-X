package com.aegisx.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring Web MVC configuration for CORS, formatters, and static assets.
 * 
 * CORS Configuration:
 * - Allowed origins configured via CORS_ALLOWED_ORIGINS environment variable
 * - Default (dev): http://localhost:3000,http://localhost:8080
 * - Production: Must explicitly set allowed frontend origins
 * 
 * Security:
 * - Never use wildcard "*" in production (allows any origin)
 * - Credentials are NOT allowed by default (secure cookie access)
 * - Only necessary HTTP methods are allowed
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${aegisx.cors.allowed-origins:http://localhost:3000,http://127.0.0.1:3000,http://localhost:8080,http://localhost:5173,http://127.0.0.1:5173}")
    private String allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins.split(","))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH")
                .allowedHeaders("Content-Type", "Authorization", "X-Correlation-ID")
                .maxAge(3600);
    }
}
