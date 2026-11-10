# Phase 2b: complete test diffs

This file preserves the complete diff for each existing test file changed by Phase 2 and this Phase 2b fix-up. The new `RealHttpTenancyAndAuthorizationTest.java` was added as a new file and is listed in the handoff; it is not a change to an existing test.

## Phase 2 commit `0f4bf8b`

```diffdiff --git a/backend/src/test/java/com/aegisx/auth/security/JwtServiceTest.java b/backend/src/test/java/com/aegisx/auth/security/JwtServiceTest.java
index 6df54f9..a2cbd05 100644
--- a/backend/src/test/java/com/aegisx/auth/security/JwtServiceTest.java
+++ b/backend/src/test/java/com/aegisx/auth/security/JwtServiceTest.java
@@ -98,6 +98,14 @@ class JwtServiceTest {
         assertFalse(expiredJwtService.isTokenValid(token));
     }
 
+    @Test
+    void isTokenValidReturnsFalseForTamperedSignature() {
+        String token = jwtService.generateToken(testUser);
+        String tamperedToken = token.substring(0, token.lastIndexOf('.') + 1) + "invalidsignature";
+        assertFalse(jwtService.isTokenValid(tamperedToken));
+        assertFalse(jwtService.isTokenValid(tamperedToken, userDetails));
+    }
+
     @Test
     void getExpirationTimeInSecondsCalculatesCorrectly() {
         assertEquals(3600L, jwtService.getExpirationTimeInSeconds());
diff --git a/backend/src/test/java/com/aegisx/evidence/controller/EvidenceControllerTest.java b/backend/src/test/java/com/aegisx/evidence/controller/EvidenceControllerTest.java
index 13d95b4..0925432 100644
--- a/backend/src/test/java/com/aegisx/evidence/controller/EvidenceControllerTest.java
+++ b/backend/src/test/java/com/aegisx/evidence/controller/EvidenceControllerTest.java
@@ -42,16 +42,19 @@ class EvidenceControllerTest {
     @Autowired private TransactionRepository transactionRepository;
     @Autowired private EvidenceChecklistRepository checklistRepository;
 
+    private static final String MERCHANT_EMAIL = "evidence-test-merchant@aegisx.test";
     private UUID transactionId;
+    private User merchant;
 
     @BeforeEach
     void setUp() {
-        User merchant = userRepository.saveAndFlush(User.builder()
-                .fullName("Evidence Test Merchant")
-                .email("evidence-" + UUID.randomUUID() + "@aegisx.test")
-                .passwordHash("not-used-by-this-service")
-                .role(Role.MERCHANT)
-                .build());
+        merchant = userRepository.findByEmail(MERCHANT_EMAIL)
+                .orElseGet(() -> userRepository.saveAndFlush(User.builder()
+                        .fullName("Evidence Test Merchant")
+                        .email(MERCHANT_EMAIL)
+                        .passwordHash("not-used-by-this-service")
+                        .role(Role.MERCHANT)
+                        .build()));
         Transaction transaction = transactionRepository.saveAndFlush(Transaction.builder()
                 .referenceNumber("TXN-EVIDENCE-" + UUID.randomUUID())
                 .merchant(merchant)
@@ -68,7 +71,7 @@ class EvidenceControllerTest {
 
     @Test
     @DisplayName("Reads the persisted evidence checklist")
-    @WithMockUser(authorities = "ROLE_MERCHANT")
+    @WithMockUser(username = MERCHANT_EMAIL, roles = "MERCHANT")
     void getsChecklist() throws Exception {
         mockMvc.perform(get("/api/v1/evidence/checklist/{transactionId}", transactionId))
                 .andExpect(status().isOk())
@@ -81,9 +84,20 @@ class EvidenceControllerTest {
 
     @Test
     @DisplayName("Returns a successful empty checklist response when none exists")
-    @WithMockUser(authorities = "ROLE_MERCHANT")
+    @WithMockUser(username = MERCHANT_EMAIL, roles = "MERCHANT")
     void returnsEmptyChecklist() throws Exception {
-        mockMvc.perform(get("/api/v1/evidence/checklist/{transactionId}", UUID.randomUUID()))
+        Transaction noChecklistTxn = transactionRepository.saveAndFlush(Transaction.builder()
+                .referenceNumber("TXN-EVIDENCE-EMPTY-" + UUID.randomUUID())
+                .merchant(merchant)
+                .amount(new BigDecimal("500.00"))
+                .currency("USD")
+                .beneficiaryName("Test Beneficiary")
+                .purposeCode("P0101")
+                .status(TransactionStatus.EVIDENCE_PENDING)
+                .version(0L)
+                .build());
+
+        mockMvc.perform(get("/api/v1/evidence/checklist/{transactionId}", noChecklistTxn.getId()))
                 .andExpect(status().isOk())
                 .andExpect(jsonPath("$.success").value(true))
                 .andExpect(jsonPath("$.data").doesNotExist());
@@ -91,7 +105,7 @@ class EvidenceControllerTest {
 
     @Test
     @DisplayName("Lists persisted missing documents")
-    @WithMockUser(authorities = "ROLE_MERCHANT")
+    @WithMockUser(username = MERCHANT_EMAIL, roles = "MERCHANT")
     void listsMissingDocuments() throws Exception {
         checklistRepository.findByTransactionId(transactionId).orElseThrow().setShippingBillRequired(true);
         checklistRepository.flush();
@@ -127,7 +141,7 @@ class EvidenceControllerTest {
 
     @Test
     @DisplayName("Persists a document-uploaded checklist state")
-    @WithMockUser(authorities = "ROLE_MERCHANT")
+    @WithMockUser(username = MERCHANT_EMAIL, roles = "MERCHANT")
     void marksDocumentUploaded() throws Exception {
         mockMvc.perform(post("/api/v1/evidence/uploaded/{transactionId}", transactionId)
                         .with(csrf())
@@ -140,7 +154,7 @@ class EvidenceControllerTest {
 
     @Test
     @DisplayName("Reports evidence completeness from the persisted checklist")
-    @WithMockUser(authorities = "ROLE_MERCHANT")
+    @WithMockUser(username = MERCHANT_EMAIL, roles = "MERCHANT")
     void checksEvidenceCompleteness() throws Exception {
         checklistRepository.findByTransactionId(transactionId).orElseThrow().markDocumentUploaded(DocumentType.COMMERCIAL_INVOICE);
         checklistRepository.flush();
@@ -162,7 +176,7 @@ class EvidenceControllerTest {
 
     @Test
     @DisplayName("Returns the merchant evidence summary")
-    @WithMockUser(authorities = "ROLE_MERCHANT")
+    @WithMockUser(username = MERCHANT_EMAIL, roles = "MERCHANT")
     void returnsSummary() throws Exception {
         mockMvc.perform(get("/api/v1/evidence/summary/{transactionId}", transactionId))
                 .andExpect(status().isOk())
@@ -181,7 +195,7 @@ class EvidenceControllerTest {
 
     @Test
     @DisplayName("Rejects requirement updates for merchants")
-    @WithMockUser(authorities = "ROLE_MERCHANT")
+    @WithMockUser(username = MERCHANT_EMAIL, roles = "MERCHANT")
     void rejectsWrongRole() throws Exception {
         var request = com.aegisx.evidence.dto.EvidenceRequirementRequest.builder()
                 .transactionId(transactionId).invoiceRequired(true).build();
@@ -205,6 +219,20 @@ class EvidenceControllerTest {
                 .andExpect(status().isBadRequest());
     }
 
+    @Test
+    @DisplayName("Rejects cross-merchant checklist access with 404")
+    @WithMockUser(username = "other-merchant@aegisx.test", roles = "MERCHANT")
+    void rejectsCrossMerchantChecklistAccess() throws Exception {
+        userRepository.saveAndFlush(User.builder()
+                .fullName("Other Merchant")
+                .email("other-merchant@aegisx.test")
+                .passwordHash("not-used")
+                .role(Role.MERCHANT)
+                .build());
+        mockMvc.perform(get("/api/v1/evidence/checklist/{transactionId}", transactionId))
+                .andExpect(status().isNotFound());
+    }
+
     private EvidenceChecklist checklist(Transaction transaction, boolean invoiceRequired,
                                          boolean shippingRequired, boolean invoiceUploaded) {
         EvidenceChecklist checklist = EvidenceChecklist.builder()```

## Phase 2b JWT wrong-signing-key coverage

```diffdiff --git a/backend/src/test/java/com/aegisx/auth/security/JwtServiceTest.java b/backend/src/test/java/com/aegisx/auth/security/JwtServiceTest.java
index a2cbd05..91664d8 100644
--- a/backend/src/test/java/com/aegisx/auth/security/JwtServiceTest.java
+++ b/backend/src/test/java/com/aegisx/auth/security/JwtServiceTest.java
@@ -106,6 +106,18 @@ class JwtServiceTest {
         assertFalse(jwtService.isTokenValid(tamperedToken, userDetails));
     }
 
+    @Test
+    void isTokenValidReturnsFalseForTokenSignedWithWrongKey() {
+        String token = jwtService.generateToken(testUser);
+        JwtService wrongKeyService = new JwtService(fixedClock);
+        ReflectionTestUtils.setField(wrongKeyService, "secretKey",
+                "another-test-signing-key-long-enough-for-hmac-sha256-validation");
+        ReflectionTestUtils.setField(wrongKeyService, "jwtExpirationMs", 3600000L);
+
+        assertFalse(wrongKeyService.isTokenValid(token));
+        assertFalse(wrongKeyService.isTokenValid(token, userDetails));
+    }
+
     @Test
     void getExpirationTimeInSecondsCalculatesCorrectly() {
         assertEquals(3600L, jwtService.getExpirationTimeInSeconds());```

## Phase 2b reconciliation cross-workspace regression

```diffdiff --git a/backend/src/test/java/com/aegisx/integrity/WorkspaceMembershipAuthorizationTest.java b/backend/src/test/java/com/aegisx/integrity/WorkspaceMembershipAuthorizationTest.java
index 84bbea5..aba1989 100644
--- a/backend/src/test/java/com/aegisx/integrity/WorkspaceMembershipAuthorizationTest.java
+++ b/backend/src/test/java/com/aegisx/integrity/WorkspaceMembershipAuthorizationTest.java
@@ -4,6 +4,13 @@ import com.aegisx.auth.entity.Role;
 import com.aegisx.auth.entity.User;
 import com.aegisx.auth.repository.UserRepository;
 import com.aegisx.integrity.domain.WorkspaceMemberRole;
+import com.aegisx.integrity.domain.CertaintyLevel;
+import com.aegisx.integrity.domain.ExceptionSeverity;
+import com.aegisx.integrity.domain.FindingType;
+import com.aegisx.integrity.domain.ReconciliationStrategy;
+import com.aegisx.integrity.domain.ReconciliationTrigger;
+import com.aegisx.integrity.entity.ReconciliationFinding;
+import com.aegisx.integrity.entity.ReconciliationRun;
 import com.aegisx.integrity.entity.Workspace;
 import com.aegisx.integrity.service.WorkspaceMembershipService;
 import com.aegisx.integrity.support.IntegrityTestSupport;
@@ -20,6 +27,7 @@ import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilde
 import java.util.ArrayList;
 import java.util.List;
 import java.util.UUID;
+import java.time.Instant;
 
 import static org.assertj.core.api.Assertions.assertThat;
 import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
@@ -86,6 +94,38 @@ class WorkspaceMembershipAuthorizationTest extends IntegrityTestSupport {
         assertThat(auditEventRepository.count()).isEqualTo(auditBefore);
     }
 
+    @Test
+    @DisplayName("a member cannot read findings for another workspace's reconciliation run")
+    void memberCannotReadFindingsForAnotherWorkspacesRun() throws Exception {
+        User member = createAdmin("reconciliation-reader-" + UUID.randomUUID() + "@aegis-x.test");
+        Workspace memberWorkspace = newWorkspace();
+        Workspace otherWorkspace = newWorkspace();
+        membershipService.addMember(member.getId(), memberWorkspace.getId(), WorkspaceMemberRole.OWNER);
+
+        ReconciliationRun otherRun = runRepository.save(ReconciliationRun.builder()
+                .workspaceId(otherWorkspace.getId())
+                .trigger(ReconciliationTrigger.MANUAL)
+                .strategy(ReconciliationStrategy.PROPOSED_EVIDENCE_BASED)
+                .startedAt(Instant.now())
+                .providerReachable(false)
+                .findingsCount(1)
+                .build());
+        findingRepository.save(ReconciliationFinding.builder()
+                .runId(otherRun.getId())
+                .workspaceId(otherWorkspace.getId())
+                .findingType(FindingType.AMOUNT_MISMATCH)
+                .severity(ExceptionSeverity.HIGH)
+                .certainty(CertaintyLevel.CERTAIN)
+                .summary("Other workspace finding")
+                .build());
+
+        mockMvc.perform(get("/api/integrity/workspaces/" + memberWorkspace.getId()
+                        + "/reconciliation/runs/" + otherRun.getId() + "/findings")
+                        .with(user(member.getEmail()).roles("ADMIN")))
+                .andExpect(status().isNotFound())
+                .andExpect(jsonPath("$.message").value("Reconciliation run not found"));
+    }
+
     private User createAdmin(String email) {
         return userRepository.save(User.builder()
                 .fullName("Workspace Membership Test User")```

## Assertion review

- `EvidenceControllerTest.returnsEmptyChecklist`: the prior setup used a random transaction UUID that did not exist. The updated setup creates a real transaction owned by the authenticated merchant and still asserts a successful empty checklist. This fixes an invalid fixture precondition; the empty-result assertion was retained.
- Other `EvidenceControllerTest` `@WithMockUser` declarations now use the actual merchant email and `ROLE_MERCHANT`, so the owner check is exercised with the right principal. Existing outcome/status assertions were retained.
- `JwtServiceTest` adds tampered-signature and wrong-signing-key rejection tests; neither removes or relaxes existing expiry assertions.
- `WorkspaceMembershipAuthorizationTest` adds a real HTTP 404 assertion when a workspace member requests a reconciliation run ID owned by another workspace.
