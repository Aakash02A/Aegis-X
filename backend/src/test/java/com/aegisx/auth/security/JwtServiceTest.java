package com.aegisx.auth.security;

import com.aegisx.auth.entity.Role;
import com.aegisx.auth.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {

    private JwtService jwtService;
    private User testUser;
    private org.springframework.security.core.userdetails.User userDetails;
    private Instant fixedInstant;
    private Clock fixedClock;

    @BeforeEach
    void setUp() {
        fixedInstant = Instant.parse("2026-09-21T10:00:00Z");
        fixedClock = Clock.fixed(fixedInstant, ZoneOffset.UTC);
        jwtService = new JwtService(fixedClock);

        // 256-bit test secret key
        String secret = "404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970";
        ReflectionTestUtils.setField(jwtService, "secretKey", secret);
        ReflectionTestUtils.setField(jwtService, "jwtExpirationMs", 3600000L); // 1 hour

        testUser = User.builder()
                .fullName("Test Merchant")
                .email("test@example.com")
                .passwordHash("hashed_pwd")
                .role(Role.MERCHANT)
                .build();
        testUser.setId(UUID.randomUUID());
        testUser.setCreatedAt(fixedInstant);
        testUser.setUpdatedAt(fixedInstant);

        userDetails = new org.springframework.security.core.userdetails.User(
                "test@example.com",
                "hashed_pwd",
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_MERCHANT"))
        );
    }

    @Test
    void generateTokenExtractsCorrectSubjectAndClaims() {
        String token = jwtService.generateToken(testUser);
        assertNotNull(token);

        String username = jwtService.extractUsername(token);
        assertEquals("test@example.com", username);

        String issuer = jwtService.extractClaim(token, io.jsonwebtoken.Claims::getIssuer);
        assertEquals("aegis-x", issuer);

        assertEquals("aegis-x-api", jwtService.extractClaim(token, claims -> claims.getAudience().iterator().next()));
    }

    @Test
    void isTokenValidReturnsTrueForMatchingUserDetails() {
        String token = jwtService.generateToken(testUser);
        assertTrue(jwtService.isTokenValid(token, userDetails));
    }

    @Test
    void isTokenValidReturnsFalseForDifferentUsername() {
        String token = jwtService.generateToken(testUser);
        org.springframework.security.core.userdetails.User otherUser =
                new org.springframework.security.core.userdetails.User(
                        "other@example.com",
                        "pwd",
                        Collections.singletonList(new SimpleGrantedAuthority("ROLE_MERCHANT"))
                );
        assertFalse(jwtService.isTokenValid(token, otherUser));
    }

    @Test
    void tokenExpiresDeterministicallyWithClock() {
        String token = jwtService.generateToken(testUser);
        assertTrue(jwtService.isTokenValid(token));

        // Advance clock past expiration (1 hour + 1 second)
        Clock advancedClock = Clock.fixed(fixedInstant.plus(Duration.ofHours(1)).plusSeconds(1), ZoneOffset.UTC);
        JwtService expiredJwtService = new JwtService(advancedClock);
        ReflectionTestUtils.setField(expiredJwtService, "secretKey", "404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970");
        ReflectionTestUtils.setField(expiredJwtService, "jwtExpirationMs", 3600000L);

        assertFalse(expiredJwtService.isTokenValid(token));
    }

    @Test
    void getExpirationTimeInSecondsCalculatesCorrectly() {
        assertEquals(3600L, jwtService.getExpirationTimeInSeconds());
    }
}

