package com.aegisx.settlement;

import com.aegisx.auth.entity.Role;
import com.aegisx.auth.entity.User;
import com.aegisx.auth.repository.UserRepository;
import com.aegisx.settlement.dto.SettlementRequest;
import com.aegisx.settlement.entity.PaymentProcessor;
import com.aegisx.settlement.service.SettlementService;
import com.aegisx.transaction.entity.Transaction;
import com.aegisx.transaction.entity.TransactionStatus;
import com.aegisx.transaction.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SettlementControllerAuthorizationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private SettlementService settlementService;

    @Test
    void merchantsCanReadOnlyTheirOwnSettlements() throws Exception {
        User owner = createMerchant("settlement-owner-");
        User otherMerchant = createMerchant("settlement-other-");
        Transaction transaction = transactionRepository.saveAndFlush(Transaction.builder()
                .referenceNumber("TXN-SETTLEMENT-ACCESS-" + UUID.randomUUID())
                .merchant(owner)
                .amount(new BigDecimal("1000.00"))
                .currency("USD")
                .beneficiaryName("Settlement Beneficiary")
                .purposeCode("P0101")
                .status(TransactionStatus.APPROVED)
                .version(0L)
                .build());
        var settlement = settlementService.initiateSettlement(SettlementRequest.createBasic(
                transaction.getId(), PaymentProcessor.MOCK, new BigDecimal("1000.00"), "USD"), owner.getEmail());

        String settlementPath = "/api/v1/settlements/" + settlement.getId();
        String referencePath = "/api/v1/settlements/reference/" + settlement.getSettlementReference();
        String transactionPath = "/api/v1/settlements/transaction/" + transaction.getId();

        mockMvc.perform(get(settlementPath).with(merchant(owner.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(settlement.getId().toString()));
        mockMvc.perform(get(referencePath).with(merchant(owner.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.settlementReference").value(settlement.getSettlementReference()));
        mockMvc.perform(get(transactionPath).with(merchant(owner.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(settlement.getId().toString()));

        mockMvc.perform(get(settlementPath).with(merchant(otherMerchant.getEmail())))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(referencePath).with(merchant(otherMerchant.getEmail())))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(transactionPath).with(merchant(otherMerchant.getEmail())))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/settlements/status/INITIATED").with(merchant(otherMerchant.getEmail())))
                .andExpect(status().isForbidden());
    }

    private User createMerchant(String prefix) {
        return userRepository.saveAndFlush(User.builder()
                .fullName("Settlement Merchant")
                .email(prefix + UUID.randomUUID() + "@aegis-x.test")
                .passwordHash("not-used-by-mock-authentication")
                .role(Role.MERCHANT)
                .build());
    }

    private static SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor merchant(String email) {
        return SecurityMockMvcRequestPostProcessors.user(email).roles("MERCHANT");
    }
}
