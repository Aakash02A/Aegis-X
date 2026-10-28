package com.aegisx.integrity;

import com.aegisx.integrity.domain.ExceptionStatus;
import com.aegisx.integrity.domain.FindingType;
import com.aegisx.integrity.entity.ExceptionCase;
import com.aegisx.integrity.entity.PaymentOrder;
import com.aegisx.integrity.entity.Workspace;
import com.aegisx.integrity.support.IntegrityScenarioFixtures;
import com.aegisx.integrity.support.MySqlIntegrityTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reconciliation persistence and case lifecycle against a real MySQL 8.4 server.
 *
 * <p>The behaviour worth proving here is that repeated reconciliation <em>refreshes</em> a case rather
 * than duplicating it, that a resolution is provisional until a later run stops detecting the problem,
 * and that all of it is enforced by the database rather than by application bookkeeping. The unique
 * constraint on {@code (workspace_id, dedupe_key)} is what makes "one case per issue" true even if two
 * runs were to overlap, so it is asserted directly.</p>
 *
 * <p>This is also where the ported CHECK constraints get exercised on realistic values: every column
 * written here (finding type, severity, certainty, status, recommendation category and status) is
 * covered by one of the 60 CHECK constraints, so a taxonomy that was mistranslated would fail here
 * rather than in production.</p>
 */
@EnabledIfEnvironmentVariable(named = "INTEGRITY_MYSQL_URL", matches = ".+")
@DisplayName("Reconciliation on MySQL")
class MySqlReconciliationTest extends MySqlIntegrityTestSupport {

    private static final long ORDER_AMOUNT = 100_000L;

    @Test
    @DisplayName("a repeated finding refreshes one case and increments its detection count")
    void repeatedFindingRefreshesOneCase() {
        Workspace workspace = newWorkspace();
        PaymentOrder order = newOrderWithProviderOrder(workspace, ORDER_AMOUNT);
        simulationService.simulateCapturedPayment(workspace.getId(), order.getId(), false);

        reconcile(workspace);
        simulationService.injectDiscrepancy(workspace.getId(), order.getId(),
                IntegrityScenarioFixtures.DiscrepancyKind.AMOUNT_MISMATCH);

        reconcile(workspace);
        reconcile(workspace);

        List<ExceptionCase> mismatches = exceptions(workspace, FindingType.AMOUNT_MISMATCH);

        assertThat(mismatches)
                .as("two runs finding the same problem must refresh one case, not create two")
                .hasSize(1);
        assertThat(mismatches.get(0).getDetectionCount())
                .as("the case must record that it has now been detected twice")
                .isEqualTo(2L);
        assertThat(mismatches.get(0).getDedupeKey()).isNotBlank();
    }

    @Test
    @DisplayName("the database itself refuses a second case for the same dedupe key")
    void dedupeKeyIsUniquelyConstrained() {
        Workspace workspace = newWorkspace();
        PaymentOrder order = newOrderWithProviderOrder(workspace, ORDER_AMOUNT);
        simulationService.simulateCapturedPayment(workspace.getId(), order.getId(), false);
        reconcile(workspace);
        simulationService.injectDiscrepancy(workspace.getId(), order.getId(),
                IntegrityScenarioFixtures.DiscrepancyKind.AMOUNT_MISMATCH);
        reconcile(workspace);

        ExceptionCase existing = exceptions(workspace, FindingType.AMOUNT_MISMATCH).get(0);

        // Deduplication must be enforced by the database, not merely by a find-then-save in the
        // service, otherwise two overlapping runs could still create two cases.
        assertThatThrownBy(() -> jdbc().update(
                "INSERT INTO pi_exceptions (id, workspace_id, dedupe_key, finding_type, severity, status, "
                        + "certainty, title, diagnosis) VALUES (?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), workspace.getId().toString(), existing.getDedupeKey(),
                existing.getFindingType().name(), existing.getSeverity().name(), "OPEN",
                existing.getCertainty().name(), "duplicate probe", "duplicate probe"))
                .as("uq_pi_exceptions_dedupe must reject a second case for the same key")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("recommendations are not duplicated per case and recommendation type")
    void recommendationsAreNotDuplicated() {
        Workspace workspace = newWorkspace();
        PaymentOrder order = newOrderWithProviderOrder(workspace, ORDER_AMOUNT);
        simulationService.simulateCapturedPayment(workspace.getId(), order.getId(), false);
        reconcile(workspace);
        simulationService.injectDiscrepancy(workspace.getId(), order.getId(),
                IntegrityScenarioFixtures.DiscrepancyKind.AMOUNT_MISMATCH);

        reconcile(workspace);
        ExceptionCase afterFirst = exceptions(workspace, FindingType.AMOUNT_MISMATCH).get(0);
        long firstCount = recommendationCount(afterFirst.getId());

        reconcile(workspace);
        ExceptionCase afterSecond = exceptions(workspace, FindingType.AMOUNT_MISMATCH).get(0);

        assertThat(afterSecond.getId())
                .as("it must still be the same case")
                .isEqualTo(afterFirst.getId());
        assertThat(recommendationCount(afterSecond.getId()))
                .as("a second run must not add another recommendation of a type the case already has")
                .isEqualTo(firstCount);

        assertThat(jdbc().queryForList(
                "SELECT recommendation_type, COUNT(*) AS n FROM pi_recovery_recommendations "
                        + "WHERE exception_id = ? GROUP BY recommendation_type HAVING COUNT(*) > 1",
                afterSecond.getId().toString()))
                .as("no (case, recommendation type) pair may appear twice")
                .isEmpty();
    }

    @Test
    @DisplayName("resolving a case does not close it while the problem still exists")
    void resolvingIsProvisionalUntilALaterRunAgrees() {
        Workspace workspace = newWorkspace();
        PaymentOrder order = newOrderWithProviderOrder(workspace, ORDER_AMOUNT);
        simulationService.simulateCapturedPayment(workspace.getId(), order.getId(), false);
        reconcile(workspace);
        simulationService.injectDiscrepancy(workspace.getId(), order.getId(),
                IntegrityScenarioFixtures.DiscrepancyKind.AMOUNT_MISMATCH);

        reconcile(workspace);
        ExceptionCase exception = exceptions(workspace, FindingType.AMOUNT_MISMATCH).get(0);
        assertThat(exception.getStatus()).isEqualTo(ExceptionStatus.OPEN);

        exceptionService.resolve(workspace.getId(), exception.getId(), OPERATOR,
                "Refund processed outside the provider and evidenced.", null);
        assertThat(exceptionCaseRepository.findById(exception.getId()).orElseThrow().getStatus())
                .isEqualTo(ExceptionStatus.RESOLVED);

        // The discrepancy is still present, so a later run must reopen the case and record why.
        reconcile(workspace);

        assertThat(exceptionCaseRepository.findById(exception.getId()).orElseThrow().getStatus())
                .as("a resolution that the next run contradicts must be reopened automatically")
                .isEqualTo(ExceptionStatus.OPEN);
        assertThat(jdbc().queryForObject(
                "SELECT COUNT(*) FROM pi_review_actions WHERE exception_id = ? AND action_type = ?",
                Integer.class, exception.getId().toString(), "AUTO_REOPEN"))
                .as("the automatic reopening must be recorded as a review action, so the audit trail "
                        + "shows the resolution was provisional")
                .isPositive();
    }

    private List<ExceptionCase> exceptions(Workspace workspace, FindingType type) {
        return exceptionCaseRepository.findAllByWorkspaceIdOrderByCreatedAtDesc(workspace.getId()).stream()
                .filter(exception -> exception.getFindingType() == type)
                .toList();
    }

    private long recommendationCount(UUID exceptionId) {
        return jdbc().queryForObject(
                "SELECT COUNT(*) FROM pi_recovery_recommendations WHERE exception_id = ?",
                Long.class, exceptionId.toString());
    }
}
