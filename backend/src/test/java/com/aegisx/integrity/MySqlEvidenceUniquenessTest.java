package com.aegisx.integrity;

import com.aegisx.document.entity.DocumentType;
import com.aegisx.integrity.entity.EvidenceDocument;
import com.aegisx.integrity.entity.PaymentOrder;
import com.aegisx.integrity.entity.Workspace;
import com.aegisx.integrity.evidence.EvidenceIntelligenceService;
import com.aegisx.integrity.support.EvidenceFixtures;
import com.aegisx.integrity.support.MySqlIntegrityTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the replacement for PostgreSQL's partial unique index on a real MySQL 8.4 server.
 *
 * <p>PostgreSQL enforces <em>at most one active document per (order, type)</em> with a partial index
 * {@code ... WHERE superseded_by_document_id IS NULL}. MySQL has no partial indexes, so the baseline
 * reproduces the predicate inside the key with a VIRTUAL generated column that is NULL for superseded
 * rows. This test checks the substitution is faithful in both directions, on the engine that will
 * actually run it:</p>
 *
 * <ul>
 *   <li>a second <em>active</em> document of the same type must be refused by the database;</li>
 *   <li>any number of <em>superseded</em> documents of the same type must coexist;</li>
 *   <li>the real supersede flow the application performs must succeed.</li>
 * </ul>
 *
 * <p>The third case is the one the H2 suite cannot see. H2 builds its schema from the entity model,
 * which does not declare this index at all, so on H2 the index does not exist and the flow passes for
 * the wrong reason. Here it has to survive the real constraint.</p>
 */
@EnabledIfEnvironmentVariable(named = "INTEGRITY_MYSQL_URL", matches = ".+")
@DisplayName("Evidence uniqueness on MySQL")
class MySqlEvidenceUniquenessTest extends MySqlIntegrityTestSupport {

    private static final long ORDER_AMOUNT = 249_000L;

    /** Inserts a document row directly, so what is under test is the database, not the service. */
    private String insertDocument(UUID workspaceId, UUID orderId, String type, String filename, String hash) {
        String id = UUID.randomUUID().toString();
        jdbc().update(
                "INSERT INTO pi_evidence_documents (id, workspace_id, order_id, document_type, "
                        + "original_filename, stored_filename, mime_type, file_size, file_hash, "
                        + "storage_path, actor_label) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                id, workspaceId.toString(), orderId.toString(), type,
                filename, filename, "application/pdf", 1024L, hash, "test/" + filename, "test-actor");
        return id;
    }

    /**
     * Inserts a document and immediately marks it superseded by {@code replacementId}.
     *
     * <p>Both columns are written: {@code superseded_at} is what makes the row inactive, and
     * {@code superseded_by_document_id} names the replacement. Setting only the latter would leave
     * the row active as far as the unique index is concerned, which is the whole point of V28.</p>
     */
    private void insertSupersededDocument(UUID workspaceId, UUID orderId, String type, String filename,
                                          String hash, String replacementId) {
        jdbc().update(
                "INSERT INTO pi_evidence_documents (id, workspace_id, order_id, document_type, "
                        + "original_filename, stored_filename, mime_type, file_size, file_hash, "
                        + "storage_path, actor_label, superseded_at, superseded_by_document_id) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,NOW(6),?)",
                UUID.randomUUID().toString(), workspaceId.toString(), orderId.toString(), type,
                filename, filename, "application/pdf", 1024L, hash, "test/" + filename, "test-actor",
                replacementId);
    }

    @Test
    @DisplayName("a second ACTIVE document of the same (order, type) is refused by the database itself")
    void secondActiveDocumentOfTheSameTypeIsRefusedByTheDatabase() {
        Workspace workspace = newWorkspace();
        PaymentOrder order = newOrderWithProviderOrder(workspace, ORDER_AMOUNT);

        insertDocument(workspace.getId(), order.getId(), "COMMERCIAL_INVOICE", "a.pdf", "a".repeat(64));

        assertThatThrownBy(() -> insertDocument(workspace.getId(), order.getId(), "COMMERCIAL_INVOICE",
                "b.pdf", "b".repeat(64)))
                .as("the generated-column unique index must reject a second active document")
                .isInstanceOf(DataIntegrityViolationException.class);

        // A different type is a different key, so it must still be allowed: the guard is per type.
        insertDocument(workspace.getId(), order.getId(), "PACKING_LIST", "c.pdf", "c".repeat(64));

        assertThat(jdbc().queryForObject(
                "SELECT COUNT(*) FROM pi_evidence_documents WHERE order_id = ?",
                Integer.class, order.getId().toString()))
                .as("the refused insert must not have left a row behind")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("any number of SUPERSEDED documents of the same type coexist")
    void manySupersededDocumentsOfTheSameTypeCoexist() {
        Workspace workspace = newWorkspace();
        PaymentOrder order = newOrderWithProviderOrder(workspace, ORDER_AMOUNT);

        // One active row, then three superseded rows of the same type. On a naive
        // UNIQUE (order_id, document_type) this would be impossible; under the PostgreSQL partial
        // index it is legal, and it must stay legal here.
        String activeId = insertDocument(workspace.getId(), order.getId(), "COMMERCIAL_INVOICE",
                "active.pdf", "d".repeat(64));
        for (int i = 0; i < 3; i++) {
            // superseded_by_document_id is a real foreign key, so the replacement it names must
            // exist — here the active row stands in as the replacement.
            insertSupersededDocument(workspace.getId(), order.getId(), "COMMERCIAL_INVOICE",
                    "old" + i + ".pdf", String.valueOf((char) ('e' + i)).repeat(64), activeId);
        }

        assertThat(jdbc().queryForObject(
                "SELECT COUNT(*) FROM pi_evidence_documents WHERE order_id = ? AND document_type = ?",
                Integer.class, order.getId().toString(), "COMMERCIAL_INVOICE"))
                .as("one active plus three superseded documents of the same type must coexist")
                .isEqualTo(4);

        assertThat(jdbc().queryForObject(
                "SELECT COUNT(*) FROM pi_evidence_documents WHERE order_id = ? "
                        + "AND active_document_type IS NOT NULL",
                Integer.class, order.getId().toString()))
                .as("exactly one row is active")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a corrected upload supersedes the original through the real service, on MySQL")
    void correctedUploadSupersedesTheOriginalOnMySql() {
        Workspace workspace = newWorkspace();
        PaymentOrder order = newOrderWithProviderOrder(workspace, ORDER_AMOUNT);

        byte[] firstBytes = EvidenceFixtures.invoicePdf("INV-2026-7001", order.getInternalReference(),
                order.getCurrency(), "2490.00");
        EvidenceIntelligenceService.UploadOutcome first = uploadEvidence(workspace, order,
                DocumentType.COMMERCIAL_INVOICE, "invoice-7001.pdf", firstBytes);
        assertThat(first.supersededPrevious()).isFalse();

        // Byte-identical re-upload is refused by the content-hash guard.
        assertThatThrownBy(() -> uploadEvidence(workspace, order, DocumentType.COMMERCIAL_INVOICE,
                "invoice-7001-copy.pdf", firstBytes))
                .hasMessageContaining("same bytes");

        // A genuinely corrected invoice of the same type: the application supersedes the old row and
        // links the two. The database must permit this transition, which is exactly the step the
        // generated-column unique index governs.
        EvidenceIntelligenceService.UploadOutcome replacement = uploadEvidence(workspace, order,
                DocumentType.COMMERCIAL_INVOICE, "invoice-7001-revised.pdf",
                EvidenceFixtures.invoicePdf("INV-2026-7001R", order.getInternalReference(),
                        order.getCurrency(), "2490.00"));

        assertThat(replacement.supersededPrevious())
                .as("the corrected invoice must be recorded as replacing the original")
                .isTrue();
        assertThat(replacement.supersededDocumentId()).isEqualTo(first.document().getId());

        List<EvidenceDocument> current = evidenceService.documents(workspace.getId(), order.getId(), false);
        List<EvidenceDocument> history = evidenceService.documents(workspace.getId(), order.getId(), true);

        assertThat(current)
                .as("only the corrected invoice is current")
                .hasSize(1)
                .allMatch(EvidenceDocument::isActive);
        assertThat(history)
                .as("the superseded original is retained so the correction is visible")
                .hasSize(2)
                .anyMatch(document -> document.getSupersededByDocumentId() != null);

        // The stored marker must agree with the application's view of which row is active: the
        // superseded row's marker is NULL, the replacement's is its document type. If these ever
        // diverge, the database would be enforcing a different invariant from the code.
        assertThat(jdbc().queryForList(
                "SELECT id, active_document_type FROM pi_evidence_documents WHERE order_id = ?",
                order.getId().toString()))
                .anySatisfy(row -> assertThat(row.get("active_document_type"))
                        .as("the replacement is the row the database treats as active")
                        .isEqualTo("COMMERCIAL_INVOICE"))
                .anySatisfy(row -> assertThat(row.get("active_document_type"))
                        .as("the superseded original is not")
                        .isNull());
    }

    @Test
    @DisplayName("the active-document marker is derived, and flips when the row is superseded")
    void theActiveMarkerIsDerived() {
        Workspace workspace = newWorkspace();
        PaymentOrder order = newOrderWithProviderOrder(workspace, ORDER_AMOUNT);

        String activeId = insertDocument(workspace.getId(), order.getId(), "SHIPPING_BILL",
                "ship.pdf", "f".repeat(64));

        assertThat(jdbc().queryForObject(
                "SELECT active_document_type FROM pi_evidence_documents WHERE id = ?",
                String.class, activeId))
                .as("an active row's marker equals its document type")
                .isEqualTo("SHIPPING_BILL");

        // Supersede it with a document that really exists, so the foreign key is satisfied.
        String replacementId = insertDocument(workspace.getId(), order.getId(), "PACKING_LIST",
                "replacement.pdf", "0".repeat(64));
        // Both columns together: superseded_at is the activity marker, superseded_by_document_id the
        // provenance. This is exactly what EvidenceDocumentService writes, in the same two steps.
        jdbc().update("UPDATE pi_evidence_documents SET superseded_at = NOW(6), "
                        + "superseded_by_document_id = ? WHERE id = ?",
                replacementId, activeId);

        assertThat(jdbc().queryForObject(
                "SELECT active_document_type FROM pi_evidence_documents WHERE id = ?",
                String.class, activeId))
                .as("a superseded row's marker becomes NULL, which is what frees the unique slot")
                .isNull();
    }
}
