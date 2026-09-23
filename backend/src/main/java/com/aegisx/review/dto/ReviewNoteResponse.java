package com.aegisx.review.dto;

import com.aegisx.review.entity.ManualReviewNote;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for a manual review note.
 * Used in GET and POST responses for review notes endpoints.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewNoteResponse {

    /**
     * Unique identifier for this note.
     */
    private UUID id;

    /**
     * ID of the transaction this note belongs to.
     */
    private UUID transactionId;

    /**
     * ID of the author (compliance officer or admin) who wrote the note.
     */
    private UUID authorId;

    /**
     * Full name of the author.
     * Useful for UI display without requiring separate user lookup.
     */
    private String authorName;

    /**
     * Email of the author.
     * Useful for audit trail and contact information.
     */
    private String authorEmail;

    /**
     * The note content.
     */
    private String noteText;

    /**
     * Timestamp when note was created.
     */
    private Instant createdAt;

    /**
     * Factory method to convert entity to DTO.
     *
     * @param note the ManualReviewNote entity
     * @return the response DTO
     */
    public static ReviewNoteResponse from(ManualReviewNote note) {
        return ReviewNoteResponse.builder()
            .id(note.getId())
            .transactionId(note.getTransaction().getId())
            .authorId(note.getAuthor().getId())
            .authorName(note.getAuthor().getFullName())
            .authorEmail(note.getAuthor().getEmail())
            .noteText(note.getNoteText())
            .createdAt(note.getCreatedAt())
            .build();
    }
}
