package com.aegisx.integrity;

import com.aegisx.document.entity.DocumentType;
import com.aegisx.integrity.domain.EvidenceProcessingStatus;
import com.aegisx.integrity.domain.EvidenceReadinessStatus;
import com.aegisx.integrity.domain.ExceptionStatus;
import com.aegisx.integrity.domain.FindingType;
import com.aegisx.integrity.domain.PaymentState;
import com.aegisx.integrity.domain.RecommendationCategory;
import com.aegisx.integrity.domain.RecommendationStatus;
import com.aegisx.integrity.entity.EvidenceDocument;
import com.aegisx.integrity.entity.EvidenceExtraction;
import com.aegisx.integrity.entity.ExceptionCase;
import com.aegisx.integrity.entity.PaymentOrder;
import com.aegisx.integrity.entity.PaymentRecord;
import com.aegisx.integrity.entity.Workspace;
import com.aegisx.integrity.evidence.EvidenceCheckService;
import com.aegisx.integrity.evidence.EvidenceDocumentService;
import com.aegisx.integrity.evidence.EvidenceIntelligenceService;
import com.aegisx.integrity.exception.IntegrityConflictException;
import com.aegisx.integrity.exception.IntegrityNotFoundException;
import com.aegisx.integrity.support.EvidenceFixtures;
import com.aegisx.integrity.support.IntegrityTestSupport;
import com.aegisx.storage.service.StorageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Module I acceptance tests: the evidence-intelligence workflow.
 *
 * <p>Covers the twelve required success and failure scenarios. Four of them (duplicate delivery,
 * out-of-order delivery, refund handling and the payment-side failure path) are asserted here from
 * the evidence angle — that the evidence dimension stays correct and stays separate — because their
 * payment-side behaviour is already covered by the ingestion and reconciliation suites. Asserting
 * them here as well is deliberate: the product's central claim is that the two dimensions do not
 * contaminate each other, and that claim can only be tested where both exist.</p>
 *
 * <p>Every document in these tests is a real PDF built by {@link EvidenceFixtures} and uploaded
 * through the real pipeline, so validation, hashing, storage, extraction and assessment all run.
 * Nothing is written directly to the database to make a test pass.</p>
 */
@DisplayName("Evidence intelligence")
class IntegrityEvidenceIntelligenceTest extends IntegrityTestSupport {

    @Autowired private EvidenceDocumentService evidenceDocumentService;
    @Autowired private StorageService storageService;
    @Autowired private PlatformTransactionManager transactionManager;

    private static final long ORDER_AMOUNT = 150_000L; // 1500.00 INR
    private static final String ORDER_CURRENCY = "INR";

    // 1 ------------------------------------------------------------------------

    @Test
    @DisplayName("a successful payment with complete matching evidence reaches EVIDENCE_COMPLETE")
    void successfulPaymentWithMatchingEvidence() {
        Workspace workspace = newWorkspace();
        PaymentOrder order = newOrderWithProviderOrder(workspace, ORDER_AMOUNT);
        simulationService.simulateCapturedPayment(workspace.getId(), order.getId(), false);

        EvidenceIntelligenceService.UploadOutcome outcome = uploadEvidence(workspace, order,
                DocumentType.COMMERCIAL_INVOICE, "invoice-0001.pdf",
                EvidenceFixtures.invoicePdf("INV-2026-0001", order.getInternalReference(), ORDER_CURRENCY,
                        "1500.00"));

        assertThat(outcome.extractionFailed())
                .as("the invoice must be readable: %s", outcome.extractionMessage())
                .isFalse();

        // The extracted values are the ones the matching rules rely on, so assert them directly.
        EvidenceDocument document = outcome.document();
        assertThat(document.getExtractedInvoiceNumber()).isEqualTo("INV-2026-0001");
        assertThat(document.getExtractedAmountMinor())
                .as("the stated total is read into minor units")
                .isEqualTo(ORDER_AMOUNT);
        assertThat(document.getExtractedCurrency()).isEqualTo(ORDER_CURRENCY);
        assertThat(document.getProcessingStatus()).isEqualTo(EvidenceProcessingStatus.EXTRACTED);

        assertThat(evidenceReadiness(workspace, order))
                .as("every value this fixture needs was read confidently, so no human step remains")
                .isEqualTo(EvidenceReadinessStatus.EVIDENCE_COMPLETE);

        assertThat(evidenceFindingTypes(workspace, order))
                .as("nothing about this order is wrong")
                .doesNotContain(FindingType.MISSING_REQUIRED_EVIDENCE,
                        FindingType.EVIDENCE_AMOUNT_MISMATCH,
                        FindingType.EVIDENCE_CURRENCY_MISMATCH,
                        FindingType.EVIDENCE_REFERENCE_MISMATCH,
                        FindingType.DUPLICATE_EVIDENCE_DOCUMENT,
                        FindingType.EVIDENCE_UNREADABLE,
                        FindingType.EVIDENCE_EXTRACTION_UNCERTAIN);

        // The four answers are independent, and here they agree: paid, complete, nothing outstanding.
        EvidenceIntelligenceService.OrderStatusSummary summary =
                evidenceService.summary(workspace.getId(), order.getId());
        assertThat(summary.evidenceStatus()).isEqualTo(EvidenceReadinessStatus.EVIDENCE_COMPLETE);
        assertThat(summary.openExceptionCount()).isZero();
        assertThat(summary.recommendedAction()).isNull();
    }

    // 2 ------------------------------------------------------------------------

    @Test
    @DisplayName("a successful payment with a missing invoice is ACTION_REQUIRED while the payment stays CAPTURED")
    void missingInvoiceDoesNotAffectPaymentStatus() {
        Workspace workspace = newWorkspace();
        PaymentOrder order = newOrderWithProviderOrder(workspace, ORDER_AMOUNT);
        simulationService.simulateCapturedPayment(workspace.getId(), order.getId(), false);

        EvidenceIntelligenceService.OrderStatusSummary summary =
                evidenceService.summary(workspace.getId(), order.getId());

        assertThat(summary.providerPaymentStatus())
                .as("the provider confirms the money arrived")
                .isEqualTo(PaymentState.CAPTURED);
        assertThat(summary.evidenceStatus())
                .as("and the paperwork is still missing: the two answers differ, and both are true")
                .isEqualTo(EvidenceReadinessStatus.PENDING_EVIDENCE);
        assertThat(summary.missingDocumentTypes()).contains("COMMERCIAL_INVOICE");
        assertThat(summary.documentsExpected()).isEqualTo(1);
        assertThat(summary.documentsPresent()).isZero();

        // A successful payment is not allowed to make the missing invoice disappear.
        List<PaymentState> paymentStates = paymentRepository.findByOrderId(order.getId()).stream()
                .map(PaymentRecord::getState).toList();
        assertThat(paymentStates).contains(PaymentState.CAPTURED);
    }

    // 3 ------------------------------------------------------------------------

    @Test
    @DisplayName("an invoice total that disagrees with the order is reported with both amounts")
    void invoiceAmountMismatchIsExplained() {
        Workspace workspace = newWorkspace();
        PaymentOrder order = newOrderWithProviderOrder(workspace, ORDER_AMOUNT);
        simulationService.simulateCapturedPayment(workspace.getId(), order.getId(), false);

        uploadEvidence(workspace, order, DocumentType.COMMERCIAL_INVOICE, "invoice-short.pdf",
                EvidenceFixtures.invoicePdfWithMismatchedTotal("INV-2026-0002", order.getInternalReference(),
                        ORDER_CURRENCY, "1400.00"));

        assertThat(evidenceFindingTypes(workspace, order))
                .contains(FindingType.EVIDENCE_AMOUNT_MISMATCH);
        assertThat(evidenceReadiness(workspace, order)).isEqualTo(EvidenceReadinessStatus.ACTION_REQUIRED);

        ExceptionCase exception = exceptionCaseRepository
                .findAllByWorkspaceIdAndOrderIdOrderByCreatedAtDesc(workspace.getId(), order.getId()).stream()
                .filter(candidate -> candidate.getFindingType() == FindingType.EVIDENCE_AMOUNT_MISMATCH)
                .findFirst()
                .orElseThrow();

        assertThat(exception.getSupportingEvidenceJson())
                .as("the operator must be shown both figures, not just told that they differ")
                .contains("140000")
                .contains("150000");
        assertThat(exception.getCertainty().name())
                .as("a value read from a document is not a certainty")
                .isEqualTo("LIKELY");
        assertThat(exception.getTitle().toLowerCase(java.util.Locale.ROOT))
                .as("a total difference is not fraud")
                .doesNotContain("fraud");

        // The recommendation must be a read-only comparison, never a money movement.
        assertThat(recommendationRepository.findByExceptionIdOrderByCreatedAtAsc(exception.getId()))
                .as("an invoice disagreement must never be answered with a refund")
                .noneMatch(recommendation -> recommendation.getCategory() == RecommendationCategory.FINANCIAL);
    }

    // 4 ------------------------------------------------------------------------

    @Test
    @DisplayName("a duplicate webhook delivery produces no duplicate business effect and no evidence finding")
    void duplicateDeliveryHasNoEvidenceEffect() {
        Workspace workspace = newWorkspace();
        PaymentOrder order = newOrderWithProviderOrder(workspace, ORDER_AMOUNT);
        // The same captured event is delivered twice.
        simulationService.simulateCapturedPayment(workspace.getId(), order.getId(), true);

        assertThat(paymentRepository.findByOrderId(order.getId()))
                .as("at-least-once delivery must not create a second payment")
                .hasSize(1);

        EvidenceIntelligenceService.UploadOutcome outcome = uploadEvidence(workspace, order,
                DocumentType.COMMERCIAL_INVOICE, "invoice-0001.pdf",
                EvidenceFixtures.invoicePdf("INV-2026-0001", order.getInternalReference(), ORDER_CURRENCY,
                        "1500.00"));
        assertThat(outcome.extractionFailed()).isFalse();

        // A second assessment must not multiply findings for one order.
        long before = exceptionCaseRepository
                .findAllByWorkspaceIdAndOrderIdOrderByCreatedAtDesc(workspace.getId(), order.getId()).size();
        evidenceService.assess(workspace.getId(), order.getId(), OPERATOR);
        long after = exceptionCaseRepository
                .findAllByWorkspaceIdAndOrderIdOrderByCreatedAtDesc(workspace.getId(), order.getId()).size();
        assertThat(after)
                .as("assessment is idempotent: re-running it must not create a second case per problem")
                .isEqualTo(before);
    }

    // 5 ------------------------------------------------------------------------

    @Test
    @DisplayName("a delayed payment event does not disturb a confirmed final state or the evidence status")
    void outOfOrderEventDoesNotDisturbEvidence() {
        Workspace workspace = newWorkspace();
        PaymentOrder order = newOrderWithProviderOrder(workspace, ORDER_AMOUNT);
        String paymentId = registerProviderPayment(order, ORDER_AMOUNT, PaymentState.CAPTURED);

        deliver(workspace, paymentEventBody("payment.captured", paymentId, order.getProviderOrderId(),
                ORDER_AMOUNT, ORDER_CURRENCY, "captured"), "evt_ooe_captured_" + paymentId);
        deliver(workspace, paymentEventBody("payment.authorized", paymentId, order.getProviderOrderId(),
                ORDER_AMOUNT, ORDER_CURRENCY, "authorized"), "evt_ooe_late_authorized_" + paymentId);

        PaymentRecord payment = paymentRepository
                .findByWorkspaceIdAndProviderPaymentId(workspace.getId(), paymentId).orElseThrow();
        assertThat(payment.getState())
                .as("a late authorisation must not roll a captured payment backwards")
                .isEqualTo(PaymentState.CAPTURED);

        uploadEvidence(workspace, order, DocumentType.COMMERCIAL_INVOICE, "invoice-0001.pdf",
                EvidenceFixtures.invoicePdf("INV-2026-0001", order.getInternalReference(), ORDER_CURRENCY,
                        "1500.00"));

        EvidenceReadinessStatus readiness = evidenceReadiness(workspace, order);
        assertThat(readiness)
                .as("the evidence status is a function of the documents alone")
                .isIn(EvidenceReadinessStatus.EVIDENCE_COMPLETE, EvidenceReadinessStatus.READY_FOR_REVIEW);
    }

    // 6 ------------------------------------------------------------------------

    @Test
    @DisplayName("a refund or partial-payment disagreement changes nothing about evidence expectations")
    void refundDisagreementDoesNotChangeEvidence() {
        Workspace workspace = newWorkspace();
        PaymentOrder order = newOrderWithProviderOrder(workspace, ORDER_AMOUNT);
        simulationService.simulateCapturedPayment(workspace.getId(), order.getId(), false);
        uploadEvidence(workspace, order, DocumentType.COMMERCIAL_INVOICE, "invoice-0001.pdf",
                EvidenceFixtures.invoicePdf("INV-2026-0001", order.getInternalReference(), ORDER_CURRENCY,
                        "1500.00"));
        EvidenceReadinessStatus before = evidenceReadiness(workspace, order);

        simulationService.injectDiscrepancy(workspace.getId(), order.getId(),
                com.aegisx.integrity.support.IntegrityScenarioFixtures.DiscrepancyKind.REFUND_DISCREPANCY);
        reconcile(workspace);

        assertThat(exceptionCaseRepository.findAllByWorkspaceIdOrderByCreatedAtDesc(workspace.getId()))
                .as("the refund disagreement must be raised on the payment side")
                .anyMatch(exception -> exception.getFindingType() == FindingType.REFUND_DISCREPANCY);
        assertThat(evidenceReadiness(workspace, order))
                .as("a refund does not make an invoice more or less complete")
                .isEqualTo(before);
    }

    // 7 ------------------------------------------------------------------------

    @Test
    @DisplayName("identical bytes are refused as a duplicate, and a corrected document supersedes rather than deletes")
    void duplicateUploadRefusedAndReplacementSupersedes() {
        Workspace workspace = newWorkspace();
        PaymentOrder order = newOrderWithProviderOrder(workspace, ORDER_AMOUNT);

        byte[] firstBytes = EvidenceFixtures.invoicePdf("INV-2026-0001", order.getInternalReference(),
                ORDER_CURRENCY, "1500.00");
        EvidenceIntelligenceService.UploadOutcome first = uploadEvidence(workspace, order,
                DocumentType.COMMERCIAL_INVOICE, "invoice-0001.pdf", firstBytes);
        assertThat(first.supersededPrevious()).isFalse();

        // 7a: the exact same bytes again.
        assertThatThrownBy(() -> uploadEvidence(workspace, order, DocumentType.COMMERCIAL_INVOICE,
                "invoice-0001-copy.pdf", firstBytes))
                .as("a byte-identical re-upload must be refused, not silently accepted")
                .hasMessageContaining("same bytes");

        // 7b: a genuinely different document of the same type.
        EvidenceIntelligenceService.UploadOutcome replacement = uploadEvidence(workspace, order,
                DocumentType.COMMERCIAL_INVOICE, "invoice-0001-revised.pdf",
                EvidenceFixtures.invoicePdf("INV-2026-0001R", order.getInternalReference(), ORDER_CURRENCY,
                        "1500.00"));

        assertThat(replacement.supersededPrevious())
                .as("the revised invoice must be recorded as replacing the original")
                .isTrue();
        assertThat(replacement.supersededDocumentId()).isEqualTo(first.document().getId());

        List<EvidenceDocument> current = evidenceService.documents(workspace.getId(), order.getId(), false);
        List<EvidenceDocument> history = evidenceService.documents(workspace.getId(), order.getId(), true);
        assertThat(current)
                .as("only the revised invoice is current")
                .hasSize(1)
                .allMatch(EvidenceDocument::isActive);
        assertThat(history)
                .as("the superseded invoice is retained, so the replacement is visible rather than hidden")
                .hasSize(2)
                .anyMatch(document -> document.getSupersededByDocumentId() != null);
    }

    @Test
    @DisplayName("a database transaction rollback removes the uploaded evidence file")
    void rolledBackEvidenceUploadRemovesStoredFile() {
        Workspace workspace = newWorkspace();
        PaymentOrder order = newOrderWithProviderOrder(workspace, ORDER_AMOUNT);
        byte[] invoice = EvidenceFixtures.invoicePdf("INV-ROLLBACK", order.getInternalReference(),
                ORDER_CURRENCY, "1500.00");
        EvidenceDocument[] attemptedDocument = new EvidenceDocument[1];

        new TransactionTemplate(transactionManager).execute(status -> {
            attemptedDocument[0] = evidenceService.receiveAndProcess(workspace.getId(), order.getId(),
                    DocumentType.COMMERCIAL_INVOICE,
                    new MockMultipartFile("file", "invoice-rollback.pdf", "application/pdf", invoice),
                    OPERATOR).document();
            status.setRollbackOnly();
            return null;
        });

        assertThat(attemptedDocument[0]).isNotNull();
        assertThat(evidenceDocumentRepository.findById(attemptedDocument[0].getId()))
                .as("the database row must roll back")
                .isEmpty();
        assertThat(storageService.exists(attemptedDocument[0].getStoragePath()))
                .as("the file written before the database rollback must be removed")
                .isFalse();
    }

    @Test
    @DisplayName("an upload over the configured size limit is rejected before writing a row or file")
    void uploadOverConfiguredLimitIsRejectedBeforeStorage() {
        Workspace workspace = newWorkspace();
        PaymentOrder order = newOrderWithProviderOrder(workspace, ORDER_AMOUNT);
        int oversizedLength = Math.toIntExact(properties.getEvidence().getMaxDocumentBytes() + 1);
        byte[] oversized = new byte[oversizedLength];
        MockMultipartFile file = new MockMultipartFile("file", "oversized.pdf", "application/pdf", oversized);

        assertThatThrownBy(() -> evidenceDocumentService.receive(workspace.getId(), order.getId(),
                DocumentType.COMMERCIAL_INVOICE, file, "oversized.pdf", oversized,
                OPERATOR.label(), OPERATOR.userId(), false))
                .isInstanceOf(IntegrityConflictException.class)
                .hasMessageContaining("exceeds the configured limit");

        assertThat(evidenceDocumentRepository.findAllByOrderIdOrderByCreatedAtDesc(order.getId()))
                .as("an oversized upload must not create a row")
                .isEmpty();
    }

    // 8 ------------------------------------------------------------------------

    @Test
    @DisplayName("a document that names another order is reported as a reference mismatch, not accepted silently")
    void documentForAnotherOrderIsReported() {
        Workspace workspace = newWorkspace();
        PaymentOrder order = newOrderWithProviderOrder(workspace, ORDER_AMOUNT);

        uploadEvidence(workspace, order, DocumentType.COMMERCIAL_INVOICE, "invoice-other.pdf",
                EvidenceFixtures.invoicePdf("INV-2026-0009", "ORD-DIFFERENT-0001", ORDER_CURRENCY, "1500.00"));

        assertThat(evidenceFindingTypes(workspace, order))
                .contains(FindingType.EVIDENCE_REFERENCE_MISMATCH);

        ExceptionCase exception = exceptionCaseRepository
                .findAllByWorkspaceIdAndOrderIdOrderByCreatedAtDesc(workspace.getId(), order.getId()).stream()
                .filter(candidate -> candidate.getFindingType() == FindingType.EVIDENCE_REFERENCE_MISMATCH)
                .findFirst()
                .orElseThrow();
        assertThat(exception.getCertainty().name())
                .as("a supplier may use its own numbering, so this is a reason to look rather than a finding of fact")
                .isEqualTo("UNCERTAIN");
        assertThat(exception.getDiagnosis()).contains("ORD-DIFFERENT-0001");
    }

    // 9 ------------------------------------------------------------------------

    @Test
    @DisplayName("an unreadable document is recorded as FAILED, not treated as a missing document")
    void unreadableDocumentIsFailedNotMissing() {
        Workspace workspace = newWorkspace();
        PaymentOrder order = newOrderWithProviderOrder(workspace, ORDER_AMOUNT);

        EvidenceIntelligenceService.UploadOutcome outcome = uploadEvidence(workspace, order,
                DocumentType.COMMERCIAL_INVOICE, "invoice-corrupt.pdf", EvidenceFixtures.corruptPdf());

        assertThat(outcome.extractionFailed())
                .as("a corrupt PDF must be reported as unreadable")
                .isTrue();
        assertThat(outcome.document().getProcessingStatus()).isEqualTo(EvidenceProcessingStatus.FAILED);
        assertThat(outcome.document().getProcessingError())
                .as("the operator must be told why, in plain language")
                .isNotBlank();

        assertThat(evidenceReadiness(workspace, order))
                .as("readiness must say the document could not be read")
                .isEqualTo(EvidenceReadinessStatus.PROCESSING_FAILED);

        assertThat(evidenceFindingTypes(workspace, order))
                .as("the document is unreadable, which is not the same as absent")
                .contains(FindingType.EVIDENCE_UNREADABLE)
                .doesNotContain(FindingType.MISSING_REQUIRED_EVIDENCE);

        // A failed attempt is still part of the history.
        assertThat(evidenceService.extractionHistory(workspace.getId(), outcome.document().getId()))
                .isNotEmpty()
                .allMatch(extraction -> extraction.getFailureReason() != null);

        // Replacing it with a legible copy resolves the state.
        EvidenceIntelligenceService.UploadOutcome replacement = uploadEvidence(workspace, order,
                DocumentType.COMMERCIAL_INVOICE, "invoice-good.pdf",
                EvidenceFixtures.invoicePdf("INV-2026-0001", order.getInternalReference(), ORDER_CURRENCY,
                        "1500.00"));
        assertThat(replacement.extractionFailed()).isFalse();
        assertThat(evidenceReadiness(workspace, order))
                .as("a readable replacement clears the failure")
                .isNotEqualTo(EvidenceReadinessStatus.PROCESSING_FAILED);
    }

    // 10 -----------------------------------------------------------------------

    @Test
    @DisplayName("a document belonging to another workspace is not reachable, readable or assessable")
    void crossWorkspaceAccessIsRefused() {
        Workspace owner = newWorkspace();
        PaymentOrder ownerOrder = newOrderWithProviderOrder(owner, ORDER_AMOUNT);
        EvidenceIntelligenceService.UploadOutcome uploaded = uploadEvidence(owner, ownerOrder,
                DocumentType.COMMERCIAL_INVOICE, "invoice-0001.pdf",
                EvidenceFixtures.invoicePdf("INV-2026-0001", ownerOrder.getInternalReference(),
                        ORDER_CURRENCY, "1500.00"));

        Workspace intruder = newWorkspace();

        assertThatThrownBy(() -> evidenceService.documents(intruder.getId(), ownerOrder.getId(), false))
                .as("an order must not be visible from another workspace")
                .isInstanceOf(IntegrityNotFoundException.class);
        assertThatThrownBy(() -> evidenceService.extractionHistory(intruder.getId(), uploaded.document().getId()))
                .as("a document must not be readable from another workspace")
                .isInstanceOf(IntegrityNotFoundException.class);
        assertThatThrownBy(() -> evidenceService.summary(intruder.getId(), ownerOrder.getId()))
                .as("the four-status view must not be available across workspaces")
                .isInstanceOf(IntegrityNotFoundException.class);
        assertThat(documentRepository_evidence(intruder.getId()))
                .as("and nothing was created in the intruder's workspace as a side effect")
                .isEmpty();
    }

    private List<EvidenceDocument> documentRepository_evidence(java.util.UUID workspaceId) {
        return evidenceDocumentRepository.findAllByWorkspaceIdOrderByCreatedAtDesc(workspaceId);
    }

    // 11 -----------------------------------------------------------------------

    @Test
    @DisplayName("an evidence exception can be resolved, and re-assessment does not silently reopen it once fixed")
    void evidenceResolutionIsRecordedAndNotReopenedWhenFixed() {
        Workspace workspace = newWorkspace();
        PaymentOrder order = newOrderWithProviderOrder(workspace, ORDER_AMOUNT);

        // Start with nothing, so the missing invoice is raised.
        evidenceService.assess(workspace.getId(), order.getId(), OPERATOR);
        ExceptionCase missing = exceptionCaseRepository
                .findAllByWorkspaceIdAndOrderIdOrderByCreatedAtDesc(workspace.getId(), order.getId()).stream()
                .filter(candidate -> candidate.getFindingType() == FindingType.MISSING_REQUIRED_EVIDENCE)
                .findFirst()
                .orElseThrow();

        assertThat(recommendationRepository.findByExceptionIdOrderByCreatedAtAsc(missing.getId()))
                .as("a missing document is never answered with an automatic financial action")
                .noneMatch(recommendation -> recommendation.getCategory() == RecommendationCategory.FINANCIAL)
                .anyMatch(recommendation -> recommendation.getCategory()
                        == RecommendationCategory.HUMAN_APPROVAL_REQUIRED);

        exceptionService.openCase(workspace.getId(), missing.getId(), OPERATOR, "Chasing the supplier");
        exceptionService.addNote(workspace.getId(), missing.getId(), OPERATOR,
                "Asked the merchant to send the invoice");
        exceptionService.resolve(workspace.getId(), missing.getId(), OPERATOR,
                "Invoice received and uploaded", "{\"documentType\":\"COMMERCIAL_INVOICE\"}");

        assertThat(exceptionCaseRepository.findByWorkspaceIdAndDedupeKey(workspace.getId(), missing.getDedupeKey()))
                .get()
                .extracting(ExceptionCase::getStatus)
                .isEqualTo(ExceptionStatus.RESOLVED);

        // Now actually supply the document, then re-assess. The resolution must stand.
        uploadEvidence(workspace, order, DocumentType.COMMERCIAL_INVOICE, "invoice-0001.pdf",
                EvidenceFixtures.invoicePdf("INV-2026-0001", order.getInternalReference(), ORDER_CURRENCY,
                        "1500.00"));

        ExceptionCase afterFix = exceptionCaseRepository
                .findByWorkspaceIdAndDedupeKey(workspace.getId(), missing.getDedupeKey()).orElseThrow();
        assertThat(afterFix.getStatus())
                .as("once the document exists, the recorded resolution must survive re-assessment")
                .isEqualTo(ExceptionStatus.RESOLVED);
        assertThat(evidenceReadiness(workspace, order)).isEqualTo(EvidenceReadinessStatus.EVIDENCE_COMPLETE);
    }

    // 12 -----------------------------------------------------------------------

    @Test
    @DisplayName("a provider outage and a document-processing failure are both reported, and neither is hidden")
    void providerOutageAndDocumentFailureAreReported() {
        Workspace workspace = newWorkspace();
        PaymentOrder order = newOrderWithProviderOrder(workspace, ORDER_AMOUNT);
        simulationService.simulateCapturedPayment(workspace.getId(), order.getId(), false);

        uploadEvidence(workspace, order, DocumentType.COMMERCIAL_INVOICE, "invoice-corrupt.pdf",
                EvidenceFixtures.corruptPdf());

        simulatedProvider.setReachable(false);
        try {
            var providerRun = reconcile(workspace);
            assertThat(providerRun.providerReachable())
                    .as("a provider outage must be reported, not silently ignored")
                    .isFalse();

            // An evidence assessment never calls the provider, so a provider outage must not stop it.
            var assessment = evidenceService.assess(workspace.getId(), order.getId(), OPERATOR);
            assertThat(assessment.findingsCount())
                    .as("the evidence dimension must keep working during a provider outage")
                    .isPositive();
        } finally {
            simulatedProvider.setReachable(true);
        }

        assertThat(evidenceFindingTypes(workspace, order)).contains(FindingType.EVIDENCE_UNREADABLE);
        assertThat(evidenceReadiness(workspace, order)).isEqualTo(EvidenceReadinessStatus.PROCESSING_FAILED);
    }

    // extra --------------------------------------------------------------------

    @Test
    @DisplayName("an unsupported file type and an empty upload are both refused with a plain reason")
    void invalidUploadsAreRefused() {
        Workspace workspace = newWorkspace();
        PaymentOrder order = newOrderWithProviderOrder(workspace, ORDER_AMOUNT);

        assertThatThrownBy(() -> uploadEvidence(workspace, order, DocumentType.COMMERCIAL_INVOICE,
                "archive.zip", EvidenceFixtures.unsupportedArchive()))
                .as("the MIME allow-list is enforced against the bytes, not the filename")
                .hasMessageContaining("Unsupported document type");

        assertThatThrownBy(() -> uploadEvidence(workspace, order, DocumentType.COMMERCIAL_INVOICE,
                "empty.pdf", EvidenceFixtures.empty()))
                .as("an empty upload must be refused")
                .hasMessageContaining("empty");

        assertThat(documentRepository_evidence(workspace.getId()))
                .as("a refused upload must leave nothing behind")
                .isEmpty();
    }

    @Test
    @DisplayName("the extraction checks are deterministic and their uncertainty is explicit")
    void checksAreDeterministicAndHonest() {
        EvidenceCheckService.Input input = new EvidenceCheckService.Input(
                new EvidenceCheckService.OrderFacts(java.util.UUID.randomUUID(), "ORD-TEST-1",
                        "order_SIMTEST", 250_000L, "INR"),
                List.of(new EvidenceCheckService.DocumentFacts(
                        java.util.UUID.randomUUID(), "SHIPPING_BILL", EvidenceProcessingStatus.EXTRACTED,
                        null, null, null, null, null, List.of(), List.of(), "abc123", null)),
                List.of("COMMERCIAL_INVOICE", "SHIPPING_BILL"),
                0L);

        List<com.aegisx.integrity.reconciliation.model.FindingCandidate> first =
                evidenceCheckService.evaluate(input);
        List<com.aegisx.integrity.reconciliation.model.FindingCandidate> second =
                evidenceCheckService.evaluate(input);

        assertThat(first).as("the same facts must always produce the same findings").isEqualTo(second);
        assertThat(first)
                .as("a shipping bill is no substitute for a commercial invoice")
                .anyMatch(candidate -> candidate.type() == FindingType.MISSING_REQUIRED_EVIDENCE);
        assertThat(first)
                .as("a document with no total cannot be reported as an amount mismatch")
                .noneMatch(candidate -> candidate.type() == FindingType.EVIDENCE_AMOUNT_MISMATCH);
        assertThat(first)
                .allSatisfy(candidate -> assertThat(candidate.certainty())
                        .as("every finding states its own certainty")
                        .isNotNull());
    }

    @Test
    @DisplayName("the extraction is published per field, with the uncertain ones named")
    void perFieldConfidenceIsPublished() {
        var result = evidenceFieldExtractor.extract(
                "COMMERCIAL INVOICE\nInvoice Number: INV-77\nTotal Amount Due: INR 999.00\n",
                70.0);

        assertThat(result.stringValue(com.aegisx.integrity.evidence.EvidenceFieldExtractor
                .FIELD_INVOICE_NUMBER)).contains("INV-77");
        assertThat(result.amountMinor(com.aegisx.integrity.evidence.EvidenceFieldExtractor.FIELD_TOTAL))
                .contains(99_900L);
        assertThat(result.stringValue(com.aegisx.integrity.evidence.EvidenceFieldExtractor.FIELD_CURRENCY))
                .contains("INR");
        assertThat(result.fieldConfidence().get(com.aegisx.integrity.evidence.EvidenceFieldExtractor
                .FIELD_TOTAL)).isGreaterThan(0.0);
        assertThat(result.uncertainFields())
                .as("a field that could not be found must not be presented as uncertain-but-present")
                .doesNotContain(com.aegisx.integrity.evidence.EvidenceFieldExtractor.FIELD_INVOICE_NUMBER);
        assertThat(result.absentFields())
                .as("the document states no order reference, so that must be reported as absent")
                .contains(com.aegisx.integrity.evidence.EvidenceFieldExtractor.FIELD_ORDER_REFERENCE);
    }

    @Test
    @DisplayName("an operator correction is recorded as a distinct extraction and never rewrites the machine reading")
    void correctionIsAppendedNotRewritten() {
        Workspace workspace = newWorkspace();
        PaymentOrder order = newOrderWithProviderOrder(workspace, ORDER_AMOUNT);
        EvidenceIntelligenceService.UploadOutcome outcome = uploadEvidence(workspace, order,
                DocumentType.COMMERCIAL_INVOICE, "invoice-0001.pdf",
                EvidenceFixtures.invoicePdf("INV-2026-0001", order.getInternalReference(), ORDER_CURRENCY,
                        "1500.00"));

        long beforeCount = evidenceService
                .extractionHistory(workspace.getId(), outcome.document().getId()).size();

        evidenceService.correct(workspace.getId(), outcome.document().getId(),
                com.aegisx.integrity.evidence.EvidenceFieldExtractor.FIELD_INVOICE_NUMBER,
                "INV-CORRECTED", OPERATOR);

        List<EvidenceExtraction> history =
                evidenceService.extractionHistory(workspace.getId(), outcome.document().getId());
        assertThat(history)
                .as("a correction is appended, so the history grows rather than being edited")
                .hasSize((int) beforeCount + 1);
        assertThat(history.get(0).isHumanCorrection())
                .as("the machine's original reading is still the first entry and is still unmarked")
                .isFalse();
        assertThat(history.get(history.size() - 1).isHumanCorrection()).isTrue();
        assertThat(history.get(history.size() - 1).getExtractionMethod().name()).isEqualTo("MANUAL_CORRECTION");

        EvidenceDocument document = evidenceDocumentRepository
                .findByIdAndWorkspaceId(outcome.document().getId(), workspace.getId()).orElseThrow();
        assertThat(document.getExtractedInvoiceNumber()).isEqualTo("INV-CORRECTED");
        assertThat(document.isFieldsConfirmed()).isTrue();
    }

    @Test
    @DisplayName("a superseded document may not be corrected, because the correction would apply to the wrong file")
    void supersededDocumentCannotBeCorrected() {
        Workspace workspace = newWorkspace();
        PaymentOrder order = newOrderWithProviderOrder(workspace, ORDER_AMOUNT);
        EvidenceIntelligenceService.UploadOutcome original = uploadEvidence(workspace, order,
                DocumentType.COMMERCIAL_INVOICE, "invoice-v1.pdf",
                EvidenceFixtures.invoicePdf("INV-A", order.getInternalReference(), ORDER_CURRENCY, "1500.00"));
        uploadEvidence(workspace, order, DocumentType.COMMERCIAL_INVOICE, "invoice-v2.pdf",
                EvidenceFixtures.invoicePdf("INV-B", order.getInternalReference(), ORDER_CURRENCY, "1500.00"));

        assertThatThrownBy(() -> evidenceService.correct(workspace.getId(), original.document().getId(),
                com.aegisx.integrity.evidence.EvidenceFieldExtractor.FIELD_INVOICE_NUMBER, "INV-X", OPERATOR))
                .hasMessageContaining("superseded");
    }

    @Test
    @DisplayName("a recommendation for an evidence finding is never executable without a person")
    void evidenceRecommendationsAreNeverAutomatic() {
        Workspace workspace = newWorkspace();
        PaymentOrder order = newOrderWithProviderOrder(workspace, ORDER_AMOUNT);
        uploadEvidence(workspace, order, DocumentType.COMMERCIAL_INVOICE, "invoice-short.pdf",
                EvidenceFixtures.invoicePdfWithMismatchedTotal("INV-2026-0002", order.getInternalReference(),
                        ORDER_CURRENCY, "1400.00"));

        var recommendations = recommendationRepository.findAllByWorkspaceIdOrderByCreatedAtDesc(workspace.getId());
        assertThat(recommendations).isNotEmpty();
        assertThat(recommendations)
                .allSatisfy(recommendation -> {
                    assertThat(recommendation.getStatus()).isEqualTo(RecommendationStatus.PROPOSED);
                    if (recommendation.getCategory() != RecommendationCategory.READ_ONLY) {
                        assertThat(recommendation.isRequiresHumanApproval())
                                .as("only read-only advice may be acted on without a person")
                                .isTrue();
                    }
                });
    }
}
