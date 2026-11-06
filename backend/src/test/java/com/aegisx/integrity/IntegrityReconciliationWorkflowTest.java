package com.aegisx.integrity;

import com.aegisx.integrity.domain.ExceptionStatus;
import com.aegisx.integrity.domain.FindingType;
import com.aegisx.integrity.domain.PaymentState;
import com.aegisx.integrity.domain.RecommendationCategory;
import com.aegisx.integrity.domain.RecommendationType;
import com.aegisx.integrity.domain.ReviewActionType;
import com.aegisx.integrity.entity.ExceptionCase;
import com.aegisx.integrity.entity.PaymentOrder;
import com.aegisx.integrity.entity.PaymentRecord;
import com.aegisx.integrity.entity.RecoveryRecommendation;
import com.aegisx.integrity.entity.ReconciliationFinding;
import com.aegisx.integrity.entity.Workspace;
import com.aegisx.integrity.reconciliation.ReconciliationService;
import com.aegisx.integrity.security.ActorContext;
import com.aegisx.integrity.service.ExceptionService;
import com.aegisx.integrity.support.IntegrityScenarioFixtures;
import com.aegisx.integrity.support.IntegrityTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Module E/F/G acceptance tests: the documented end-to-end workflow.
 *
 * <p>Covers acceptance criteria 7, 8, 9 and steps 8-14 of the required workflow: reconcile, detect an
 * injected discrepancy, explain it, let a human investigate and resolve it, then <em>verify</em> the
 * resolution with a later run.</p>
 *
 * <p>The resolution-verification test deliberately asserts on the reopened set rather than on one
 * specific finding type. A discrepancy can legitimately surface as a status mismatch and a
 * conflicting-observation finding at the same time, so pinning a test to one of them would test the
 * rule table rather than the workflow.</p>
 */
@DisplayName("Reconciliation workflow")
class IntegrityReconciliationWorkflowTest extends IntegrityTestSupport {

    private static final ActorContext.Actor ANALYST = actor("analyst@aegis-x.test", "COMPLIANCE_OFFICER");

    @Test
    @DisplayName("an injected amount discrepancy becomes an explainable exception with a safe recommendation")
    void detectsAmountMismatchWithEvidence() {
        Workspace workspace = newWorkspace();
        PaymentOrder order = newOrderWithProviderOrder(workspace, 100_000);
        simulationService.simulateCapturedPayment(workspace.getId(), order.getId(), false);

        ReconciliationService.RunResult clean = reconcile(workspace);
        assertThat(clean.reopenedExceptions()).isZero();

        simulationService.injectDiscrepancy(workspace.getId(), order.getId(),
                IntegrityScenarioFixtures.DiscrepancyKind.AMOUNT_MISMATCH);

        ReconciliationService.RunResult run = reconcile(workspace);

        assertThat(run.providerReachable()).as("the simulated provider is reachable").isTrue();
        assertThat(exceptionCaseRepository.countByWorkspaceIdAndFindingType(workspace.getId(),
                FindingType.AMOUNT_MISMATCH))
                .as("the provider-confirmed short capture must be reported as an amount mismatch")
                .isPositive();

        ExceptionCase exception = exceptionCaseRepository
                .findAllByWorkspaceIdOrderByCreatedAtDesc(workspace.getId()).stream()
                .filter(candidate -> candidate.getFindingType() == FindingType.AMOUNT_MISMATCH)
                .findFirst()
                .orElseThrow();

        assertThat(exception.getTitle()).isNotBlank();
        assertThat(exception.getDiagnosis())
                .as("an exception must explain why it exists, not just that it exists")
                .isNotBlank();
        assertThat(exception.getSupportingEvidenceJson())
                .as("the classification must cite the evidence it was based on, with the amounts involved")
                .isNotNull()
                .contains("internal expected amount");
        assertThat(exception.getSeverity()).isNotNull();
        assertThat(exception.getCertainty()).isNotNull();
        assertThat(exception.getDedupeKey()).isNotBlank();

        List<RecoveryRecommendation> recommendations =
                recommendationRepository.findByExceptionIdOrderByCreatedAtAsc(exception.getId());
        assertThat(recommendations).isNotEmpty();
        assertThat(recommendations)
                .anyMatch(recommendation -> recommendation.getType() == RecommendationType.INVESTIGATE_AMOUNT_MISMATCH);
        assertThat(recommendations)
                .as("an amount mismatch must not be answered with an automatic money movement")
                .noneMatch(recommendation -> recommendation.getCategory() == RecommendationCategory.FINANCIAL
                        && !recommendation.isRequiresHumanApproval());
    }

    @Test
    @DisplayName("a resolution is provisional until a later run stops detecting the problem")
    void resolutionIsVerifiedByALaterRun() {
        Workspace workspace = newWorkspace();
        PaymentOrder order = newOrderWithProviderOrder(workspace, 150_000);
        simulationService.simulateCapturedPayment(workspace.getId(), order.getId(), false);

        ReconciliationService.RunResult baseline = reconcile(workspace);
        // Assert on the findings themselves rather than on the count, so a failure names the
        // discrepancy that was (incorrectly) reported instead of only reporting a number.
        List<ReconciliationFinding> baselineFindings =
                findingRepository.findByRunIdOrderByCreatedAtAsc(baseline.runId());
        assertThat(baselineFindings)
                .as("a normal captured payment must reconcile cleanly; reported=%s", baselineFindings.stream()
                        .map(finding -> finding.getFindingType() + ": " + finding.getSummary())
                        .toList())
                .isEmpty();
        assertThat(baseline.findingsCount())
                .as("the run's reported count must match the persisted findings")
                .isEqualTo(baselineFindings.size());

        simulationService.injectDiscrepancy(workspace.getId(), order.getId(),
                IntegrityScenarioFixtures.DiscrepancyKind.STATUS_MISMATCH);

        ReconciliationService.RunResult detecting = reconcile(workspace);
        assertThat(detecting.findingsCount())
                .as("the injected internal/provider status disagreement must be detected")
                .isPositive();

        List<ExceptionCase> detected = exceptionCaseRepository
                .findAllByWorkspaceIdOrderByCreatedAtDesc(workspace.getId());
        assertThat(detected).isNotEmpty();

        // Human review: the operator opens the case, notes the investigation and records a resolution.
        for (ExceptionCase exception : detected) {
            exceptionService.openCase(workspace.getId(), exception.getId(), ANALYST,
                    "Investigating the provider/status disagreement");
            exceptionService.addNote(workspace.getId(), exception.getId(), ANALYST,
                    "Provider test mode appears to have used a different outcome for this payment");
            exceptionService.resolve(workspace.getId(), exception.getId(), ANALYST,
                    "Requested the provider to correct the payment record",
                    "{\"providerCorrection\":\"requested\"}");
        }
        assertThat(exceptionCaseRepository.countByWorkspaceIdAndStatus(workspace.getId(), ExceptionStatus.RESOLVED))
                .isEqualTo(detected.size());
        assertThat(reviewActionRepository.findByExceptionIdOrderByCreatedAtAsc(detected.get(0).getId()))
                .extracting(action -> action.getActionType())
                .contains(ReviewActionType.OPEN_CASE, ReviewActionType.ADD_NOTE, ReviewActionType.RECORD_RESOLUTION);

        // The discrepancy is still present, so the resolution cannot be verified: the cases reopen.
        reconcile(workspace);

        List<ExceptionCase> reopened = exceptionCaseRepository
                .findAllByWorkspaceIdOrderByCreatedAtDesc(workspace.getId());
        assertThat(reopened)
                .as("a resolution that reconciliation still contradicts must not stand")
                .anyMatch(exception -> exception.getStatus() == ExceptionStatus.OPEN);
        assertThat(reviewActionRepository.findAllByWorkspaceIdOrderByCreatedAtDesc(workspace.getId()))
                .anyMatch(action -> action.getActionType() == ReviewActionType.AUTO_REOPEN);

        // The underlying cause is now actually fixed on the provider side.
        List<PaymentRecord> payments = paymentRepository.findByOrderId(order.getId());
        assertThat(payments).isNotEmpty();
        PaymentRecord captured = payments.stream()
                .filter(payment -> payment.getProviderPaymentId() != null)
                .findFirst()
                .orElseThrow();
        simulatedProvider.updatePaymentState(captured.getProviderPaymentId(), PaymentState.CAPTURED);

        // ...and the operator resolves the reopened cases again.
        for (ExceptionCase exception : exceptionCaseRepository
                .findAllByWorkspaceIdOrderByCreatedAtDesc(workspace.getId())) {
            if (exception.getStatus() != ExceptionStatus.RESOLVED) {
                exceptionService.resolve(workspace.getId(), exception.getId(), ANALYST,
                        "Provider record corrected and re-read", "{\"providerState\":\"CAPTURED\"}");
            }
        }
        long resolvedBeforeVerification = exceptionCaseRepository
                .countByWorkspaceIdAndStatus(workspace.getId(), ExceptionStatus.RESOLVED);

        ReconciliationService.RunResult verifying = reconcile(workspace);

        assertThat(verifying.reopenedExceptions())
                .as("once the cause is gone, the recorded resolutions must survive the next run")
                .isZero();
        assertThat(exceptionCaseRepository.countByWorkspaceIdAndStatus(workspace.getId(), ExceptionStatus.RESOLVED))
                .isEqualTo(resolvedBeforeVerification);
    }

    @Test
    @DisplayName("only the first reconciliation run creates a case; later runs refresh the same one")
    void repeatedRunsDoNotDuplicateCases() {
        Workspace workspace = newWorkspace();
        PaymentOrder order = newOrderWithProviderOrder(workspace, 80_000);
        simulationService.simulateCapturedPayment(workspace.getId(), order.getId(), false);
        simulationService.injectDiscrepancy(workspace.getId(), order.getId(),
                IntegrityScenarioFixtures.DiscrepancyKind.AMOUNT_MISMATCH);

        ReconciliationService.RunResult firstRun = reconcile(workspace);
        long afterFirstRun = exceptionCaseRepository.countByWorkspaceId(workspace.getId());
        long firstRunFindings = findingRepository.countByRunId(firstRun.runId());

        // The persisted rows for a run must equal the number that run reports, otherwise the run's
        // own result disagrees with its audit trail. (An earlier version of this test compared the
        // second run's finding count with the first run's *case* count — two different quantities —
        // and therefore failed whenever a discrepancy legitimately cleared during the second run.)
        assertThat(firstRunFindings)
                .as("the first run's persisted findings must match its reported count; reported=%s",
                        findingRepository.findByRunIdOrderByCreatedAtAsc(firstRun.runId()).stream()
                                .map(finding -> finding.getFindingType() + ": " + finding.getSummary()).toList())
                .isEqualTo(firstRun.findingsCount())
                .isPositive();

        ReconciliationService.RunResult secondRun = reconcile(workspace);
        long afterSecondRun = exceptionCaseRepository.countByWorkspaceId(workspace.getId());

        assertThat(afterFirstRun).isPositive();
        assertThat(afterSecondRun)
                .as("the dedupe key must prevent one discrepancy from becoming many cases")
                .isEqualTo(afterFirstRun);
        assertThat(runRepository.countByWorkspaceId(workspace.getId()))
                .as("each run is still recorded separately")
                .isEqualTo(2);
        assertThat(runRepository.findAllByWorkspaceIdOrderByStartedAtDesc(workspace.getId()).get(0).getId())
                .isEqualTo(secondRun.runId());
        // A later run adds its own findings; it must not rewrite the earlier run's rows.
        assertThat(findingRepository.countByRunId(firstRun.runId()))
                .as("the first run's findings must survive later runs")
                .isEqualTo(firstRunFindings);
        assertThat(findingRepository.countByRunId(secondRun.runId()))
                .as("the second run's persisted findings must match its reported count; reported=%s",
                        findingRepository.findByRunIdOrderByCreatedAtAsc(secondRun.runId()).stream()
                                .map(finding -> finding.getFindingType() + ": " + finding.getSummary()).toList())
                .isEqualTo(secondRun.findingsCount())
                .isPositive();
    }

    @Test
    @DisplayName("a provider refresh that cannot reach the provider is reported, not silently ignored")
    void providerOutageIsReported() {
        Workspace workspace = newWorkspace();
        PaymentOrder order = newOrderWithProviderOrder(workspace, 20_000);
        simulationService.simulateCapturedPayment(workspace.getId(), order.getId(), false);

        simulatedProvider.setReachable(false);
        try {
            ReconciliationService.RunResult result = reconcile(workspace);
            assertThat(result.providerReachable())
                    .as("reconciliation must say that it could not confirm provider state")
                    .isFalse();
            assertThat(result.notes())
                    .as("the run must explain that it worked from stored evidence only")
                    .isNotEmpty();

            // An outage must never be presented as a clean bill of health. Every finding raised
            // during the outage is flagged as resting on unconfirmed provider evidence.
            assertThat(exceptionCaseRepository.findAllByWorkspaceIdOrderByCreatedAtDesc(workspace.getId()))
                    .allSatisfy(exception -> assertThat(exception.isProviderUnreachable())
                            .as("a finding recorded during an outage must say so")
                            .isTrue());
        } finally {
            simulatedProvider.setReachable(true);
        }

        // Once the provider can be reached again, the disagreement disappears: the internal order
        // state is confirmed rather than merely asserted.
        ReconciliationService.RunResult recovered = reconcile(workspace);
        assertThat(recovered.providerReachable()).isTrue();
        List<ReconciliationFinding> recoveredFindings =
                findingRepository.findByRunIdOrderByCreatedAtAsc(recovered.runId());
        assertThat(recovered.findingsCount())
                .as("a disagreement that was only unconfirmed must clear once provider state is re-read; "
                                + "reported=%s observations=%s",
                        recoveredFindings.stream()
                                .map(finding -> finding.getFindingType() + ": " + finding.getSummary())
                                .toList(),
                        observationRepository.findAllByWorkspaceIdOrderByObservedAtDesc(workspace.getId()).stream()
                                .filter(observation -> observation.getSource().isProviderAuthoritative())
                                .map(observation -> observation.getSource() + "/rank=" + observation.getEvidenceRank()
                                        + " " + observation.getObservedState() + " @" + observation.getObservedAt()
                                        + (observation.isSimulated() ? " (simulated)" : ""))
                                .toList())
                .isZero();
    }

    @Test
    @DisplayName("the audit trail records the reconciliation run and the human decisions")
    void auditTrailCoversRunAndDecisions() {
        Workspace workspace = newWorkspace();
        PaymentOrder order = newOrderWithProviderOrder(workspace, 30_000);
        simulationService.simulateCapturedPayment(workspace.getId(), order.getId(), false);
        simulateDiscrepancyAndResolve(workspace, order);

        assertThat(auditEventRepository.countByWorkspaceId(workspace.getId()))
                .as("order creation, webhook handling, reconciliation and review must all be auditable")
                .isPositive();
        assertThat(auditEventRepository.findByWorkspaceIdOrderByOccurredAtDesc(workspace.getId(),
                        org.springframework.data.domain.PageRequest.of(0, 200))
                .getContent())
                .extracting(event -> event.getEventType())
                .contains("RECONCILIATION_RUN_COMPLETED");
    }

    private void simulateDiscrepancyAndResolve(Workspace workspace, PaymentOrder order) {
        simulationService.injectDiscrepancy(workspace.getId(), order.getId(),
                IntegrityScenarioFixtures.DiscrepancyKind.AMOUNT_MISMATCH);
        reconcile(workspace);
        for (ExceptionCase exception : exceptionCaseRepository
                .findAllByWorkspaceIdOrderByCreatedAtDesc(workspace.getId())) {
            if (exception.getStatus() != ExceptionStatus.RESOLVED) {
                exceptionService.resolve(workspace.getId(), exception.getId(), ANALYST,
                        "Reviewed and recorded", null);
            }
        }
    }

    @Test
    @DisplayName("findings carry a certainty level and a severity, and never claim fraud")
    void findingsAreLabelledHonestly() {
        Workspace workspace = newWorkspace();
        PaymentOrder order = newOrderWithProviderOrder(workspace, 55_000);
        simulationService.simulateCapturedPayment(workspace.getId(), order.getId(), false);
        simulationService.injectDiscrepancy(workspace.getId(), order.getId(),
                IntegrityScenarioFixtures.DiscrepancyKind.AMOUNT_MISMATCH);
        reconcile(workspace);

        exceptionCaseRepository.findAllByWorkspaceIdOrderByCreatedAtDesc(workspace.getId()).forEach(exception -> {
            assertThat(exception.getSeverity()).isNotNull();
            assertThat(exception.getCertainty()).isNotNull();
            assertThat(exception.getTitle().toLowerCase(java.util.Locale.ROOT))
                    .as("a mismatch is not evidence of fraud")
                    .doesNotContain("fraud");
            assertThat(exception.getDiagnosis().toLowerCase(java.util.Locale.ROOT))
                    .doesNotContain("fraud");
        });
    }
}
