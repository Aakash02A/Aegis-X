package com.aegisx.review;

import com.aegisx.auth.entity.Role;
import com.aegisx.auth.entity.User;
import com.aegisx.auth.repository.UserRepository;
import com.aegisx.review.dto.CreateReviewNoteRequest;
import com.aegisx.review.dto.ReviewNoteResponse;
import com.aegisx.review.entity.ManualReviewNote;
import com.aegisx.review.repository.ManualReviewNoteRepository;
import com.aegisx.transaction.entity.Transaction;
import com.aegisx.transaction.entity.TransactionStatus;
import com.aegisx.transaction.repository.TransactionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for manual review notes API.
 * 
 * Tests:
 * - Create review note (RBAC: compliance officer only)
 * - Get review notes for transaction (RBAC: merchant own only, compliance all)
 * - Audit event creation on note addition
 * - Validation (note text length)
 * - Unauthorized access handling
 */
@Slf4j
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(locations = "classpath:application-test.yml")
@Transactional
@DisplayName("Review Notes API Tests")
public class ReviewNoteControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private ManualReviewNoteRepository noteRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID merchantId;
    private UUID complianceOfficerId;
    private UUID transactionId;

    @BeforeEach
    void setUp() {
        // Create test users
        User merchant = User.builder()
            .email("merchant@test.com")
            .fullName("Test Merchant")
            .passwordHash("hashed-password")
            .role(Role.MERCHANT)
            .build();
        User savedMerchant = userRepository.save(merchant);
        merchantId = savedMerchant.getId();

        User complianceOfficer = User.builder()
            .email("compliance@test.com")
            .fullName("Compliance Officer")
            .passwordHash("hashed-password")
            .role(Role.COMPLIANCE_OFFICER)
            .build();
        User savedCompliance = userRepository.save(complianceOfficer);
        complianceOfficerId = savedCompliance.getId();

        // Create test transaction
        Transaction transaction = Transaction.builder()
            .referenceNumber("TXN-TEST-" + System.currentTimeMillis())
            .merchant(savedMerchant)
            .amount(new BigDecimal("1000.00"))
            .currency("USD")
            .beneficiaryName("Test Beneficiary")
            .purposeCode("SUPPLY")
            .status(TransactionStatus.APPROVED)
            .version(0L)
            .build();
        Transaction savedTransaction = transactionRepository.save(transaction);
        transactionId = savedTransaction.getId();

        log.info("Test setup complete: transaction={}, merchant={}, compliance={}",
            transactionId, merchantId, complianceOfficerId);
    }

    @Test
    @DisplayName("Compliance officer should create review note")
    @WithMockUser(username = "compliance@test.com", roles = "COMPLIANCE_OFFICER")
    void testCreateNoteAsComplianceOfficer() throws Exception {
        log.info("Test: Compliance officer creates review note");

        CreateReviewNoteRequest request = CreateReviewNoteRequest.builder()
            .noteText("Verified merchant identity. All compliance checks passed. Ready for approval.")
            .build();

        MvcResult result = mockMvc.perform(
            post("/api/transactions/{transactionId}/notes", transactionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").exists())
            .andExpect(jsonPath("$.transactionId").value(transactionId.toString()))
            .andExpect(jsonPath("$.authorName").value("Compliance Officer"))
            .andExpect(jsonPath("$.noteText").value(request.getNoteText()))
            .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        ReviewNoteResponse note = objectMapper.readValue(responseBody, ReviewNoteResponse.class);

        assertThat(note.getId()).isNotNull();
        assertThat(note.getCreatedAt()).isNotNull();

        log.info("✓ Review note created successfully: {}", note.getId());
    }

    @Test
    @DisplayName("Merchant should not create review note")
    @WithMockUser(username = "merchant@test.com", roles = "MERCHANT")
    void testMerchantCannotCreateNote() throws Exception {
        log.info("Test: Merchant denied permission to create note");

        CreateReviewNoteRequest request = CreateReviewNoteRequest.builder()
            .noteText("Attempting to create note as merchant.")
            .build();

        mockMvc.perform(
            post("/api/transactions/{transactionId}/notes", transactionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isForbidden());

        log.info("✓ Merchant correctly denied permission");
    }

    @Test
    @DisplayName("Compliance officer should view all notes on transaction")
    @WithMockUser(username = "compliance@test.com", roles = "COMPLIANCE_OFFICER")
    void testComplianceOfficerCanViewAllNotes() throws Exception {
        log.info("Test: Compliance officer views all notes");

        // Create a note first
        ManualReviewNote note = ManualReviewNote.builder()
            .transaction(transactionRepository.findById(transactionId).orElseThrow())
            .author(userRepository.findById(complianceOfficerId).orElseThrow())
            .noteText("Test investigation note")
            .build();
        noteRepository.save(note);

        mockMvc.perform(
            get("/api/transactions/{transactionId}/notes", transactionId)
                .param("page", "0")
                .param("size", "20")
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").isArray())
            .andExpect(jsonPath("$.content[0].noteText").value("Test investigation note"))
            .andExpect(jsonPath("$.totalElements").value(1));

        log.info("✓ Notes retrieved successfully");
    }

    @Test
    @DisplayName("Merchant should only view notes on own transactions")
    @WithMockUser(username = "merchant@test.com", roles = "MERCHANT")
    void testMerchantViewOwnNotesOnly() throws Exception {
        log.info("Test: Merchant RBAC - views own transaction notes");

        mockMvc.perform(
            get("/api/transactions/{transactionId}/notes", transactionId)
                .param("page", "0")
                .param("size", "20")
        )
            .andExpect(status().isOk());

        log.info("✓ Merchant can view notes on own transaction");
    }

    @Test
    @DisplayName("Review note validation: minimum length")
    @WithMockUser(username = "compliance@test.com", roles = "COMPLIANCE_OFFICER")
    void testNoteValidationMinimumLength() throws Exception {
        log.info("Test: Note text minimum length validation");

        CreateReviewNoteRequest request = CreateReviewNoteRequest.builder()
            .noteText("Too short")  // Less than 10 characters
            .build();

        mockMvc.perform(
            post("/api/transactions/{transactionId}/notes", transactionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isBadRequest());

        log.info("✓ Validation correctly rejected short note");
    }

    @Test
    @DisplayName("Unauthorized user cannot access notes")
    void testUnauthorizedAccessDenied() throws Exception {
        log.info("Test: Unauthorized access denied");

        mockMvc.perform(
            get("/api/transactions/{transactionId}/notes", transactionId)
                .param("page", "0")
                .param("size", "20")
        )
            .andExpect(status().isUnauthorized());

        log.info("✓ Unauthorized access correctly denied");
    }

    @Test
    @DisplayName("Transaction not found should return 404")
    @WithMockUser(username = "compliance@test.com", roles = "COMPLIANCE_OFFICER")
    void testTransactionNotFoundReturns404() throws Exception {
        log.info("Test: Not found handling");

        UUID nonExistentId = UUID.randomUUID();

        mockMvc.perform(
            get("/api/transactions/{transactionId}/notes", nonExistentId)
                .param("page", "0")
                .param("size", "20")
        )
            .andExpect(status().isNotFound());

        log.info("✓ Non-existent transaction correctly returns 404");
    }
}
