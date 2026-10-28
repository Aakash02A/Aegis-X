package com.aegisx.auth;

import com.aegisx.auth.dto.AuthResponse;
import com.aegisx.auth.dto.LoginRequest;
import com.aegisx.auth.dto.RegisterRequest;
import com.aegisx.auth.entity.Role;
import com.aegisx.auth.entity.User;
import com.aegisx.auth.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuthIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    /** A per-run address, so the test never depends on (or destroys) another test's rows. */
    private String email;

    @BeforeEach
    void setUp() {
        // This used to call userRepository.deleteAll(). Test classes share a Spring context and its
        // database, so a transaction created by another class could still reference a merchant and
        // the delete failed with a foreign-key violation depending on run order. A unique address
        // gives this test the clean slate it needs without touching anyone else's data.
        email = "abc.exports." + UUID.randomUUID() + "@example.com";
    }

    @Test
    void completeAuthenticationLifecycle() throws Exception {
        // 1. Register User
        RegisterRequest registerReq = RegisterRequest.builder()
                .fullName("ABC Exports Pvt Ltd")
                .email(email)
                .password("Password@123")
                .build();

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value("MERCHANT"));

        // Verify password is encrypted with BCrypt in DB
        Optional<User> savedUser = userRepository.findByEmail(email);
        assertTrue(savedUser.isPresent());
        assertTrue(savedUser.get().getPasswordHash().startsWith("$2a$") || savedUser.get().getPasswordHash().startsWith("$2b$"));

        // 2. Login with correct credentials
        LoginRequest loginReq = LoginRequest.builder()
                .email(email)
                .password("Password@123")
                .build();

        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").exists())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andReturn();

        String responseJson = loginResult.getResponse().getContentAsString();
        AuthResponse authResponse = objectMapper.readValue(responseJson, AuthResponse.class);
        String jwtToken = authResponse.getToken();
        assertNotNull(jwtToken);

        // 3. Access protected /api/auth/me with Bearer token
        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.fullName").value("ABC Exports Pvt Ltd"))
                .andExpect(jsonPath("$.role").value("MERCHANT"));

        // 4. Attempt login with wrong password
        LoginRequest wrongLoginReq = LoginRequest.builder()
                .email(email)
                .password("WrongPassword123")
                .build();

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(wrongLoginReq)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"));
    }
}
