package com.aegisx.integrity.support;

import com.aegisx.document.entity.DocumentType;
import com.aegisx.integrity.benchmark.IntegrityBenchmarkService;
import com.aegisx.integrity.config.IntegrityProperties;
import com.aegisx.integrity.domain.EvidenceReadinessStatus;
import com.aegisx.integrity.domain.FindingType;
import com.aegisx.integrity.evidence.EvidenceCheckService;
import com.aegisx.integrity.evidence.EvidenceIntelligenceService;
import com.aegisx.integrity.evidence.EvidenceReadinessService;
import com.aegisx.integrity.repository.EvidenceDocumentRepository;
import com.aegisx.integrity.repository.EvidenceExtractionRepository;
import com.aegisx.integrity.repository.EvidenceReadinessRepository;
import com.aegisx.integrity.domain.PaymentState;
import com.aegisx.integrity.domain.ProviderMode;
import com.aegisx.integrity.domain.ReconciliationStrategy;
import com.aegisx.integrity.domain.ReconciliationTrigger;
import com.aegisx.integrity.entity.PaymentOrder;
import com.aegisx.integrity.entity.Workspace;
import com.aegisx.integrity.reconciliation.ReconciliationService;
import com.aegisx.integrity.repository.ExceptionCaseRepository;
import com.aegisx.integrity.repository.IntegrityAuditEventRepository;
import com.aegisx.integrity.repository.IntegrityWebhookEventRepository;
import com.aegisx.integrity.repository.PaymentOrderRepository;
import com.aegisx.integrity.repository.PaymentRecordRepository;
import com.aegisx.integrity.repository.ProviderObservationRepository;
import com.aegisx.integrity.repository.ReconciliationFindingRepository;
import com.aegisx.integrity.repository.ReconciliationRunRepository;
import com.aegisx.integrity.repository.RecoveryRecommendationRepository;
import com.aegisx.integrity.repository.RefundRecordRepository;
import com.aegisx.integrity.repository.ReviewActionRepository;
import com.aegisx.integrity.repository.StateTransitionRepository;
import com.aegisx.integrity.repository.TransferRecordRepository;
import com.aegisx.integrity.repository.WebhookDeliveryRepository;
import com.aegisx.integrity.repository.WorkspaceRepository;
import com.aegisx.integrity.security.ActorContext;
import com.aegisx.integrity.service.ExceptionService;
import com.aegisx.integrity.service.ObservationService;
import com.aegisx.integrity.service.OrderService;
import com.aegisx.integrity.service.RecoveryService;
import com.aegisx.integrity.service.WorkspaceService;
import com.aegisx.integrity.webhook.IntegrityWebhookIngestionService;
import com.aegisx.integrity.webhook.IntegrityWebhookSignatureService;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared fixture for the payment integrity tests.
 *
 * <p>Every test gets its own workspace, so tests never observe each other's rows even though the
 * H2 schema is shared. The offline provider fixture is deliberately <em>not</em> reset between
 * tests: its identifier sequence must keep increasing, otherwise two tests could produce the same
 * provider payment id and the duplicate guard (correctly) would treat the second one as a repeat.</p>
 *
 * <p>Financial execution is pinned to {@code false} here so the safety properties are asserted
 * against the shipped default rather than a test-only configuration.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "aegisx.integrity.benchmark-output-dir=target/benchmark-results",
        "aegisx.integrity.allow-financial-execution=false"
})
@DisplayName("Payment integrity engine")
public abstract class IntegrityTestSupport {

    @Autowired protected WorkspaceService workspaceService;
    @Autowired protected WorkspaceRepository workspaceRepository;
    @Autowired protected OrderService orderService;
    @Autowired protected IntegrityScenarioFixtures simulationService;
    @Autowired protected ObservationService observationService;
    @Autowired protected IntegrityWebhookIngestionService ingestionService;
    @Autowired protected ReconciliationService reconciliationService;
    @Autowired protected ExceptionService exceptionService;
    @Autowired protected RecoveryService recoveryService;
    @Autowired protected IntegrityBenchmarkService benchmarkService;
    @Autowired protected TestPaymentProviderAdapter simulatedProvider;
    @Autowired protected IntegrityWebhookSignatureService signatureService;
    @Autowired protected IntegrityProperties properties;

    @Autowired protected PaymentOrderRepository orderRepository;
    @Autowired protected PaymentRecordRepository paymentRepository;
    @Autowired protected RefundRecordRepository refundRepository;
    @Autowired protected TransferRecordRepository transferRepository;
    @Autowired protected IntegrityWebhookEventRepository webhookEventRepository;
    @Autowired protected WebhookDeliveryRepository deliveryRepository;
    @Autowired protected StateTransitionRepository transitionRepository;
    @Autowired protected ProviderObservationRepository observationRepository;
    @Autowired protected ReconciliationRunRepository runRepository;
    @Autowired protected ReconciliationFindingRepository findingRepository;
    @Autowired protected ExceptionCaseRepository exceptionCaseRepository;
    @Autowired protected RecoveryRecommendationRepository recommendationRepository;
    @Autowired protected ReviewActionRepository reviewActionRepository;
    @Autowired protected IntegrityAuditEventRepository auditEventRepository;
    @Autowired protected EvidenceDocumentRepository evidenceDocumentRepository;
    @Autowired protected EvidenceExtractionRepository evidenceExtractionRepository;
    @Autowired protected EvidenceReadinessRepository evidenceReadinessRepository;
    @Autowired protected EvidenceIntelligenceService evidenceService;
    @Autowired protected EvidenceCheckService evidenceCheckService;
    @Autowired protected EvidenceReadinessService evidenceReadinessService;
    @Autowired protected com.aegisx.integrity.evidence.EvidenceFieldExtractor evidenceFieldExtractor;

    /** A fresh workspace configured for historical simulated-provenance test scenarios. */
    protected Workspace newWorkspace() {
        Workspace workspace = workspaceService.create(
                "Integrity Test Workspace " + UUID.randomUUID(), ProviderMode.RAZORPAY_TEST);
        workspace.setProviderMode(ProviderMode.SIMULATED);
        workspaceRepository.save(workspace);
        return workspaceService.refreshCapabilities(workspace.getId());
    }

    /** Creates an internal order and asserts the offline provider fixture issued an order id. */
    protected PaymentOrder newOrderWithProviderOrder(Workspace workspace, long amountMinor) {
        OrderService.CreationOutcome outcome =
                orderService.create(workspace.getId(), amountMinor, "INR", "integrity test order", null);
        assertThat(outcome.order().getProviderOrderId())
                .as("the simulated provider must return a provider order id")
                .isNotNull();
        return outcome.order();
    }

    /** Builds a webhook body in the provider's documented envelope shape. */
    protected static String paymentEventBody(String eventType, String providerPaymentId, String providerOrderId,
                                             long amountMinor, String currency, String providerStatus) {
        String template = "{\"event\":\"%s\",\"created_at\":%d,\"payload\":{\"payment\":{\"entity\":{"
                + "\"id\":\"%s\",\"order_id\":\"%s\",\"status\":\"%s\",\"amount\":%d,"
                + "\"currency\":\"%s\",\"method\":\"card\"}}}}";
        return template.formatted(eventType, Instant.now().getEpochSecond(), providerPaymentId,
                providerOrderId, providerStatus, amountMinor, currency);
    }

    /** Delivers a correctly signed webhook through the real ingestion path. */
    protected IntegrityWebhookIngestionService.IngestionResult deliver(Workspace workspace, String body,
                                                                      String providerEventId) {
        return ingestionService.ingest(workspace.getId(), body, simulatedProvider.sign(body), providerEventId);
    }

    /** A reconciliation run using the shipped decision procedure, with a provider refresh. */
    protected ReconciliationService.RunResult reconcile(Workspace workspace) {
        return reconciliationService.run(workspace.getId(), ReconciliationTrigger.MANUAL,
                ReconciliationStrategy.PROPOSED_EVIDENCE_BASED, null, true);
    }

    protected static ActorContext.Actor actor(String email, String role) {
        return new ActorContext.Actor(null, email, role);
    }

    /** Registers a provider-side payment and returns its provider payment id. */
    protected String registerProviderPayment(PaymentOrder order, long amountMinor, PaymentState state) {
        return simulatedProvider.registerPayment(order.getProviderOrderId(), amountMinor, order.getCurrency(), state);
    }

    // ------------------------------------------------------------------ evidence

    /**
     * Uploads an evidence document through the real pipeline: validation, hashing, storage,
     * extraction and assessment. Nothing is written directly to the database.
     */
    protected EvidenceIntelligenceService.UploadOutcome uploadEvidence(Workspace workspace, PaymentOrder order,
                                                                      DocumentType type, String filename,
                                                                      byte[] content) {
        return evidenceService.receiveAndProcess(workspace.getId(), order.getId(), type,
                new org.springframework.mock.web.MockMultipartFile("file", filename, null, content),
                OPERATOR);
    }

    /** The evidence findings currently open for an order, by type. */
    protected List<FindingType> evidenceFindingTypes(Workspace workspace, PaymentOrder order) {
        return exceptionCaseRepository.findAllByWorkspaceIdAndOrderIdOrderByCreatedAtDesc(
                        workspace.getId(), order.getId()).stream()
                .filter(exception -> exception.getFindingType().isEvidenceFinding())
                .filter(exception -> exception.getStatus() == com.aegisx.integrity.domain.ExceptionStatus.OPEN)
                .map(com.aegisx.integrity.entity.ExceptionCase::getFindingType)
                .distinct()
                .toList();
    }

    /** The readiness status currently recorded for an order. */
    protected EvidenceReadinessStatus evidenceReadiness(Workspace workspace, PaymentOrder order) {
        return evidenceReadinessRepository.findByWorkspaceIdAndOrderId(workspace.getId(), order.getId())
                .map(com.aegisx.integrity.entity.EvidenceReadiness::getStatus)
                .orElseThrow(() -> new AssertionError("No evidence readiness row exists for order " + order.getId()));
    }

    /** A stable actor for uploads and assessments. */
    protected static final ActorContext.Actor OPERATOR =
            new ActorContext.Actor(null, "evidence.operator@aegis-x.test", "COMPLIANCE_OFFICER");
}
