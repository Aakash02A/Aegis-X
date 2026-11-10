package com.aegisx.integrity.controller;

import com.aegisx.common.response.ApiResponse;
import com.aegisx.integrity.domain.ExceptionStatus;
import com.aegisx.integrity.entity.ExceptionCase;
import com.aegisx.integrity.entity.ReviewAction;
import com.aegisx.integrity.security.ActorContext;
import com.aegisx.integrity.service.ExceptionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
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

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Module F endpoints: the exception queue, the investigation view, and the persisted human workflow.
 *
 * <p>Open, resolve and dismiss require a compliance-officer or admin role. Merchant accounts can
 * read their own workspace's exceptions but cannot change their status.</p>
 */
@RestController
@RequestMapping("/api/integrity/workspaces/{workspaceId}/exceptions")
@RequiredArgsConstructor
public class IntegrityExceptionController {

    private final ExceptionService exceptionService;
    private final ActorContext actorContext;

    public record NoteRequest(@NotBlank @Size(max = 1000) String note) { }

    public record ResolutionRequest(@NotBlank @Size(max = 1000) String summary,
                                    @Size(max = 2000) String evidence) { }

    public record DismissRequest(@NotBlank @Size(max = 1000) String reason) { }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_OFFICER','SETTLEMENT_OPERATOR')")
    public ResponseEntity<ApiResponse<Page<ExceptionCase>>> queue(
            @PathVariable UUID workspaceId,
            @RequestParam(required = false) String[] status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        List<ExceptionStatus> statuses = status == null ? List.of()
                : Arrays.stream(status).map(ExceptionStatus::valueOf).toList();
        int pageSize = Math.min(Math.max(size, 1), 200);
        return ResponseEntity.ok(ApiResponse.success(
                exceptionService.queue(workspaceId, statuses, PageRequest.of(page, pageSize)),
                "Exception queue retrieved"));
    }

    @GetMapping("/all")
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_OFFICER','SETTLEMENT_OPERATOR')")
    public ResponseEntity<ApiResponse<List<ExceptionCase>>> all(@PathVariable UUID workspaceId) {
        return ResponseEntity.ok(ApiResponse.success(exceptionService.list(workspaceId),
                "All exception cases retrieved"));
    }

    @GetMapping("/{exceptionId}")
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_OFFICER','SETTLEMENT_OPERATOR')")
    public ResponseEntity<ApiResponse<ExceptionService.ExceptionDetail>> detail(@PathVariable UUID workspaceId,
                                                                               @PathVariable UUID exceptionId) {
        return ResponseEntity.ok(ApiResponse.success(exceptionService.detail(workspaceId, exceptionId),
                "Exception detail with supporting evidence retrieved"));
    }

    @PostMapping("/{exceptionId}/open")
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_OFFICER')")
    public ResponseEntity<ApiResponse<ExceptionCase>> open(@PathVariable UUID workspaceId,
                                                          @PathVariable UUID exceptionId,
                                                          @RequestBody(required = false) NoteRequest request,
                                                          Authentication authentication) {
        ActorContext.Actor actor = actorContext.resolve(authentication);
        ExceptionCase exceptionCase = exceptionService.openCase(workspaceId, exceptionId, actor,
                request == null ? null : request.note());
        return ResponseEntity.ok(ApiResponse.success(exceptionCase, "Exception opened for review"));
    }

    @PostMapping("/{exceptionId}/notes")
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_OFFICER')")
    public ResponseEntity<ApiResponse<ReviewAction>> addNote(@PathVariable UUID workspaceId,
                                                            @PathVariable UUID exceptionId,
                                                            @Valid @RequestBody NoteRequest request,
                                                            Authentication authentication) {
        ActorContext.Actor actor = actorContext.resolve(authentication);
        return ResponseEntity.ok(ApiResponse.success(
                exceptionService.addNote(workspaceId, exceptionId, actor, request.note()), "Note recorded"));
    }

    @PostMapping("/{exceptionId}/resolve")
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_OFFICER')")
    public ResponseEntity<ApiResponse<ExceptionCase>> resolve(@PathVariable UUID workspaceId,
                                                             @PathVariable UUID exceptionId,
                                                             @Valid @RequestBody ResolutionRequest request,
                                                             Authentication authentication) {
        ActorContext.Actor actor = actorContext.resolve(authentication);
        ExceptionCase exceptionCase = exceptionService.resolve(workspaceId, exceptionId, actor,
                request.summary(), request.evidence());
        return ResponseEntity.ok(ApiResponse.success(exceptionCase,
                "Resolution recorded. A later reconciliation run will verify whether the discrepancy persists."));
    }

    @PostMapping("/{exceptionId}/dismiss")
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_OFFICER')")
    public ResponseEntity<ApiResponse<ExceptionCase>> dismiss(@PathVariable UUID workspaceId,
                                                             @PathVariable UUID exceptionId,
                                                             @Valid @RequestBody DismissRequest request,
                                                             Authentication authentication) {
        ActorContext.Actor actor = actorContext.resolve(authentication);
        return ResponseEntity.ok(ApiResponse.success(
                exceptionService.dismiss(workspaceId, exceptionId, actor, request.reason()),
                "Exception dismissed with a recorded reason"));
    }
}
