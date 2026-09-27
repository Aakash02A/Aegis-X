package com.aegisx.auth.controller;

import com.aegisx.auth.dto.AuthResponse;
import com.aegisx.auth.dto.LoginRequest;
import com.aegisx.auth.dto.RegisterRequest;
import com.aegisx.auth.dto.UserResponse;
import com.aegisx.auth.entity.Role;
import com.aegisx.auth.service.UserService;
import com.aegisx.common.exception.EmailAlreadyExistsException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private UserService userService;

    @Test
    void registerReturnsCreatedWhenValid() throws Exception {
        RegisterRequest request = RegisterRequest.builder()
                .fullName("ABC Exports")
                .email("merchant@example.com")
                .password("Password@123")
                .build();

        UserResponse response = UserResponse.builder()
                .fullName("ABC Exports")
                .email("merchant@example.com")
                .role(Role.MERCHANT)
                .createdAt(Instant.now())
                .build();

        when(userService.register(any(RegisterRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("merchant@example.com"))
                .andExpect(jsonPath("$.role").value("MERCHANT"));
    }

    @Test
    void registerReturnsConflictWhenEmailAlreadyExists() throws Exception {
        RegisterRequest request = RegisterRequest.builder()
                .fullName("ABC Exports")
                .email("duplicate@example.com")
                .password("Password@123")
                .build();

        when(userService.register(any(RegisterRequest.class)))
                .thenThrow(new EmailAlreadyExistsException("Email already registered: duplicate@example.com"));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").value("Email already registered: duplicate@example.com"));
    }

    @Test
    void registerReturnsBadRequestWhenInvalidValidation() throws Exception {
        RegisterRequest request = RegisterRequest.builder()
                .fullName("")
                .email("invalid-email")
                .password("short")
                .build();

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"));
    }

    @Test
    void loginReturnsOkWithJwtToken() throws Exception {
        LoginRequest request = LoginRequest.builder()
                .email("merchant@example.com")
                .password("Password@123")
                .build();

        AuthResponse response = AuthResponse.builder()
                .token("valid_jwt_token")
                .expiresIn(86400L)
                .tokenType("Bearer")
                .userId(UUID.randomUUID())
                .email("merchant@example.com")
                .build();

        when(userService.login(any(LoginRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("valid_jwt_token"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.email").value("merchant@example.com"));
    }

    @Test
    void loginReturnsUnauthorizedWhenBadCredentials() throws Exception {
        LoginRequest request = LoginRequest.builder()
                .email("merchant@example.com")
                .password("WrongPassword")
                .build();

        when(userService.login(any(LoginRequest.class)))
                .thenThrow(new BadCredentialsException("Invalid credentials"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("Invalid credentials"));
    }

    @Test
    @WithMockUser(username = "merchant@example.com", roles = {"MERCHANT"})
    void getCurrentUserReturnsProfileWhenAuthenticated() throws Exception {
        UserResponse response = UserResponse.builder()
                .fullName("ABC Exports")
                .email("merchant@example.com")
                .role(Role.MERCHANT)
                .createdAt(Instant.now())
                .build();

        when(userService.getCurrentUser("merchant@example.com")).thenReturn(response);

        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("merchant@example.com"))
                .andExpect(jsonPath("$.fullName").value("ABC Exports"));
    }

    @Test
    void getCurrentUserReturnsForbiddenOrUnauthorizedWhenAnonymous() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());
    }
}
