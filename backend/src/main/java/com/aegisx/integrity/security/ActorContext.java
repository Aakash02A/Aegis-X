package com.aegisx.integrity.security;

import com.aegisx.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Resolves the acting identity for audit records.
 *
 * <p>Used only to attribute a decision to a person. The role string recorded here is what the
 * security layer already authorised the request with, so the audit trail cannot claim a role the
 * caller did not hold.</p>
 */
@Component
@RequiredArgsConstructor
public class ActorContext {

    public record Actor(UUID userId, String label, String role) { }

    private final UserRepository userRepository;

    public Actor resolve(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return new Actor(null, "ANONYMOUS", "NONE");
        }
        String email = authentication.getName();
        String role = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith("ROLE_"))
                .map(authority -> authority.substring("ROLE_".length()))
                .findFirst()
                .orElse("UNKNOWN");
        UUID userId = null;
        try {
            userId = userRepository.findByEmail(email).map(user -> user.getId()).orElse(null);
        } catch (RuntimeException e) {
            // Identity attribution must never block an operation the security layer already allowed.
            userId = null;
        }
        return new Actor(userId, email, role);
    }
}
