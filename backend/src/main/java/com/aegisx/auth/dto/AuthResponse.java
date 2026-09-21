package com.aegisx.auth.dto;

import com.aegisx.auth.entity.Role;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthResponse {

    private String token;
    private long expiresIn;
    private String tokenType;
    private UUID userId;
    private String email;
    private Role role;
}
