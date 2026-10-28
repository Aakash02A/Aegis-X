package com.aegisx.integrity;

import com.aegisx.integrity.domain.FindingType;
import com.aegisx.integrity.domain.RecommendationCategory;
import com.aegisx.integrity.domain.RecommendationStatus;
import com.aegisx.integrity.domain.ReviewActionType;
import com.aegisx.integrity.entity.PaymentOrder;
import com.aegisx.integrity.entity.RecoveryRecommendation;
import com.aegisx.integrity.entity.Workspace;
import com.aegisx.integrity.exception.FinancialExecutionDisabledException;
import com.aegisx.integrity.security.ActorContext;
import com.aegisx.integrity.service.RecoveryService;
import com.aegisx.integrity.support.IntegrityScenarioFixtures;
import com.aegisx.integrity.support.IntegrityTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Module G acceptance tests: acceptance criterion 10 of the specification.
 *
 * <p>The claim under test is negative and therefore easy to falsify: <em>no</em> recovery
 * recommendation can move money without a human, and the prototype cannot execute a financial
 * action at all. The refund discrepancy scenario is used deliberately, because it is the one that
 * <em>does</em> raise a money-moving recommendation — so the guard is exercised rather than
 * trivially satisfied by having no financial advice at all.</p>
 */
@DisplayName("Recovery recommendations: safety boundary")
class IntegrityRecoverySafetyTest extends IntegrityTestSupport {

    private static final ActorContext.Actor ANALYST = actor("analyst@aegis-x.test", "COMPLIANCE_OFFICER");

    @Test
    @DisplayName("financial execution is disabled by default in the shipped configuration")
    void financialExecutionIsDisabledByDefault() {
        assertThat(properties.isAllowFinancialExecution())
                .as("the prototype must not be able to execute a financial action")
                .isFalse();
    }

    @Test
    @DisplayName("a refund discrepancy raises a money-moving recommendation that still requires a human")
    void refundDiscrepancyRaisesGuardedFinancialRecommendation() {
        Workspace workspace = workspaceWithRefundDiscrepancy();

        List<RecoveryRecommendation> recommendations =
                recommendationRepository.findAllByWorkspaceIdOrderByCreatedAtDesc(workspace.getId());

        assertThat(recommendations).isNotEmpty();

        List<RecoveryRecommendation> financial = recommendations.stream()
                .filter(recommendation -> recommendation.getCategory() == RecommendationCategory.FINANCIAL)
                .toList();

        assertThat(financial)
                .as("a refund the provider never processed must surface as possible money that still needs returning")
                .isNotEmpty();
        assertThat(financial).allSatisfy(recommendation -> {
            assertThat(recommendation.isRequiresHumanApproval()).isTrue();
            assertThat(recommendation.getStatus()).isEqualTo(RecommendationStatus.PROPOSED);
            assertThat(recommendation.getRiskNote())
                    .as("a money-moving recommendation must state its risk explicitly")
                    .isNotBlank();
        });

        // Every non-read-only recommendation requires a human, whatever its category.
        assertThat(recommendations)
                .filteredOn(recommendation -> recommendation.getCategory() != RecommendationCategory.READ_ONLY)
                .allSatisfy(recommendation -> assertThat(recommendation.isRequiresHumanApproval()).isTrue());
    }

    @Test
    @DisplayName("approving a financial recommendation is refused and leaves it proposed")
    void approvingFinancialRecommendationIsRefused() {
        Workspace workspace = workspaceWithRefundDiscrepancy();
        RecoveryRecommendation financial = firstFinancial(workspace);

        assertThatThrownBy(() -> recoveryService.approve(workspace.getId(), financial.getId(), ANALYST,
                "Approving the refund"))
                .isInstanceOf(FinancialExecutionDisabledException.class);

        RecoveryRecommendation after =
                recoveryService.require(workspace.getId(), financial.getId());
        assertThat(after.getStatus())
                .as("a refused approval must not leave the action in an executable state")
                .isEqualTo(RecommendationStatus.PROPOSED);
        assertThat(recommendationRepository.countByWorkspaceIdAndStatus(workspace.getId(),
                RecommendationStatus.EXECUTED))
                .as("nothing financial may reach EXECUTED")
                .isZero();
    }

    @Test
    @DisplayName("only read-only recommendations may be executed, and the execution is audited")
    void readOnlyRecommendationsExecuteAndAreAudited() {
        Workspace workspace = workspaceWithRefundDiscrepancy();
        RecoveryRecommendation readOnly = recommendationRepository
                .findAllByWorkspaceIdOrderByCreatedAtDesc(workspace.getId()).stream()
                .filter(recommendation -> recommendation.getCategory() == RecommendationCategory.READ_ONLY)
                .findFirst()
                .orElseThrow();

        RecoveryService.ExecutionOutcome outcome =
                recoveryService.executeReadOnly(workspace.getId(), readOnly.getId(), ANALYST);

        assertThat(outcome.status())
                .as("a read-only re-read of provider state is the one action that may run unattended")
                .isEqualTo(RecommendationStatus.EXECUTED);
        assertThat(outcome.detail()).containsIgnoringCase("provider");
        assertThat(reviewActionRepository.findAllByWorkspaceIdOrderByCreatedAtDesc(workspace.getId()))
                .anyMatch(action -> action.getActionType() == ReviewActionType.EXECUTE_READ_ONLY_RECOMMENDATION);
    }

    @Test
    @DisplayName("a financial recommendation cannot be executed through the read-only path")
    void financialRecommendationCannotUseTheReadOnlyPath() {
        Workspace workspace = workspaceWithRefundDiscrepancy();
        RecoveryRecommendation financial = firstFinancial(workspace);

        assertThatThrownBy(() -> recoveryService.executeReadOnly(workspace.getId(), financial.getId(), ANALYST))
                .isInstanceOf(FinancialExecutionDisabledException.class);

        assertThat(recoveryService.require(workspace.getId(), financial.getId()).getStatus())
                .isEqualTo(RecommendationStatus.PROPOSED);
    }

    @Test
    @DisplayName("a rejection is recorded as a human decision with its rationale")
    void rejectionIsRecorded() {
        Workspace workspace = workspaceWithRefundDiscrepancy();
        RecoveryRecommendation readOnly = recommendationRepository
                .findAllByWorkspaceIdOrderByCreatedAtDesc(workspace.getId()).stream()
                .filter(recommendation -> recommendation.getCategory() == RecommendationCategory.READ_ONLY)
                .findFirst()
                .orElseThrow();

        RecoveryRecommendation rejected = recoveryService.reject(workspace.getId(), readOnly.getId(), ANALYST,
                "The provider is already being reconciled out of band");

        assertThat(rejected.getStatus()).isEqualTo(RecommendationStatus.REJECTED);
        assertThat(reviewActionRepository.findByExceptionIdOrderByCreatedAtAsc(rejected.getExceptionId()))
                .anyMatch(action -> action.getActionType() == ReviewActionType.REJECT_RECOMMENDATION
                        && action.getRationale() != null);
    }

    private Workspace workspaceWithRefundDiscrepancy() {
        Workspace workspace = newWorkspace();
        PaymentOrder order = newOrderWithProviderOrder(workspace, 200_000);
        simulationService.simulateCapturedPayment(workspace.getId(), order.getId(), false);
        simulationService.injectDiscrepancy(workspace.getId(), order.getId(),
                IntegrityScenarioFixtures.DiscrepancyKind.REFUND_DISCREPANCY);
        reconcile(workspace);

        assertThat(exceptionCaseRepository.countByWorkspaceIdAndFindingType(workspace.getId(),
                FindingType.REFUND_DISCREPANCY))
                .as("the refund discrepancy must be detected for this test to be meaningful")
                .isPositive();
        return workspace;
    }

    private RecoveryRecommendation firstFinancial(Workspace workspace) {
        return recommendationRepository.findAllByWorkspaceIdOrderByCreatedAtDesc(workspace.getId()).stream()
                .filter(recommendation -> recommendation.getCategory() == RecommendationCategory.FINANCIAL)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected a financial recommendation for the refund discrepancy"));
    }
}
