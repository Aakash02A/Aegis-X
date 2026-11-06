package com.aegisx.integrity.controller;

import com.aegisx.common.response.ApiResponse;
import com.aegisx.integrity.config.IntegrityProperties;
import com.aegisx.integrity.domain.ProviderMode;
import com.aegisx.integrity.entity.Workspace;
import com.aegisx.integrity.exception.IntegrityConflictException;
import com.aegisx.integrity.service.WorkspaceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Module A endpoints: workspace configuration and provider connectivity.
 *
 * <p>Configuration responses never contain a credential. The masked key hint is the only
 * identifying value returned, and secrets remain in the environment.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/integrity/workspaces")
@RequiredArgsConstructor
public class IntegrityWorkspaceController {

    private final WorkspaceService workspaceService;
    private final IntegrityProperties properties;

    public record CreateWorkspaceRequest(
            @NotBlank @Size(max = 120) String name,
            ProviderMode providerMode) { }

    public record UpdateThresholdRequest(
            @NotNull @Min(30) @Max(86400) Long stalenessThresholdSeconds) { }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_OFFICER','SETTLEMENT_OPERATOR')")
    public ResponseEntity<ApiResponse<List<Workspace>>> list() {
        if (workspaceService.list().isEmpty()) {
            workspaceService.ensureDefaultWorkspace();
        }
        return ResponseEntity.ok(ApiResponse.success(workspaceService.list(), "Workspaces retrieved"));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Workspace>> create(@Valid @RequestBody CreateWorkspaceRequest request) {
        Workspace workspace = workspaceService.create(request.name(), request.providerMode());
        return ResponseEntity.ok(ApiResponse.success(workspace, "Workspace created in TEST environment"));
    }

    @GetMapping("/default")
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_OFFICER','SETTLEMENT_OPERATOR')")
    public ResponseEntity<ApiResponse<Workspace>> defaultWorkspace() {
        return ResponseEntity.ok(ApiResponse.success(workspaceService.ensureDefaultWorkspace(),
                "Default workspace ready"));
    }

    @GetMapping("/{workspaceId}")
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_OFFICER','SETTLEMENT_OPERATOR')")
    public ResponseEntity<ApiResponse<Workspace>> get(@PathVariable UUID workspaceId) {
        return ResponseEntity.ok(ApiResponse.success(workspaceService.get(workspaceId), "Workspace retrieved"));
    }

    /**
     * Provider configuration status. Reports presence and reachability, never a credential value.
     */
    @GetMapping("/{workspaceId}/configuration")
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_OFFICER','SETTLEMENT_OPERATOR')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> configuration(@PathVariable UUID workspaceId) {
        Workspace workspace = workspaceService.refreshCapabilities(workspaceId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workspaceId", workspace.getId());
        body.put("name", workspace.getName());
        body.put("environment", workspace.getEnvironment().name());
        body.put("providerMode", workspace.getProviderMode().name());
        body.put("providerConfigured", workspace.isProviderConfigured());
        body.put("providerKeyIdMasked", workspace.getProviderKeyIdMasked());
        body.put("transferCapability", workspace.getTransferCapability().name());
        body.put("razorpayBaseUrl", properties.getRazorpay().getBaseUrl());
        body.put("testModeOnly", true);
        body.put("financialExecutionEnabled", properties.isAllowFinancialExecution());
        body.put("rawPayloadRetentionDays", properties.getPayloadRetentionDays());
        body.put("note", "Provider secrets are read from environment variables only and are never returned "
                + "or stored in the database.");
        return ResponseEntity.ok(ApiResponse.success(body, "Provider configuration status retrieved"));
    }

    @PostMapping("/{workspaceId}/connectivity-check")
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_OFFICER','SETTLEMENT_OPERATOR')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> connectivityCheck(@PathVariable UUID workspaceId) {
        Workspace workspace = workspaceService.refreshCapabilities(workspaceId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("providerMode", workspace.getProviderMode().name());
        body.put("providerConfigured", workspace.isProviderConfigured());
        body.put("transferCapability", workspace.getTransferCapability().name());
        body.put("checkedAt", java.time.Instant.now().toString());
        return ResponseEntity.ok(ApiResponse.success(body, "Connectivity check completed"));
    }

    @PatchMapping("/{workspaceId}/staleness-threshold")
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_OFFICER')")
    public ResponseEntity<ApiResponse<Workspace>> updateThreshold(@PathVariable UUID workspaceId,
                                                                 @Valid @RequestBody UpdateThresholdRequest request) {
        if (request.stalenessThresholdSeconds() == null) {
            throw new IntegrityConflictException("stalenessThresholdSeconds is required");
        }
        return ResponseEntity.ok(ApiResponse.success(
                workspaceService.updateStalenessThreshold(workspaceId, request.stalenessThresholdSeconds()),
                "Staleness threshold updated"));
    }
}
