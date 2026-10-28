package com.aegisx.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Public registration request.
 * 
 * Security Note: Role is not accepted from the client.
 * All new registrations are assigned MERCHANT role by default.
 * Role escalation to ADMIN or COMPLIANCE_OFFICER requires
 * separate administrator operations outside the public API.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegisterRequest {

    @NotBlank(message = "Full name is required")
    @Size(max = 120, message = "Full name must not exceed 120 characters")
    private String fullName;

    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    private String email;

    @NotBlank(message = "Password is required")
    @Size(min = 8, message = "Password must be at least 8 characters long")
    private String password;

    /**
     * Optional WhatsApp contact number. Stored in international format without the leading '+' so
     * it can be handed to the WhatsApp Cloud API as-is.
     */
    @Size(max = 32, message = "Phone must not exceed 32 characters")
    @Pattern(regexp = "^$|^\\+?[0-9 ()-]{7,32}$", message = "Phone must be a valid international number")
    private String phone;
}
