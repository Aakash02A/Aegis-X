package com.aegisx.auth.service;

import com.aegisx.auth.dto.AuthResponse;
import com.aegisx.auth.dto.LoginRequest;
import com.aegisx.auth.dto.RegisterRequest;
import com.aegisx.auth.dto.UserResponse;
import com.aegisx.auth.entity.Role;
import com.aegisx.auth.repository.UserRepository;
import com.aegisx.common.exception.EmailAlreadyExistsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class UserServiceTest {

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    private String email;

    @BeforeEach
    void setUp() {
        email = "merchant-" + UUID.randomUUID() + "@example.com";
    }

    @Test
    void registerSuccessfullyCreatesUser() {
        UserResponse response = userService.register(registerRequest("Password@123"));

        assertNotNull(response.getId());
        assertEquals("ABC Exports", response.getFullName());
        assertEquals(email, response.getEmail());
        assertEquals(Role.MERCHANT, response.getRole());
        assertTrue(userRepository.findByEmail(email).orElseThrow().getPasswordHash().startsWith("$2"));
    }

    @Test
    void registerThrowsExceptionWhenEmailAlreadyExists() {
        userService.register(registerRequest("Password@123"));

        assertThrows(EmailAlreadyExistsException.class,
                () -> userService.register(registerRequest("AnotherPassword@123")));
    }

    @Test
    void loginSuccessfullyReturnsAuthResponse() {
        userService.register(registerRequest("Password@123"));

        AuthResponse response = userService.login(LoginRequest.builder()
                .email(email)
                .password("Password@123")
                .build());

        assertNotNull(response.getToken());
        assertTrue(response.getExpiresIn() > 0);
        assertEquals("Bearer", response.getTokenType());
        assertEquals(email, response.getEmail());
        assertEquals(Role.MERCHANT, response.getRole());
    }

    @Test
    void loginThrowsBadCredentialsWhenAuthenticationFails() {
        userService.register(registerRequest("Password@123"));

        assertThrows(BadCredentialsException.class, () -> userService.login(LoginRequest.builder()
                .email(email)
                .password("WrongPassword")
                .build()));
    }

    @Test
    void getCurrentUserReturnsUserResponse() {
        UserResponse registered = userService.register(registerRequest("Password@123"));

        UserResponse current = userService.getCurrentUser(email.toUpperCase());

        assertEquals(registered.getId(), current.getId());
        assertEquals("ABC Exports", current.getFullName());
        assertEquals(email, current.getEmail());
    }

    @Test
    void getCurrentUserThrowsUsernameNotFoundExceptionWhenUserDoesNotExist() {
        assertThrows(UsernameNotFoundException.class, () -> userService.getCurrentUser(email));
    }

    private RegisterRequest registerRequest(String password) {
        return RegisterRequest.builder()
                .fullName("ABC Exports")
                .email(email)
                .password(password)
                .build();
    }
}
