package com.aegisx.auth;

import com.aegisx.auth.dto.AuthResponse;
import com.aegisx.auth.dto.LoginRequest;
import com.aegisx.auth.dto.RegisterRequest;
import com.aegisx.auth.dto.UserResponse;
import com.aegisx.auth.entity.Role;
import com.aegisx.auth.entity.User;
import com.aegisx.auth.repository.UserRepository;
import com.aegisx.auth.security.JwtService;
import com.aegisx.integrity.domain.ProviderMode;
import com.aegisx.integrity.domain.WorkspaceMemberRole;
import com.aegisx.integrity.entity.Workspace;
import com.aegisx.integrity.repository.EvidenceDocumentRepository;
import com.aegisx.integrity.repository.IntegrityAuditEventRepository;
import com.aegisx.integrity.repository.PaymentOrderRepository;
import com.aegisx.integrity.repository.WorkspaceRepository;
import com.aegisx.integrity.service.WorkspaceMembershipService;
import com.aegisx.integrity.service.WorkspaceService;
import com.aegisx.settlement.dto.SettlementRequest;
import com.aegisx.settlement.entity.PaymentProcessor;
import com.aegisx.transaction.dto.CreateTransactionRequest;
import com.aegisx.transaction.dto.TransactionResponse;
import com.aegisx.transaction.repository.TransactionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Real HTTP Tenancy and Authorization Test")
class RealHttpTenancyAndAuthorizationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private WorkspaceService workspaceService;

    @Autowired
    private WorkspaceMembershipService membershipService;

    @Autowired
    private PaymentOrderRepository orderRepository;

    @Autowired
    private EvidenceDocumentRepository evidenceDocumentRepository;

    @Autowired
    private IntegrityAuditEventRepository auditEventRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return headers;
    }

    @Test
    @DisplayName("Proves cross-access returns 403 or 404 for reads AND writes with no side effects, and merchant cannot reach admin/operator paths")
    void proveRealHttpIsolationAndRbac() {
        // 1. Real HTTP Registration & Login for Merchant A and Merchant B
        String emailA = "merchant.a." + UUID.randomUUID() + "@aegis-x.test";
        String emailB = "merchant.b." + UUID.randomUUID() + "@aegis-x.test";
        String password = "SecurePassword@123";

        // Register Merchant A
        RegisterRequest regA = RegisterRequest.builder()
                .fullName("Merchant Alpha Ltd")
                .email(emailA)
                .password(password)
                .build();
        ResponseEntity<UserResponse> regRespA = restTemplate.postForEntity(
                url("/api/auth/register"), regA, UserResponse.class);
        assertThat(regRespA.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(regRespA.getBody()).isNotNull();
        assertThat(regRespA.getBody().getRole()).isEqualTo(Role.MERCHANT);

        // Register Merchant B
        RegisterRequest regB = RegisterRequest.builder()
                .fullName("Merchant Beta Ltd")
                .email(emailB)
                .password(password)
                .build();
        ResponseEntity<UserResponse> regRespB = restTemplate.postForEntity(
                url("/api/auth/register"), regB, UserResponse.class);
        assertThat(regRespB.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(regRespB.getBody()).isNotNull();
        assertThat(regRespB.getBody().getRole()).isEqualTo(Role.MERCHANT);

        // Login Merchant A
        ResponseEntity<AuthResponse> loginRespA = restTemplate.postForEntity(
                url("/api/auth/login"),
                LoginRequest.builder().email(emailA).password(password).build(),
                AuthResponse.class);
        assertThat(loginRespA.getStatusCode()).isEqualTo(HttpStatus.OK);
        String tokenA = loginRespA.getBody().getToken();
        assertThat(tokenA).isNotBlank();

        // Login Merchant B
        ResponseEntity<AuthResponse> loginRespB = restTemplate.postForEntity(
                url("/api/auth/login"),
                LoginRequest.builder().email(emailB).password(password).build(),
                AuthResponse.class);
        assertThat(loginRespB.getStatusCode()).isEqualTo(HttpStatus.OK);
        String tokenB = loginRespB.getBody().getToken();
        assertThat(tokenB).isNotBlank();

        // Create Admin A and Admin B with corresponding workspaces
        User adminA = userRepository.save(User.builder()
                .fullName("Admin Alpha")
                .email("admin.a." + UUID.randomUUID() + "@aegis-x.test")
                .passwordHash(passwordEncoder.encode(password))
                .role(Role.ADMIN)
                .build());
        User adminB = userRepository.save(User.builder()
                .fullName("Admin Beta")
                .email("admin.b." + UUID.randomUUID() + "@aegis-x.test")
                .passwordHash(passwordEncoder.encode(password))
                .role(Role.ADMIN)
                .build());

        String tokenAdminA = jwtService.generateToken(adminA);
        String tokenAdminB = jwtService.generateToken(adminB);

        Workspace workspaceA = workspaceService.create("Workspace A " + UUID.randomUUID(), ProviderMode.RAZORPAY_TEST);
        Workspace workspaceB = workspaceService.create("Workspace B " + UUID.randomUUID(), ProviderMode.RAZORPAY_TEST);

        membershipService.addMember(adminA.getId(), workspaceA.getId(), WorkspaceMemberRole.OWNER);
        membershipService.addMember(adminB.getId(), workspaceB.getId(), WorkspaceMemberRole.OWNER);

        // 2. Merchant A creates Transaction A over real HTTP
        CreateTransactionRequest txnReqA = CreateTransactionRequest.builder()
                .amount(new BigDecimal("1500.00"))
                .currency("INR")
                .beneficiaryName("Alpha Supplier Ltd")
                .purposeCode("P0101")
                .build();
        ResponseEntity<TransactionResponse> txnRespA = restTemplate.exchange(
                url("/api/transactions"),
                HttpMethod.POST,
                new HttpEntity<>(txnReqA, bearerHeaders(tokenA)),
                TransactionResponse.class);
        assertThat(txnRespA.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID txnIdA = txnRespA.getBody().getId();
        assertThat(txnIdA).isNotNull();

        // 3. Merchant B creates Transaction B over real HTTP
        CreateTransactionRequest txnReqB = CreateTransactionRequest.builder()
                .amount(new BigDecimal("2500.00"))
                .currency("INR")
                .beneficiaryName("Beta Supplier Ltd")
                .purposeCode("P0101")
                .build();
        ResponseEntity<TransactionResponse> txnRespB = restTemplate.exchange(
                url("/api/transactions"),
                HttpMethod.POST,
                new HttpEntity<>(txnReqB, bearerHeaders(tokenB)),
                TransactionResponse.class);
        assertThat(txnRespB.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID txnIdB = txnRespB.getBody().getId();
        assertThat(txnIdB).isNotNull();

        // Snapshot DB counts before cross-access attempts
        long txnCountBefore = transactionRepository.count();
        long wsCountBefore = workspaceRepository.count();
        long orderCountBefore = orderRepository.count();
        long docCountBefore = evidenceDocumentRepository.count();
        long auditCountBefore = auditEventRepository.count();

        // 4. CROSS-ACCESS READS (Merchant B attempts to read Merchant A's transaction resources) -> MUST RETURN 404
        // Read transaction
        ResponseEntity<String> readTxn = restTemplate.exchange(
                url("/api/transactions/" + txnIdA),
                HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(tokenB)),
                String.class);
        assertThat(readTxn.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Read notes
        ResponseEntity<String> readNotes = restTemplate.exchange(
                url("/api/transactions/" + txnIdA + "/notes"),
                HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(tokenB)),
                String.class);
        assertThat(readNotes.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Read audit
        ResponseEntity<String> readAudit = restTemplate.exchange(
                url("/api/transactions/" + txnIdA + "/audit"),
                HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(tokenB)),
                String.class);
        assertThat(readAudit.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Read evidence checklist
        ResponseEntity<String> readEvidence = restTemplate.exchange(
                url("/api/v1/evidence/checklist/" + txnIdA),
                HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(tokenB)),
                String.class);
        assertThat(readEvidence.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Read notifications
        ResponseEntity<String> readNotifs = restTemplate.exchange(
                url("/api/transactions/" + txnIdA + "/notifications"),
                HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(tokenB)),
                String.class);
        assertThat(readNotifs.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Read policy evaluation
        ResponseEntity<String> readPolicyEval = restTemplate.exchange(
                url("/api/transactions/" + txnIdA + "/policy-evaluation"),
                HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(tokenB)),
                String.class);
        assertThat(readPolicyEval.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Read policy violations
        ResponseEntity<String> readViolations = restTemplate.exchange(
                url("/api/v1/policy-violations/transaction/" + txnIdA),
                HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(tokenB)),
                String.class);
        assertThat(readViolations.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // 5. CROSS-ACCESS WRITES (Merchant B attempts to modify Merchant A's transaction) -> MUST RETURN 403 or 404
        // Write note on transaction A -> 403
        ResponseEntity<String> writeNote = restTemplate.exchange(
                url("/api/transactions/" + txnIdA + "/notes"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("noteText", "Unauthorized note by merchant B"), bearerHeaders(tokenB)),
                String.class);
        assertThat(writeNote.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // Write payment confirm on transaction A -> 404
        ResponseEntity<String> writeConfirm = restTemplate.exchange(
                url("/api/transactions/" + txnIdA + "/payment/confirm"),
                HttpMethod.POST,
                new HttpEntity<>(bearerHeaders(tokenB)),
                String.class);
        assertThat(writeConfirm.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Write payment order on transaction A -> 404
        ResponseEntity<String> writeOrder = restTemplate.exchange(
                url("/api/v1/payments/orders"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("transactionId", txnIdA.toString()), bearerHeaders(tokenB)),
                String.class);
        assertThat(writeOrder.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Write document request notification on transaction A -> 404
        ResponseEntity<String> writeDocReq = restTemplate.exchange(
                url("/api/transactions/" + txnIdA + "/notifications/document-request"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("recipientPhone", "919876543210"), bearerHeaders(tokenB)),
                String.class);
        assertThat(writeDocReq.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Write policy evaluation on transaction A -> 404
        ResponseEntity<String> writePolicyEval = restTemplate.exchange(
                url("/api/transactions/" + txnIdA + "/policy-evaluation"),
                HttpMethod.POST,
                new HttpEntity<>(bearerHeaders(tokenB)),
                String.class);
        assertThat(writePolicyEval.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // 6. CROSS-WORKSPACE ACCESS (Admin B attempts to access Workspace A) -> MUST RETURN 404
        // Read Workspace A with Admin B
        ResponseEntity<String> readWs = restTemplate.exchange(
                url("/api/integrity/workspaces/" + workspaceA.getId()),
                HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(tokenAdminB)),
                String.class);
        assertThat(readWs.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Write to Workspace A with Admin B
        ResponseEntity<String> writeWs = restTemplate.exchange(
                url("/api/integrity/workspaces/" + workspaceA.getId() + "/orders"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("amountMinor", 5000, "currency", "INR"), bearerHeaders(tokenAdminB)),
                String.class);
        assertThat(writeWs.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // 7. CHECK ADMIN / OPERATOR PATHS CANNOT BE REACHED BY A MERCHANT -> MUST RETURN 403
        // Performance metrics
        ResponseEntity<String> perfMetrics = restTemplate.exchange(
                url("/api/performance/metrics"),
                HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(tokenA)),
                String.class);
        assertThat(perfMetrics.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // Performance reset
        ResponseEntity<String> perfReset = restTemplate.exchange(
                url("/api/performance/reset"),
                HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(tokenA)),
                String.class);
        assertThat(perfReset.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // Integrity workspaces list
        ResponseEntity<String> integrityWorkspaces = restTemplate.exchange(
                url("/api/integrity/workspaces"),
                HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(tokenA)),
                String.class);
        assertThat(integrityWorkspaces.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // Settlement initiation
        ResponseEntity<String> settlementInit = restTemplate.exchange(
                url("/api/v1/settlements"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "transactionId", txnIdA.toString(),
                        "paymentProcessor", "RAZORPAYX",
                        "settlementAmount", 1500,
                        "settlementCurrency", "INR"
                ), bearerHeaders(tokenA)),
                String.class);
        assertThat(settlementInit.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // Compliance review queue
        ResponseEntity<String> complianceQueue = restTemplate.exchange(
                url("/api/v1/compliance/queue"),
                HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(tokenA)),
                String.class);
        assertThat(complianceQueue.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // 8. DENIAL HAS NO SIDE EFFECTS -> Database counts unchanged
        assertThat(transactionRepository.count()).isEqualTo(txnCountBefore);
        assertThat(workspaceRepository.count()).isEqualTo(wsCountBefore);
        assertThat(orderRepository.count()).isEqualTo(orderCountBefore);
        assertThat(evidenceDocumentRepository.count()).isEqualTo(docCountBefore);
        assertThat(auditEventRepository.count()).isEqualTo(auditCountBefore);
    }
}
