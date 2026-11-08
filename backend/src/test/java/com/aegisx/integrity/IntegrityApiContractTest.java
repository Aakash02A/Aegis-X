package com.aegisx.integrity;

import com.aegisx.auth.entity.Role;
import com.aegisx.auth.entity.User;
import com.aegisx.auth.repository.UserRepository;
import com.aegisx.integrity.entity.EvidenceDocument;
import com.aegisx.integrity.entity.PaymentOrder;
import com.aegisx.integrity.entity.Workspace;
import com.aegisx.integrity.support.IntegrityScenarioFixtures;
import com.aegisx.integrity.support.IntegrityTestSupport;
import com.aegisx.integrity.domain.WorkspaceMemberRole;
import com.aegisx.integrity.service.WorkspaceMembershipService;
import com.aegisx.storage.config.StorageProperties;
import com.aegisx.storage.service.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract test for the REST surface the integrity console depends on.
 *
 * <p>The console is a static front end that calls these exact paths and query parameters. Without
 * this test, a renamed mapping or a changed parameter name would break the UI silently, because the
 * service-level tests never touch the web layer. Each request below mirrors a call in
 * {@code frontend/js/integrity.js}.</p>
 *
 * <p>The webhook endpoint is also exercised here because it is the only integrity endpoint that is
 * reachable without a bearer token: it must still reject an unsigned delivery and persist nothing.</p>
 */
@DisplayName("Integrity API contract")
@AutoConfigureMockMvc
class IntegrityApiContractTest extends IntegrityTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceMembershipService membershipService;
    @Autowired private StorageProperties storageProperties;
    @Autowired private StorageService storageService;

    private User operator;

    @BeforeEach
    void ensureOperatorUser() {
        operator = userRepository.findByEmail("operator@aegis-x.test")
                .orElseGet(() -> userRepository.save(User.builder()
                        .fullName("Integrity API Contract Operator")
                        .email("operator@aegis-x.test")
                        .passwordHash("not-used-by-mock-authentication")
                        .role(Role.ADMIN)
                        .build()));
    }

    private Workspace newAuthorizedWorkspace() {
        Workspace workspace = newWorkspace();
        membershipService.addMember(operator.getId(), workspace.getId(), WorkspaceMemberRole.OWNER);
        return workspace;
    }

    @Test
    @DisplayName("the console's read endpoints all answer with the documented envelope")
    @WithMockUser(username = "operator@aegis-x.test", roles = "ADMIN")
    void readEndpointsAnswer() throws Exception {
        Workspace workspace = newAuthorizedWorkspace();
        PaymentOrder order = newOrderWithProviderOrder(workspace, 100_000);
        simulationService.injectDiscrepancy(workspace.getId(), order.getId(),
                IntegrityScenarioFixtures.DiscrepancyKind.AMOUNT_MISMATCH);
        reconcile(workspace);
        String base = "/api/integrity/workspaces/" + workspace.getId();

        // Workspace resolution first, because the console calls it before it renders anything.
        mockMvc.perform(get("/api/integrity/workspaces/default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").exists());

        for (String endpoint : new String[]{
                "/dashboard", "/orders", "/payments", "/refunds", "/transfers", "/webhooks",
                "/reconciliation/runs", "/reconciliation/methods", "/reconciliation/findings?limit=10",
                "/exceptions?status=OPEN", "/recommendations", "/audit?page=0&size=10"}) {
            mockMvc.perform(get(base + endpoint))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }

        mockMvc.perform(get(base + "/orders/" + order.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.order.internalReference").exists())
                .andExpect(jsonPath("$.data.transitions").isArray());

        mockMvc.perform(get("/api/integrity/benchmark/method"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.note").exists());
    }

    @Test
    @DisplayName("integrity audit events cannot be edited or deleted through HTTP")
    @WithMockUser(username = "operator@aegis-x.test", roles = "ADMIN")
    void auditRowsAreReadOnlyOverHttp() throws Exception {
        Workspace workspace = newAuthorizedWorkspace();
        newOrderWithProviderOrder(workspace, 100_000);
        String base = "/api/integrity/workspaces/" + workspace.getId() + "/audit";
        var event = auditEventRepository.findByWorkspaceIdOrderByOccurredAtDesc(workspace.getId(),
                        org.springframework.data.domain.PageRequest.of(0, 1))
                .getContent().get(0);
        long originalCount = auditEventRepository.countByWorkspaceId(workspace.getId());
        String originalType = event.getEventType();
        String originalActor = event.getActorLabel();
        var originalOccurredAt = event.getOccurredAt();

        mockMvc.perform(post(base).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"FORGED\",\"actorLabel\":\"attacker\"}"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(put(base + "/" + event.getId()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"FORGED\",\"actorLabel\":\"attacker\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete(base + "/" + event.getId()))
                .andExpect(status().isNotFound());

        var unchanged = auditEventRepository.findById(event.getId()).orElseThrow();
        assertThat(auditEventRepository.countByWorkspaceId(workspace.getId())).isEqualTo(originalCount);
        assertThat(unchanged.getEventType()).isEqualTo(originalType);
        assertThat(unchanged.getActorLabel()).isEqualTo(originalActor);
        assertThat(unchanged.getOccurredAt()).isEqualTo(originalOccurredAt);
    }

    @Test
    @DisplayName("the console's evidence endpoints all answer with the documented envelope")
    @WithMockUser(username = "operator@aegis-x.test", roles = "ADMIN")
    void evidenceEndpointsAnswer() throws Exception {
        Workspace workspace = newAuthorizedWorkspace();
        PaymentOrder order = newOrderWithProviderOrder(workspace, 150_000);
        // A confirmed payment with incomplete paperwork is the case the console exists for: the
        // summary must report a confirmed payment and an incomplete evidence status side by side.
        simulationService.simulateCapturedPayment(workspace.getId(), order.getId(), false);
        String base = "/api/integrity/workspaces/" + workspace.getId() + "/evidence";

        // The separation the product exists for: the provider has confirmed the money, and the
        // paperwork for it has not arrived. Both are true at the same time, in different fields.
        mockMvc.perform(get(base + "/summary").param("orderId", order.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.providerPaymentStatus").value("CAPTURED"))
                .andExpect(jsonPath("$.data.internalPaymentStatus").value("CAPTURED"))
                .andExpect(jsonPath("$.data.evidenceStatus").value("PENDING_EVIDENCE"))
                .andExpect(jsonPath("$.data.documentsPresent").value(0))
                .andExpect(jsonPath("$.data.documentsExpected").value(1));

        // The requirement list must explain itself, not just list document types.
        mockMvc.perform(get(base + "/requirements").param("orderId", order.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.required").isArray())
                .andExpect(jsonPath("$.data.rationale").isNotEmpty());

        mockMvc.perform(get(base + "/model"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.limitation").isNotEmpty());

        // Upload a real PDF through the multipart endpoint the console uses.
        byte[] invoiceBytes = com.aegisx.integrity.support.EvidenceFixtures.invoicePdf(
                "INV-CONTRACT-1", order.getInternalReference(), "INR", "1500.00");
        String uploaded = mockMvc.perform(multipart(base + "/documents")
                        .file(new org.springframework.mock.web.MockMultipartFile("file", "../../invoice-0001.pdf",
                                "application/pdf", invoiceBytes))
                        .param("orderId", order.getId().toString())
                        .param("documentType", "COMMERCIAL_INVOICE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.extractionFailed").value(false))
                .andExpect(jsonPath("$.data.document.originalFilename").value("invoice-0001.pdf"))
                .andReturn().getResponse().getContentAsString();
        String documentId = com.jayway.jsonpath.JsonPath.read(uploaded, "$.data.document.id");
        EvidenceDocument storedDocument = evidenceDocumentRepository.findById(UUID.fromString(documentId))
                .orElseThrow();
        java.nio.file.Path storageRoot = java.nio.file.Path.of(storageProperties.getRootPath())
                .toAbsolutePath().normalize();
        java.nio.file.Path storedPath = java.nio.file.Path.of(storedDocument.getStoragePath())
                .toAbsolutePath().normalize();
        assertThat(storedPath)
                .as("the multipart filename must not move the stored file outside the configured root")
                .startsWith(storageRoot);
        assertThat(storageService.exists(storedDocument.getStoragePath())).isTrue();
        assertThat(storedPath.getFileName().toString()).doesNotContain("..");

        mockMvc.perform(get(base + "/documents").param("orderId", order.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(documentId));

        mockMvc.perform(get(base + "/documents/" + documentId + "/extractions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());

        mockMvc.perform(get(base + "/documents/" + documentId + "/content"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .contentType(MediaType.APPLICATION_PDF))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .bytes(invoiceBytes))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Content-Disposition", org.hamcrest.Matchers.containsString("invoice-0001.pdf")));

        mockMvc.perform(post(base + "/documents/" + documentId + "/corrections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"field\":\"invoiceNumber\",\"value\":\"INV-CONFIRMED\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post(base + "/assess").param("orderId", order.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runId").exists());

        mockMvc.perform(get(base + "/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());

        mockMvc.perform(get(base + "/summary").param("orderId", order.getId().toString()))
                .andExpect(status().isOk())
                // The four answers are separate fields, and the evidence one is not the payment one.
                .andExpect(jsonPath("$.data.providerPaymentStatus").value("CAPTURED"))
                .andExpect(jsonPath("$.data.evidenceStatus").exists())
                .andExpect(jsonPath("$.data.exceptionStatus").exists())
                .andExpect(jsonPath("$.data.evidenceReason").isNotEmpty())
                // The document that arrived is reflected in the evidence side, and the payment side
                // is unchanged by it: a document upload cannot restate a provider-confirmed payment.
                .andExpect(jsonPath("$.data.documentsPresent").value(1))
                .andExpect(jsonPath("$.data.documentsExpected").value(1));
    }

    @Test
    @DisplayName("an upload whose bytes contradict its claimed PDF type is rejected before storage")
    @WithMockUser(username = "operator@aegis-x.test", roles = "ADMIN")
    void evidenceUploadRejectsSpoofedPdfContentType() throws Exception {
        Workspace workspace = newAuthorizedWorkspace();
        PaymentOrder order = newOrderWithProviderOrder(workspace, 150_000);
        String base = "/api/integrity/workspaces/" + workspace.getId() + "/evidence";

        mockMvc.perform(multipart(base + "/documents")
                        .file(new org.springframework.mock.web.MockMultipartFile("file", "invoice.pdf",
                                "application/pdf", "plain text, not a PDF".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        .param("orderId", order.getId().toString())
                        .param("documentType", "COMMERCIAL_INVOICE"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false));

        assertThat(evidenceDocumentRepository.findAllByOrderIdOrderByCreatedAtDesc(order.getId()))
                .as("a rejected content type must not create a database row")
                .isEmpty();
    }

    @Test
    @DisplayName("an evidence document belonging to another workspace cannot be read over HTTP")
    @WithMockUser(username = "operator@aegis-x.test", roles = "ADMIN")
    void crossWorkspaceEvidenceAccessIsRefusedOverHttp() throws Exception {
        Workspace owner = newAuthorizedWorkspace();
        PaymentOrder order = newOrderWithProviderOrder(owner, 150_000);
        String ownerBase = "/api/integrity/workspaces/" + owner.getId() + "/evidence";

        String uploaded = mockMvc.perform(multipart(ownerBase + "/documents")
                        .file(new org.springframework.mock.web.MockMultipartFile("file", "invoice.pdf",
                                "application/pdf", com.aegisx.integrity.support.EvidenceFixtures.invoicePdf(
                                        "INV-OWNED", order.getInternalReference(), "INR", "1500.00")))
                        .param("orderId", order.getId().toString())
                        .param("documentType", "COMMERCIAL_INVOICE"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String documentId = com.jayway.jsonpath.JsonPath.read(uploaded, "$.data.document.id");

        Workspace intruder = newWorkspace();
        String intruderBase = "/api/integrity/workspaces/" + intruder.getId() + "/evidence";

        // The document id is a valid UUID and the caller is a fully authorised operator. What makes
        // this a refusal is the workspace scoping, not the role.
        mockMvc.perform(get(intruderBase + "/documents/" + documentId + "/content"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(intruderBase + "/documents/" + documentId + "/extractions"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(intruderBase + "/documents").param("orderId", order.getId().toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the console's mutation endpoints accept the documented bodies")
    @WithMockUser(username = "operator@aegis-x.test", roles = "ADMIN")
    void mutationEndpointsAnswer() throws Exception {
        Workspace workspace = newAuthorizedWorkspace();
        String base = "/api/integrity/workspaces/" + workspace.getId();

        String created = mockMvc.perform(post(base + "/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\":150000,\"currency\":\"INR\",\"description\":\"contract test\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.providerOrderId").exists())
                .andReturn().getResponse().getContentAsString();
        String orderId = com.jayway.jsonpath.JsonPath.read(created, "$.data.orderId");

        simulationService.injectDiscrepancy(workspace.getId(), UUID.fromString(orderId),
                IntegrityScenarioFixtures.DiscrepancyKind.AMOUNT_MISMATCH);

        mockMvc.perform(post(base + "/reconciliation/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"strategy\":\"PROPOSED_EVIDENCE_BASED\",\"refreshProviderState\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runId").exists())
                .andExpect(jsonPath("$.data.findingsCount").isNumber());

        var queue = exceptionCaseRepository.findAllByWorkspaceIdOrderByCreatedAtDesc(workspace.getId());
        assertThat(queue).as("the injected fault must have produced a case").isNotEmpty();
        String exceptionId = queue.get(0).getId().toString();

        mockMvc.perform(get(base + "/exceptions/" + exceptionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.exceptionCase.id").value(exceptionId));

        mockMvc.perform(post(base + "/exceptions/" + exceptionId + "/open")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"investigating\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_REVIEW"));

        mockMvc.perform(post(base + "/exceptions/" + exceptionId + "/notes")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"checked the provider\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post(base + "/exceptions/" + exceptionId + "/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"summary\":\"provider record corrected\",\"evidence\":\"{}\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RESOLVED"));

        // A safety-critical contract: approving a money-moving action must be refused, not ignored.
        var financial = recommendationRepository.findAllByWorkspaceIdOrderByCreatedAtDesc(workspace.getId())
                .stream()
                .filter(recommendation -> recommendation.getCategory().name().equals("FINANCIAL"))
                .findFirst();
        if (financial.isPresent()) {
            mockMvc.perform(post(base + "/recommendations/" + financial.get().getId() + "/approve")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"rationale\":\"contract test\"}"))
                    .andExpect(status().is4xxClientError());
        } else {
            var readOnly = recommendationRepository.findAllByWorkspaceIdOrderByCreatedAtDesc(workspace.getId())
                    .stream()
                    .filter(recommendation -> recommendation.getCategory().name().equals("READ_ONLY"))
                    .findFirst()
                    .orElseThrow();
            mockMvc.perform(post(base + "/recommendations/" + readOnly.getId() + "/execute"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("EXECUTED"));
        }

        mockMvc.perform(post(base + "/connectivity-check"))
                .andExpect(status().isOk());

        mockMvc.perform(patch(base + "/staleness-threshold")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stalenessThresholdSeconds\":1800}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stalenessThresholdSeconds").value(1800));
    }

    @Test
    @DisplayName("a webhook without a valid signature is rejected and persists nothing")
    void unsignedWebhookIsRejected() throws Exception {
        Workspace workspace = newWorkspace();
        String base = "/api/integrity/webhooks/razorpay/" + workspace.getId();

        mockMvc.perform(post(base)
                        .header("X-Razorpay-Event-Id", "evt_contract_unsigned")
                        .header("X-Razorpay-Signature", "not-a-real-signature")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event\":\"payment.captured\",\"payload\":{}}"))
                .andExpect(status().is4xxClientError());

        assertThat(webhookEventRepository.countByWorkspaceId(workspace.getId()))
                .as("an unverified delivery must not be stored, even though this endpoint needs no bearer token")
                .isZero();
    }

    @Test
    @DisplayName("merchants cannot enumerate or read shared operations workspaces")
    @WithMockUser(username = "merchant@aegis-x.test", roles = "MERCHANT")
    void merchantCannotAccessIntegrityWorkspaceData() throws Exception {
        Workspace workspace = newWorkspace();
        User merchant = userRepository.findByEmail("merchant@aegis-x.test")
                .orElseGet(() -> userRepository.save(User.builder()
                        .fullName("Integrity API Contract Merchant")
                        .email("merchant@aegis-x.test")
                        .passwordHash("not-used-by-mock-authentication")
                        .role(Role.MERCHANT)
                        .build()));
        membershipService.addMember(merchant.getId(), workspace.getId(), WorkspaceMemberRole.OWNER);
        String base = "/api/integrity/workspaces/" + workspace.getId();

        mockMvc.perform(get("/api/integrity/workspaces"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(base + "/orders"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(base + "/evidence/documents")
                        .param("orderId", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden());
    }
}
