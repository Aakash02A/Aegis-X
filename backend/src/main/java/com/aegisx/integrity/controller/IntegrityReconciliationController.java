package com.aegisx.integrity.controller;

import com.aegisx.common.response.ApiResponse;
import com.aegisx.integrity.domain.ReconciliationStrategy;
import com.aegisx.integrity.domain.ReconciliationTrigger;
import com.aegisx.integrity.entity.ReconciliationFinding;
import com.aegisx.integrity.entity.ReconciliationRun;
import com.aegisx.integrity.reconciliation.ReconciliationMethodRegistry;
import com.aegisx.integrity.reconciliation.ReconciliationService;
import com.aegisx.integrity.repository.ReconciliationFindingRepository;
import com.aegisx.integrity.repository.ReconciliationRunRepository;
import com.aegisx.integrity.security.ActorContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Module E endpoints: run reconciliation, inspect runs and findings.
 *
 * <p>The strategy is a parameter so an operator can deliberately re-run a scenario through a
 * baseline for comparison. The default is always the proposed evidence-based method.</p>
 */
@RestController
@RequestMapping("/api/integrity/workspaces/{workspaceId}/reconciliation")
@RequiredArgsConstructor
public class IntegrityReconciliationController {

    private final ReconciliationService reconciliationService;
    private final ReconciliationMethodRegistry methodRegistry;
    private final ReconciliationRunRepository runRepository;
    private final ReconciliationFindingRepository findingRepository;
    private final ActorContext actorContext;

    public record RunRequest(ReconciliationStrategy strategy, Boolean refreshProviderState) { }

    @PostMapping("/runs")
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_OFFICER','SETTLEMENT_OPERATOR')")
    public ResponseEntity<ApiResponse<ReconciliationService.RunResult>> run(
            @PathVariable UUID workspaceId,
            @RequestBody(required = false) RunRequest request,
            Authentication authentication) {
        ActorContext.Actor actor = actorContext.resolve(authentication);
        ReconciliationStrategy strategy = request == null ? null : request.strategy();
        boolean refresh = request == null || request.refreshProviderState() == null || request.refreshProviderState();
        ReconciliationService.RunResult result = reconciliationService.run(workspaceId,
                ReconciliationTrigger.MANUAL, strategy, actor.userId(), refresh);
        return ResponseEntity.ok(ApiResponse.success(result, "Reconciliation run completed"));
    }

    @GetMapping("/methods")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<Map<String, String>>>> methods() {
        List<Map<String, String>> methods = methodRegistry.all().stream().map(method -> {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("strategy", method.strategy().name());
            entry.put("displayName", method.displayName());
            return entry;
        }).toList();
        return ResponseEntity.ok(ApiResponse.success(methods, "Available reconciliation methods"));
    }

    @GetMapping("/runs")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<ReconciliationRun>>> runs(@PathVariable UUID workspaceId) {
        return ResponseEntity.ok(ApiResponse.success(
                runRepository.findAllByWorkspaceIdOrderByStartedAtDesc(workspaceId), "Reconciliation runs retrieved"));
    }

    @GetMapping("/runs/{runId}/findings")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<ReconciliationFinding>>> findings(@PathVariable UUID workspaceId,
                                                                            @PathVariable UUID runId) {
        return ResponseEntity.ok(ApiResponse.success(findingRepository.findByRunIdOrderByCreatedAtAsc(runId),
                "Findings for run " + runId + " retrieved"));
    }

    @GetMapping("/findings")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<ReconciliationFinding>>> recentFindings(
            @PathVariable UUID workspaceId,
            @RequestParam(defaultValue = "50") int limit) {
        int pageSize = Math.min(Math.max(limit, 1), 200);
        return ResponseEntity.ok(ApiResponse.success(
                findingRepository.findByWorkspaceIdOrderByCreatedAtDesc(workspaceId, PageRequest.of(0, pageSize))
                        .toList(),
                "Recent reconciliation findings retrieved"));
    }
}
