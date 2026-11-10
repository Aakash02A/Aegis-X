package com.aegisx.integrity.controller;

import com.aegisx.common.response.ApiResponse;
import com.aegisx.integrity.entity.IntegrityAuditEvent;
import com.aegisx.integrity.repository.IntegrityAuditEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Module H endpoint: investigation history.
 *
 * <p>Detail payloads were redacted at write time, so nothing sensitive can leak through this read
 * path either.</p>
 */
@RestController
@RequestMapping("/api/integrity/workspaces/{workspaceId}/audit")
@RequiredArgsConstructor
public class IntegrityAuditController {

    private final IntegrityAuditEventRepository auditRepository;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_OFFICER','SETTLEMENT_OPERATOR')")
    public ResponseEntity<ApiResponse<Page<IntegrityAuditEvent>>> history(
            @PathVariable UUID workspaceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        int pageSize = Math.min(Math.max(size, 1), 200);
        return ResponseEntity.ok(ApiResponse.success(
                auditRepository.findByWorkspaceIdOrderByOccurredAtDesc(workspaceId, PageRequest.of(page, pageSize)),
                "Audit history retrieved"));
    }
}
