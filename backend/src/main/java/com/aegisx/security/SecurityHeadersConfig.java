package com.aegisx.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;

/**
 * Security Headers Configuration
 * Implements comprehensive security headers for demo deployment
 */
@Configuration
public class SecurityHeadersConfig {

    /**
     * Read through Spring rather than System.getenv so that the same property drives this filter
     * chain and the MVC CORS mapping (WebConfig). Reading the environment directly here meant a
     * value set in application-*.yml was silently ignored by Spring Security.
     */
    @Value("${aegisx.cors.allowed-origins:http://localhost:3000,http://127.0.0.1:3000,http://localhost:8080,http://127.0.0.1:8080,http://localhost:5173,http://127.0.0.1:5173}")
    private String allowedOrigins;

    @org.springframework.context.annotation.Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList());
        
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(Arrays.asList("*"));
        configuration.setAllowCredentials(true);
        configuration.setExposedHeaders(Arrays.asList("Authorization"));
        configuration.setMaxAge(3600L); // Cache preflight for 1 hour
        
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
