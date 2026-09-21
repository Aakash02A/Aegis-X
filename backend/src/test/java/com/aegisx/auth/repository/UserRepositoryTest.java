package com.aegisx.auth.repository;

import com.aegisx.auth.entity.Role;
import com.aegisx.auth.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
class UserRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private UserRepository userRepository;

    @Test
    void findByEmailReturnsUserWhenExists() {
        User user = User.builder()
                .fullName("ABC Exports")
                .email("abc@example.com")
                .passwordHash("hashed_pwd_123")
                .role(Role.MERCHANT)
                .build();

        entityManager.persistAndFlush(user);

        Optional<User> found = userRepository.findByEmail("abc@example.com");
        assertTrue(found.isPresent());
        assertEquals("ABC Exports", found.get().getFullName());
        assertNotNull(found.get().getId());
        assertNotNull(found.get().getCreatedAt());
        assertNotNull(found.get().getUpdatedAt());
    }

    @Test
    void existsByEmailReturnsTrueWhenUserExists() {
        User user = User.builder()
                .fullName("Compliance Officer")
                .email("officer@example.com")
                .passwordHash("hashed_pwd_123")
                .role(Role.COMPLIANCE_OFFICER)
                .build();

        entityManager.persistAndFlush(user);

        assertTrue(userRepository.existsByEmail("officer@example.com"));
        assertFalse(userRepository.existsByEmail("other@example.com"));
    }

    @Test
    void duplicateEmailThrowsException() {
        User user1 = User.builder()
                .fullName("User One")
                .email("unique@example.com")
                .passwordHash("pwd1")
                .role(Role.MERCHANT)
                .build();

        User user2 = User.builder()
                .fullName("User Two")
                .email("unique@example.com")
                .passwordHash("pwd2")
                .role(Role.ADMIN)
                .build();

        entityManager.persistAndFlush(user1);

        assertThrows(Exception.class, () -> {
            entityManager.persistAndFlush(user2);
        });
    }
}
