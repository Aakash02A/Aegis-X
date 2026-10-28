package com.aegisx.integrity.reconciliation;

import com.aegisx.integrity.audit.IntegrityAuditService;
import com.aegisx.integrity.domain.ReconciliationStrategy;
import com.aegisx.integrity.domain.ReconciliationTrigger;
import com.aegisx.integrity.entity.ExceptionCase;
import com.aegisx.integrity.entity.ReconciliationFinding;
import com.aegisx.integrity.entity.ReconciliationRun;
import com.aegisx.integrity.entity.Workspace;
import com.aegisx.integrity.reconciliation.model.EvidencePack;
import com.aegisx.integrity.reconciliation.model.FindingCandidate;
import com.aegisx.integrity.reconciliation.model.ReconciliationMethod;
import com.aegisx.integrity.repository.ReconciliationFindingRepository;
import com.aegisx.integrity.repository.ReconciliationRunRepository;
import com.aegisx.integrity.service.ProviderSyncService;
import com.aegisx.integrity.service.WorkspaceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Module E/F: the reconciliation engine.
 *
 * <p>One run performs: optional provider refresh → build the evidence pack → evaluate with the
 * selected strategy → persist findings → upsert exception cases → attach recovery
 * recommendations → auto-reopen cases whose discrepancy is still present.</p>
 *
 * <p>Auto-reopening is the verification step required by the workflow: an operator's resolution is
 * provisional until a later run stops detecting the problem.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReconciliationService {

    private final WorkspaceService workspaceService;
    private final ProviderSyncService providerSyncService;
    private final EvidencePackBuilder evidencePackBuilder;
    private final ReconciliationMethodRegistry methodRegistry;
    private final ReconciliationRunRepository runRepository;
    private final ReconciliationFindingRepository findingRepository;
    private final ExceptionUpserter exceptionUpserter;
    private final IntegrityAuditService auditService;
    private final ObjectMapper objectMapper;

    public record RunResult(UUID runId,
                            ReconciliationStrategy strategy,
                            int findingsCount,
                            int newExceptions,
                            int reopenedExceptions,
                            int refreshedExceptions,
                            boolean providerReachable,
                            List<String> notes) { }

    @Transactional
    public RunResult run(UUID workspaceId,
                         ReconciliationTrigger trigger,
                         ReconciliationStrategy strategy,
                         UUID triggeredBy,
                         boolean syncProvider) {
        Workspace workspace = workspaceService.get(workspaceId);
        ReconciliationStrategy effectiveStrategy = strategy == null
                ? ReconciliationStrategy.PROPOSED_EVIDENCE_BASED
                : strategy;

        boolean providerReachable = true;
        List<String> notes = new ArrayList<>();
        if (syncProvider) {
            ProviderSyncService.SyncSummary summary = providerSyncService.sync(workspace);
            providerReachable = summary.providerReachable();
            notes.addAll(summary.notes());
        } else {
            notes.add("Provider refresh skipped for this run; stored observations were used");
        }

        EvidencePack pack = evidencePackBuilder.build(workspace, providerReachable);
        ReconciliationMethod method = methodRegistry.forStrategy(effectiveStrategy);
        List<FindingCandidate> candidates = method.evaluate(pack);

        ReconciliationRun run = runRepository.save(ReconciliationRun.builder()
                .workspaceId(workspaceId)
                .trigger(trigger == null ? ReconciliationTrigger.MANUAL : trigger)
                .strategy(effectiveStrategy)
                .startedAt(Instant.now())
                .ordersChecked(pack.orders().size())
                .paymentsChecked(pack.payments().size())
                .refundsChecked(pack.refunds().size())
                .transfersChecked(pack.transfers().size())
                .findingsCount(candidates.size())
                .providerReachable(providerReachable)
                .notes(notes.isEmpty() ? null : truncate(String.join("; ", notes), 990))
                .triggeredBy(triggeredBy)
                .build());

        int newExceptions = 0;
        int reopened = 0;
        int refreshed = 0;

        for (FindingCandidate candidate : candidates) {
            ExceptionUpserter.Upsert upsert = exceptionUpserter.upsert(workspaceId, run, candidate, triggeredBy);
            ExceptionCase exception = upsert.exception();
            if (upsert.created()) {
                newExceptions++;
            } else if (upsert.reopened()) {
                reopened++;
            } else {
                refreshed++;
            }

            findingRepository.save(ReconciliationFinding.builder()
                    .runId(run.getId())
                    .workspaceId(workspaceId)
                    .orderId(candidate.orderId())
                    .paymentId(candidate.paymentId())
                    .refundId(candidate.refundId())
                    .transferId(candidate.transferId())
                    .findingType(candidate.type())
                    .severity(candidate.severity())
                    .certainty(candidate.certainty())
                    .summary(truncate(candidate.summary(), 490))
                    .evidenceJson(toJson(candidate.evidence()))
                    .missingEvidenceJson(toJson(candidate.missingEvidence()))
                    .exceptionId(exception.getId())
                    .build());

            exceptionUpserter.attachRecommendations(workspaceId, exception, candidate, pack);
        }

        run.setCompletedAt(Instant.now());
        run.setNewExceptions(newExceptions);
        run.setReopenedExceptions(reopened);
        run.setRefreshedExceptions(refreshed);
        runRepository.save(run);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("strategy", effectiveStrategy.name());
        detail.put("trigger", run.getTrigger().name());
        detail.put("findings", candidates.size());
        detail.put("newExceptions", newExceptions);
        detail.put("reopenedExceptions", reopened);
        detail.put("providerReachable", providerReachable);
        auditService.record("RECONCILIATION_RUN_COMPLETED", "RECONCILIATION", "SYSTEM", workspaceId,
                detail, null, null, null, null, null, run.getId(), triggeredBy,
                workspace.getProviderMode().isSimulated());

        log.info("Reconciliation run {} for workspace {} with {} produced {} findings",
                run.getId(), workspaceId, effectiveStrategy, candidates.size());

        return new RunResult(run.getId(), effectiveStrategy, candidates.size(), newExceptions, reopened,
                refreshed, providerReachable, List.copyOf(notes));
    }

    private String toJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (Exception e) {
            return "[]";
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
