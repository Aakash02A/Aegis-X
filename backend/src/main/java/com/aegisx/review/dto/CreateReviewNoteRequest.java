package com.aegisx.review.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Request DTO for creating a new manual review note.
 * Used in POST /api/transactions/{id}/notes endpoint.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateReviewNoteRequest {

    /**
     * The note content.
     * Can be multi-line for detailed investigation findings.
     * 
     * Validation:
     * - Required (not blank)
     * - Between 10 and 5000 characters
     * - Trimmed of leading/trailing whitespace
     */
    @NotBlank(message = "Note text cannot be empty")
    @Size(min = 10, max = 5000, message = "Note must be between 10 and 5000 characters")
    private String noteText;
}
