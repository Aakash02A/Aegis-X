package com.aegisx.integrity.controller;

import com.aegisx.common.response.ApiResponse;
import com.aegisx.integrity.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Module I endpoint: operations dashboard metrics, all computed from persisted rows. */
@RestController
@RequestMapping("/api/integrity/workspaces/{workspaceId}/dashboard")
@RequiredArgsConstructor
public class IntegrityDashboardController {

    private final DashboardService dashboardService;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<DashboardService.DashboardSummary>> summary(@PathVariable UUID workspaceId) {
        return ResponseEntity.ok(ApiResponse.success(dashboardService.summary(workspaceId),
                "Dashboard summary computed from persisted data"));
    }
}
