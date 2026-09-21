package com.aegisx.auth.service;

import com.aegisx.auth.dto.AuthResponse;
import com.aegisx.auth.dto.LoginRequest;
import com.aegisx.auth.dto.RegisterRequest;
import com.aegisx.auth.dto.UserResponse;
import com.aegisx.auth.entity.Role;
import com.aegisx.auth.entity.User;
import com.aegisx.auth.repository.UserRepository;
import com.aegisx.auth.security.JwtService;
import com.aegisx.common.exception.EmailAlreadyExistsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    @Mock
    private AuthenticationManager authenticationManager;

    @InjectMocks
    private UserService userService;

    private User sampleUser;

    @BeforeEach
    void setUp() {
        sampleUser = User.builder()
                .fullName("ABC Exports")
                .email("merchant@example.com")
                .passwordHash("encoded_password")
                .role(Role.MERCHANT)
                .build();
        sampleUser.setId(UUID.randomUUID());
        sampleUser.setCreatedAt(Instant.now());
        sampleUser.setUpdatedAt(Instant.now());
    }

    @Test
    void registerSuccessfullyCreatesUser() {
        RegisterRequest request = RegisterRequest.builder()
                .fullName("ABC Exports")
                .email("merchant@example.com")
                .password("Password@123")
                .role(Role.MERCHANT)
                .build();

        when(userRepository.existsByEmail("merchant@example.com")).thenReturn(false);
        when(passwordEncoder.encode("Password@123")).thenReturn("encoded_password");
        when(userRepository.save(any(User.class))).thenReturn(sampleUser);

        UserResponse response = userService.register(request);

        assertNotNull(response);
        assertEquals(sampleUser.getId(), response.getId());
        assertEquals("ABC Exports", response.getFullName());
        assertEquals("merchant@example.com", response.getEmail());
        assertEquals(Role.MERCHANT, response.getRole());
        verify(userRepository, times(1)).save(any(User.class));
    }

    @Test
    void registerThrowsExceptionWhenEmailAlreadyExists() {
        RegisterRequest request = RegisterRequest.builder()
                .fullName("Duplicate User")
                .email("merchant@example.com")
                .password("Password@123")
                .role(Role.MERCHANT)
                .build();

        when(userRepository.existsByEmail("merchant@example.com")).thenReturn(true);

        assertThrows(EmailAlreadyExistsException.class, () -> userService.register(request));
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void loginSuccessfullyReturnsAuthResponse() {
        LoginRequest request = LoginRequest.builder()
                .email("merchant@example.com")
                .password("Password@123")
                .build();

        when(userRepository.findByEmail("merchant@example.com")).thenReturn(Optional.of(sampleUser));
        when(jwtService.generateToken(sampleUser)).thenReturn("sample_jwt_token");
        when(jwtService.getExpirationTimeInSeconds()).thenReturn(86400L);

        AuthResponse response = userService.login(request);

        assertNotNull(response);
        assertEquals("sample_jwt_token", response.getToken());
        assertEquals(86400L, response.getExpiresIn());
        assertEquals("Bearer", response.getTokenType());
        assertEquals(sampleUser.getId(), response.getUserId());
        assertEquals("merchant@example.com", response.getEmail());
        assertEquals(Role.MERCHANT, response.getRole());
        verify(authenticationManager, times(1)).authenticate(any(UsernamePasswordAuthenticationToken.class));
    }

    @Test
    void loginThrowsBadCredentialsWhenAuthenticationFails() {
        LoginRequest request = LoginRequest.builder()
                .email("merchant@example.com")
                .password("WrongPassword")
                .build();

        doThrow(new BadCredentialsException("Bad credentials"))
                .when(authenticationManager)
                .authenticate(any(UsernamePasswordAuthenticationToken.class));

        assertThrows(BadCredentialsException.class, () -> userService.login(request));
    }

    @Test
    void getCurrentUserReturnsUserResponse() {
        when(userRepository.findByEmail("merchant@example.com")).thenReturn(Optional.of(sampleUser));

        UserResponse response = userService.getCurrentUser("merchant@example.com");

        assertNotNull(response);
        assertEquals(sampleUser.getId(), response.getId());
        assertEquals("ABC Exports", response.getFullName());
        assertEquals("merchant@example.com", response.getEmail());
    }

    @Test
    void getCurrentUserThrowsUsernameNotFoundExceptionWhenUserDoesNotExist() {
        when(userRepository.findByEmail("nonexistent@example.com")).thenReturn(Optional.empty());

        assertThrows(UsernameNotFoundException.class, () -> userService.getCurrentUser("nonexistent@example.com"));
    }
}
