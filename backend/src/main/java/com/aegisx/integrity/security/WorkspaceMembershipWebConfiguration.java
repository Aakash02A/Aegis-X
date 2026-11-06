package com.aegisx.integrity.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WorkspaceMembershipWebConfiguration implements WebMvcConfigurer {

    private final WorkspaceMembershipInterceptor membershipInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(membershipInterceptor)
                .addPathPatterns(
                        "/api/integrity/workspaces/{workspaceId}",
                        "/api/integrity/workspaces/{workspaceId}/**")
                .excludePathPatterns("/api/integrity/workspaces/default");
    }
}
