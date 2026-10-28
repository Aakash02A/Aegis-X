package com.aegisx.integrity.service;

import com.aegisx.integrity.config.IntegrityProperties;
import com.aegisx.integrity.domain.CapabilityStatus;
import com.aegisx.integrity.domain.ExceptionSeverity;
import com.aegisx.integrity.domain.ExceptionStatus;
import com.aegisx.integrity.domain.FindingType;
import com.aegisx.integrity.domain.PaymentState;
import com.aegisx.integrity.domain.ProviderMode;
import com.aegisx.integrity.domain.RecommendationCategory;
import com.aegisx.integrity.domain.RecommendationStatus;
import com.aegisx.integrity.domain.RefundState;
import com.aegisx.integrity.domain.WebhookProcessingState;
import com.aegisx.integrity.entity.IntegrityAuditEvent;
import com.aegisx.integrity.entity.ReconciliationRun;
import com.aegisx.integrity.entity.Workspace;
import com.aegisx.integrity.repository.ExceptionCaseRepository;
import com.aegisx.integrity.repository.IntegrityAuditEventRepository;
import com.aegisx.integrity.repository.PaymentOrderRepository;
import com.aegisx.integrity.repository.PaymentRecordRepository;
import com.aegisx.integrity.repository.RecoveryRecommendationRepository;
import com.aegisx.integrity.repository.ReconciliationFindingRepository;
import com.aegisx.integrity.repository.ReconciliationRunRepository;
import com.aegisx.integrity.repository.RefundRecordRepository;
import com.aegisx.integrity.repository.TransferRecordRepository;
import com.aegisx.integrity.repository.WebhookDeliveryRepository;
import com.aegisx.integrity.repository.IntegrityWebhookEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Module I: dashboard metrics.
 *
 * <p>Every value is computed from persisted rows at request time. Nothing is hard-coded and nothing
 * is cached, so a metric that looks wrong is a data question rather than a display bug.</p>
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    private static final List<ExceptionStatus> UNRESOLVED =
            List.of(ExceptionStatus.OPEN, ExceptionStatus.IN_REVIEW);

    private final WorkspaceService workspaceService;
    private final PaymentOrderRepository orderRepository;
    private final PaymentRecordRepository paymentRepository;
    private final RefundRecordRepository refundRepository;
    private final TransferRecordRepository transferRepository;
    private final ExceptionCaseRepository exceptionRepository;
    private final RecoveryRecommendationRepository recommendationRepository;
    private final ReconciliationRunRepository runRepository;
    private final ReconciliationFindingRepository findingRepository;
    private final IntegrityWebhookEventRepository webhookEventRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    private final IntegrityAuditEventRepository auditRepository;
    private final IntegrityProperties properties;

    public record ActivityItem(String eventType, String category, String actorLabel, Instant occurredAt,
                               boolean simulated) { }

    public record DashboardSummary(
            UUID workspaceId,
            String workspaceName,
            ProviderMode providerMode,
            boolean simulated,
            CapabilityStatus providerConfigured,
            CapabilityStatus transferCapability,
            boolean financialExecutionEnabled,
            long totalOrders,
            long totalPayments,
            long confirmedSuccessfulPayments,
            long failedPayments,
            long pendingPayments,
            long totalRefunds,
            long pendingRefunds,
            long totalTransfers,
            long unresolvedExceptions,
            long openExceptions,
            long inReviewExceptions,
            long resolvedExceptions,
            long dismissedExceptions,
            long reconciliationMismatches,
            long latestRunFindings,
            long duplicateDeliveries,
            long totalWebhookDeliveries,
            long failedWebhookEvents,
            Instant lastReconciliationAt,
            String lastReconciliationStrategy,
            boolean lastReconciliationProviderReachable,
            Map<String, Long> exceptionsByType,
            Map<String, Long> exceptionsBySeverity,
            long recommendationsProposed,
            long recommendationsRequiringHumanApproval,
            long recommendationsFinancial,
            List<ActivityItem> recentActivity) { }

    @Transactional(readOnly = true)
    public DashboardSummary summary(UUID workspaceId) {
        Workspace workspace = workspaceService.get(workspaceId);
        ReconciliationRun lastRun = runRepository.findTopByWorkspaceIdOrderByStartedAtDesc(workspaceId).orElse(null);

        long unresolved = exceptionRepository.countByWorkspaceIdAndStatusIn(workspaceId, UNRESOLVED);
        long open = exceptionRepository.countByWorkspaceIdAndStatus(workspaceId, ExceptionStatus.OPEN);
        long inReview = exceptionRepository.countByWorkspaceIdAndStatus(workspaceId, ExceptionStatus.IN_REVIEW);
        long resolved = exceptionRepository.countByWorkspaceIdAndStatus(workspaceId, ExceptionStatus.RESOLVED);
        long dismissed = exceptionRepository.countByWorkspaceIdAndStatus(workspaceId, ExceptionStatus.DISMISSED);

        Map<String, Long> byType = new LinkedHashMap<>();
        for (FindingType type : FindingType.values()) {
            long count = exceptionRepository.countByWorkspaceIdAndFindingType(workspaceId, type);
            if (count > 0) {
                byType.put(type.name(), count);
            }
        }
        Map<String, Long> bySeverity = new LinkedHashMap<>();
        for (ExceptionSeverity severity : ExceptionSeverity.values()) {
            long count = exceptionRepository.countByWorkspaceIdAndSeverity(workspaceId, severity);
            if (count > 0) {
                bySeverity.put(severity.name(), count);
            }
        }

        List<ActivityItem> activity = auditRepository
                .findByWorkspaceIdOrderByOccurredAtDesc(workspaceId, PageRequest.of(0, 12))
                .map(this::toActivity)
                .toList();
        // Page.map above yields Page<ActivityItem>; take its content.
        List<ActivityItem> recentActivity = activity;

        return new DashboardSummary(
                workspaceId,
                workspace.getName(),
                workspace.getProviderMode(),
                workspace.getProviderMode() == ProviderMode.SIMULATED,
                workspace.isProviderConfigured() ? workspace.getTransferCapability() : CapabilityStatus.NOT_CONFIGURED,
                workspace.getTransferCapability(),
                properties.isAllowFinancialExecution(),
                orderRepository.countByWorkspaceId(workspaceId),
                paymentRepository.countByWorkspaceId(workspaceId),
                paymentRepository.countByWorkspaceIdAndStateIn(workspaceId,
                        List.of(PaymentState.CAPTURED, PaymentState.PARTIALLY_REFUNDED, PaymentState.REFUNDED)),
                paymentRepository.countByWorkspaceIdAndState(workspaceId, PaymentState.FAILED),
                paymentRepository.countByWorkspaceIdAndStateIn(workspaceId,
                        List.of(PaymentState.CREATED, PaymentState.AUTHORIZED, PaymentState.TIMEOUT_UNKNOWN)),
                refundRepository.countByWorkspaceId(workspaceId),
                refundRepository.countByWorkspaceIdAndState(workspaceId, RefundState.PENDING),
                transferRepository.countByWorkspaceId(workspaceId),
                unresolved, open, inReview, resolved, dismissed,
                findingRepository.countByWorkspaceIdAndFindingType(workspaceId, FindingType.AMOUNT_MISMATCH)
                        + findingRepository.countByWorkspaceIdAndFindingType(workspaceId, FindingType.STATUS_MISMATCH)
                        + findingRepository.countByWorkspaceIdAndFindingType(workspaceId, FindingType.CURRENCY_MISMATCH)
                        + findingRepository.countByWorkspaceIdAndFindingType(workspaceId, FindingType.CONFLICTING_OBSERVATIONS),
                lastRun == null ? 0 : findingRepository.countByRunId(lastRun.getId()),
                deliveryRepository.countByWorkspaceIdAndFirstDeliveryFalse(workspaceId),
                deliveryRepository.countByWorkspaceId(workspaceId),
                webhookEventRepository.countByWorkspaceIdAndProcessingState(workspaceId, WebhookProcessingState.FAILED),
                lastRun == null ? null : lastRun.getStartedAt(),
                lastRun == null ? null : lastRun.getStrategy().name(),
                lastRun != null && lastRun.isProviderReachable(),
                byType, bySeverity,
                recommendationRepository.countByWorkspaceIdAndStatus(workspaceId, RecommendationStatus.PROPOSED),
                recommendationRepository.countByWorkspaceId(workspaceId)
                        - recommendationRepository.countByWorkspaceIdAndCategory(workspaceId, RecommendationCategory.READ_ONLY),
                recommendationRepository.countByWorkspaceIdAndCategory(workspaceId, RecommendationCategory.FINANCIAL),
                recentActivity);
    }

    private ActivityItem toActivity(IntegrityAuditEvent event) {
        return new ActivityItem(event.getEventType(), event.getCategory(), event.getActorLabel(),
                event.getOccurredAt(), event.isSimulated());
    }
}
