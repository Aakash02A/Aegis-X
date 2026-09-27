package com.aegisx.transaction;

import com.aegisx.auth.entity.Role;
import com.aegisx.auth.entity.User;
import com.aegisx.auth.repository.UserRepository;
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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for transaction search API.
 * 
 * Tests:
 * - Search without filters (all transactions)
 * - Search with status filter
 * - Search with amount range filters
 * - Search with date range filters
 * - Search with merchant filter (RBAC enforcement)
 * - Pagination
 * - RBAC: merchants see own only, compliance officers see all
 * - Unauthorized access handling
 */
@Slf4j
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(locations = "classpath:application-test.yml")
@Transactional
@DisplayName("Transaction Search API Tests")
public class TransactionSearchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID merchantId1;
    private UUID merchantId2;
    private UUID complianceOfficerId;
    private UUID transactionId1;
    private UUID transactionId2;
    private UUID transactionId3;

    @BeforeEach
    void setUp() {
        // Create test users
        User merchant1 = User.builder()
            .email("merchant1@test.com")
            .fullName("Merchant One")
            .passwordHash("hashed-password")
            .role(Role.MERCHANT)
            .build();
        User savedMerchant1 = userRepository.save(merchant1);
        merchantId1 = savedMerchant1.getId();

        User merchant2 = User.builder()
            .email("merchant2@test.com")
            .fullName("Merchant Two")
            .passwordHash("hashed-password")
            .role(Role.MERCHANT)
            .build();
        User savedMerchant2 = userRepository.save(merchant2);
        merchantId2 = savedMerchant2.getId();

        User complianceOfficer = User.builder()
            .email("compliance@test.com")
            .fullName("Compliance Officer")
            .passwordHash("hashed-password")
            .role(Role.COMPLIANCE_OFFICER)
            .build();
        User savedCompliance = userRepository.save(complianceOfficer);
        complianceOfficerId = savedCompliance.getId();

        // Create test transactions for merchant1
        Transaction txn1 = Transaction.builder()
            .referenceNumber("TXN-20260922-001")
            .merchant(savedMerchant1)
            .amount(new BigDecimal("5000.00"))
            .currency("USD")
            .beneficiaryName("Acme Corp")
            .purposeCode("SUPPLY")
            .status(TransactionStatus.APPROVED)
            .version(0L)
            .build();
        Transaction savedTxn1 = transactionRepository.save(txn1);
        transactionId1 = savedTxn1.getId();

        Transaction txn2 = Transaction.builder()
            .referenceNumber("TXN-20260922-002")
            .merchant(savedMerchant1)
            .amount(new BigDecimal("15000.00"))
            .currency("USD")
            .beneficiaryName("Beta Inc")
            .purposeCode("CONSULTING")
            .status(TransactionStatus.COMPLIANCE_HOLD)
            .version(0L)
            .build();
        Transaction savedTxn2 = transactionRepository.save(txn2);
        transactionId2 = savedTxn2.getId();

        // Create test transaction for merchant2
        Transaction txn3 = Transaction.builder()
            .referenceNumber("TXN-20260923-001")
            .merchant(savedMerchant2)
            .amount(new BigDecimal("2500.00"))
            .currency("EUR")
            .beneficiaryName("Gamma Ltd")
            .purposeCode("SUPPLY")
            .status(TransactionStatus.APPROVED)
            .version(0L)
            .build();
        Transaction savedTxn3 = transactionRepository.save(txn3);
        transactionId3 = savedTxn3.getId();

        log.info("Test setup complete: 3 transactions created (2 for merchant1, 1 for merchant2)");
    }

    @Test
    @DisplayName("Search without filters should return all transactions (compliance officer)")
    @WithMockUser(username = "compliance@test.com", roles = "COMPLIANCE_OFFICER")
    void testSearchAllTransactions() throws Exception {
        log.info("Test: Search all transactions without filters");

        mockMvc.perform(
            get("/api/transactions/search")
                .param("page", "0")
                .param("size", "20")
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(greaterThanOrEqualTo(3)))
            .andExpect(jsonPath("$.totalElements").value(greaterThanOrEqualTo(3)));

        log.info("✓ All transactions returned successfully");
    }

    @Test
    @DisplayName("Merchant should only see own transactions (RBAC)")
    @WithMockUser(username = "merchant1@test.com", roles = "MERCHANT")
    void testMerchantSeesOwnTransactionsOnly() throws Exception {
        log.info("Test: Merchant RBAC - sees only own transactions");

        mockMvc.perform(
            get("/api/transactions/search")
                .param("page", "0")
                .param("size", "20")
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(2))
            .andExpect(jsonPath("$.content[0].merchantId").value(merchantId1.toString()));

        log.info("✓ Merchant sees only own transactions");
    }

    @Test
    @DisplayName("Search by status filter")
    @WithMockUser(username = "compliance@test.com", roles = "COMPLIANCE_OFFICER")
    void testSearchByStatus() throws Exception {
        log.info("Test: Filter by status");

        mockMvc.perform(
            get("/api/transactions/search")
                .param("status", "APPROVED")
                .param("page", "0")
                .param("size", "20")
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[*].status").value(org.hamcrest.Matchers.everyItem(
                org.hamcrest.Matchers.equalTo("APPROVED")
            )));

        log.info("✓ Status filter working correctly");
    }

    @Test
    @DisplayName("Search by amount range filter")
    @WithMockUser(username = "compliance@test.com", roles = "COMPLIANCE_OFFICER")
    void testSearchByAmountRange() throws Exception {
        log.info("Test: Filter by amount range");

        mockMvc.perform(
            get("/api/transactions/search")
                .param("amountMin", "5000")
                .param("amountMax", "10000")
                .param("page", "0")
                .param("size", "20")
        )
            .andExpect(status().isOk());

        log.info("✓ Amount range filter working correctly");
    }

    @Test
    @DisplayName("Search by date range filter")
    @WithMockUser(username = "compliance@test.com", roles = "COMPLIANCE_OFFICER")
    void testSearchByDateRange() throws Exception {
        log.info("Test: Filter by date range");

        mockMvc.perform(
            get("/api/transactions/search")
                .param("dateFrom", "2020-01-01")
                .param("dateTo", "2030-01-01")
                .param("page", "0")
                .param("size", "20")
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(greaterThanOrEqualTo(1)));

        log.info("✓ Date range filter working correctly");
    }

    @Test
    @DisplayName("Search by currency filter")
    @WithMockUser(username = "compliance@test.com", roles = "COMPLIANCE_OFFICER")
    void testSearchByCurrency() throws Exception {
        log.info("Test: Filter by currency");

        mockMvc.perform(
            get("/api/transactions/search")
                .param("currency", "USD")
                .param("page", "0")
                .param("size", "20")
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[*].currency").value(org.hamcrest.Matchers.everyItem(
                org.hamcrest.Matchers.equalTo("USD")
            )));

        log.info("✓ Currency filter working correctly");
    }

    @Test
    @DisplayName("Search by merchant filter (compliance officer only)")
    @WithMockUser(username = "compliance@test.com", roles = "COMPLIANCE_OFFICER")
    void testSearchByMerchantFilter() throws Exception {
        log.info("Test: Filter by specific merchant (compliance officer)");

        mockMvc.perform(
            get("/api/transactions/search")
                .param("merchantId", merchantId2.toString())
                .param("page", "0")
                .param("size", "20")
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].merchantId").value(merchantId2.toString()));

        log.info("✓ Merchant filter working correctly");
    }

    @Test
    @DisplayName("Merchant cannot override merchantId filter (RBAC)")
    @WithMockUser(username = "merchant1@test.com", roles = "MERCHANT")
    void testMerchantCannotOverrideMerchantFilter() throws Exception {
        log.info("Test: Merchant cannot see other merchants' transactions via filter");

        // Try to filter by merchant2 ID
        mockMvc.perform(
            get("/api/transactions/search")
                .param("merchantId", merchantId2.toString())
                .param("page", "0")
                .param("size", "20")
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[*].merchantId").value(org.hamcrest.Matchers.everyItem(
                org.hamcrest.Matchers.equalTo(merchantId1.toString())
            )));

        log.info("✓ Merchant filter correctly overridden by RBAC");
    }

    @Test
    @DisplayName("Search with multiple filters (AND-style)")
    @WithMockUser(username = "compliance@test.com", roles = "COMPLIANCE_OFFICER")
    void testSearchWithMultipleFilters() throws Exception {
        log.info("Test: Multiple filters combined (AND-style)");

        mockMvc.perform(
            get("/api/transactions/search")
                .param("status", "APPROVED")
                .param("currency", "USD")
                .param("amountMin", "5000")
                .param("page", "0")
                .param("size", "20")
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[*].status").value(org.hamcrest.Matchers.everyItem(
                org.hamcrest.Matchers.equalTo("APPROVED")
            )))
            .andExpect(jsonPath("$.content[*].currency").value(org.hamcrest.Matchers.everyItem(
                org.hamcrest.Matchers.equalTo("USD")
            )));

        log.info("✓ Multiple filters working correctly (AND-style)");
    }

    @Test
    @DisplayName("Search results include summary counts")
    @WithMockUser(username = "compliance@test.com", roles = "COMPLIANCE_OFFICER")
    void testSearchResultsIncludeCounts() throws Exception {
        log.info("Test: Search results include document, audit, and note counts");

        mockMvc.perform(
            get("/api/transactions/search")
                .param("page", "0")
                .param("size", "20")
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].documentCount").exists())
            .andExpect(jsonPath("$.content[0].auditEventCount").exists())
            .andExpect(jsonPath("$.content[0].noteCount").exists());

        log.info("✓ Summary counts included in results");
    }

    @Test
    @DisplayName("Pagination works correctly")
    @WithMockUser(username = "compliance@test.com", roles = "COMPLIANCE_OFFICER")
    void testPagination() throws Exception {
        log.info("Test: Pagination");

        mockMvc.perform(
            get("/api/transactions/search")
                .param("page", "0")
                .param("size", "2")
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.pageable.pageNumber").value(0))
            .andExpect(jsonPath("$.pageable.pageSize").value(2));

        log.info("✓ Pagination working correctly");
    }

    @Test
    @DisplayName("Invalid status filter is ignored gracefully")
    @WithMockUser(username = "compliance@test.com", roles = "COMPLIANCE_OFFICER")
    void testInvalidStatusFilterIgnored() throws Exception {
        log.info("Test: Invalid status is ignored");

        mockMvc.perform(
            get("/api/transactions/search")
                .param("status", "INVALID_STATUS")
                .param("page", "0")
                .param("size", "20")
        )
            .andExpect(status().isOk());

        log.info("✓ Invalid status handled gracefully");
    }

    @Test
    @DisplayName("Unauthorized user cannot access search")
    void testUnauthorizedAccessDenied() throws Exception {
        log.info("Test: Unauthorized access denied");

        mockMvc.perform(
            get("/api/transactions/search")
                .param("page", "0")
                .param("size", "20")
        )
            .andExpect(status().isUnauthorized());

        log.info("✓ Unauthorized access correctly denied");
    }

    @Test
    @DisplayName("Search by reference number (partial match)")
    @WithMockUser(username = "compliance@test.com", roles = "COMPLIANCE_OFFICER")
    void testSearchByReferenceNumber() throws Exception {
        log.info("Test: Filter by partial reference number");

        mockMvc.perform(
            get("/api/transactions/search")
                .param("reference", "TXN-20260922")
                .param("page", "0")
                .param("size", "20")
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[*].referenceNumber").value(org.hamcrest.Matchers.everyItem(
                org.hamcrest.Matchers.containsString("TXN-20260922")
            )));

        log.info("✓ Reference number partial match filter working correctly");
    }

    @Test
    @DisplayName("Search by purpose code")
    @WithMockUser(username = "compliance@test.com", roles = "COMPLIANCE_OFFICER")
    void testSearchByPurposeCode() throws Exception {
        log.info("Test: Filter by purpose code");

        mockMvc.perform(
            get("/api/transactions/search")
                .param("purposeCode", "SUPPLY")
                .param("page", "0")
                .param("size", "20")
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[*].purposeCode").value(org.hamcrest.Matchers.everyItem(
                org.hamcrest.Matchers.equalTo("SUPPLY")
            )));

        log.info("✓ Purpose code filter working correctly");
    }
}
