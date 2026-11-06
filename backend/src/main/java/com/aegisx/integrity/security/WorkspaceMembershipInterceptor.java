package com.aegisx.integrity.security;

import com.aegisx.integrity.exception.IntegrityNotFoundException;
import com.aegisx.integrity.service.WorkspaceMembershipService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class WorkspaceMembershipInterceptor implements HandlerInterceptor {

    private final WorkspaceMembershipService membershipService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }
        Object pathVariables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (!(pathVariables instanceof Map<?, ?> variables)
                || !(variables.get("workspaceId") instanceof String workspaceIdValue)) {
            throw new IntegrityNotFoundException("Workspace not found");
        }

        UUID workspaceId;
        try {
            workspaceId = UUID.fromString(workspaceIdValue);
        } catch (IllegalArgumentException exception) {
            throw new IntegrityNotFoundException("Workspace not found");
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        membershipService.requireMembership(workspaceId, authentication);
        return true;
    }
}
