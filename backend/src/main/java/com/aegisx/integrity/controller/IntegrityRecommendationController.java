package com.aegisx.integrity.controller;

import com.aegisx.common.response.ApiResponse;
import com.aegisx.integrity.entity.RecoveryRecommendation;
import com.aegisx.integrity.security.ActorContext;
import com.aegisx.integrity.service.RecoveryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Module G endpoints.
 *
 * <p>Approving a financial recommendation is refused by default: the safety property is enforced in
 * the service and surfaces here as a 409 with a clear explanation, not as a silent success.</p>
 */
@RestController
@RequestMapping("/api/integrity/workspaces/{workspaceId}/recommendations")
@RequiredArgsConstructor
public class IntegrityRecommendationController {

    private final RecoveryService recoveryService;
    private final ActorContext actorContext;

    public record DecisionRequest(@Size(max = 1000) String rationale) { }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_OFFICER','SETTLEMENT_OPERATOR')")
    public ResponseEntity<ApiResponse<List<RecoveryRecommendation>>> list(@PathVariable UUID workspaceId) {
        return ResponseEntity.ok(ApiResponse.success(recoveryService.list(workspaceId),
                "Recovery recommendations retrieved"));
    }

    @GetMapping("/by-exception/{exceptionId}")
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_OFFICER','SETTLEMENT_OPERATOR')")
    public ResponseEntity<ApiResponse<List<RecoveryRecommendation>>> byException(@PathVariable UUID workspaceId,
                                                                               @PathVariable UUID exceptionId) {
        return ResponseEntity.ok(ApiResponse.success(recoveryService.forException(workspaceId, exceptionId),
                "Recommendations for exception retrieved"));
    }

    @PostMapping("/{recommendationId}/approve")
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_OFFICER')")
    public ResponseEntity<ApiResponse<RecoveryRecommendation>> approve(@PathVariable UUID workspaceId,
                                                                      @PathVariable UUID recommendationId,
                                                                      @RequestBody(required = false) DecisionRequest request,
                                                                      Authentication authentication) {
        ActorContext.Actor actor = actorContext.resolve(authentication);
        RecoveryRecommendation recommendation = recoveryService.approve(workspaceId, recommendationId, actor,
                request == null ? null : request.rationale());
        return ResponseEntity.ok(ApiResponse.success(recommendation, "Recommendation approved"));
    }

    @PostMapping("/{recommendationId}/reject")
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_OFFICER')")
    public ResponseEntity<ApiResponse<RecoveryRecommendation>> reject(@PathVariable UUID workspaceId,
                                                                     @PathVariable UUID recommendationId,
                                                                     @RequestBody(required = false) DecisionRequest request,
                                                                     Authentication authentication) {
        ActorContext.Actor actor = actorContext.resolve(authentication);
        RecoveryRecommendation recommendation = recoveryService.reject(workspaceId, recommendationId, actor,
                request == null ? null : request.rationale());
        return ResponseEntity.ok(ApiResponse.success(recommendation, "Recommendation rejected"));
    }

    @PostMapping("/{recommendationId}/execute")
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_OFFICER','SETTLEMENT_OPERATOR')")
    public ResponseEntity<ApiResponse<RecoveryService.ExecutionOutcome>> execute(@PathVariable UUID workspaceId,
                                                                                @PathVariable UUID recommendationId,
                                                                                Authentication authentication) {
        ActorContext.Actor actor = actorContext.resolve(authentication);
        RecoveryService.ExecutionOutcome outcome = recoveryService.executeReadOnly(workspaceId, recommendationId, actor);
        return ResponseEntity.ok(ApiResponse.success(outcome, "Read-only recommendation executed"));
    }
}
