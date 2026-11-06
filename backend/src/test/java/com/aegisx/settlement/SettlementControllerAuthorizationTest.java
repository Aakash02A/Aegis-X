package com.aegisx.settlement;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SettlementControllerAuthorizationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser(username = "merchant@aegis-x.test", roles = "MERCHANT")
    void merchantCannotReadSettlementByIdOrReferenceOrTransaction() throws Exception {
        UUID id = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/settlements/{id}", id))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/settlements/reference/{reference}", "STL-PRIVATE"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/settlements/transaction/{id}", id))
                .andExpect(status().isForbidden());
    }
}
