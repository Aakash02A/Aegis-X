package com.aegisx.integrity.evidence;

import com.aegisx.document.entity.DocumentType;
import com.aegisx.integrity.audit.IntegrityAuditService;
import com.aegisx.integrity.config.IntegrityProperties;
import com.aegisx.integrity.domain.EvidenceDocumentOrigin;
import com.aegisx.integrity.domain.EvidenceProcessingStatus;
import com.aegisx.integrity.entity.EvidenceDocument;
import com.aegisx.integrity.entity.PaymentOrder;
import com.aegisx.integrity.exception.IntegrityConflictException;
import com.aegisx.integrity.exception.IntegrityNotFoundException;
import com.aegisx.integrity.repository.EvidenceDocumentRepository;
import com.aegisx.integrity.repository.PaymentOrderRepository;
import com.aegisx.integrity.security.ActorContext;
import com.aegisx.storage.service.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.core.io.Resource;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Module I: receiving, validating and storing supporting documents for a payment.
 *
 * <p>Three decisions are worth stating explicitly because they differ from the older trade-document
 * module and the difference is deliberate:</p>
 *
 * <ol>
 *   <li><strong>Duplicate detection is real.</strong> The upload's SHA-256 is compared with the
 *       workspace's current documents. Re-uploading identical bytes is refused with a message naming
 *       the document that already holds them, instead of being accepted and silently doubling the
 *       evidence for one payment.</li>
 *   <li><strong>A correction supersedes, it does not overwrite.</strong> Uploading a new document of
 *       a type the order already has marks the previous one superseded and links the two. The old
 *       row is retained, so "the first invoice was replaced" remains visible. The older module
 *       instead rejected the second upload outright, which left a merchant with a wrong invoice
 *       unable to supply a right one.</li>
 *   <li><strong>Evidence cannot touch payment state.</strong> Nothing in this service writes to a
 *       payment record. Associating an invoice with an uncaptured payment cannot capture it, and a
 *       document mismatch cannot refund anything.</li>
 * </ol>
 *
 * <p>The file itself is stored through the existing {@link StorageService} abstraction, so this
 * module inherits the project's storage configuration rather than inventing a second one.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvidenceDocumentService {

    private final EvidenceDocumentRepository documentRepository;
    private final PaymentOrderRepository orderRepository;
    private final StorageService storageService;
    private final IntegrityAuditService auditService;
    private final IntegrityProperties properties;

    /** What the caller gets back, including the duplicate/supersede decision so it can be shown. */
    public record Receipt(EvidenceDocument document,
                          boolean supersededPrevious,
                          UUID supersededDocumentId) { }

    @Transactional
    public Receipt receive(UUID workspaceId, UUID orderId, DocumentType requestedType,
                           MultipartFile file, String filename, String mimeType, byte[] content,
                           String actorLabel, UUID actorId, boolean simulated) {
        PaymentOrder order = requireOrder(workspaceId, orderId);
        if (requestedType == null) {
            throw new IntegrityConflictException("A document type is required for every evidence document");
        }
        // Store the canonical type so the "one active document per type" rule cannot be bypassed by
        // uploading the same logical document under a legacy alias.
        DocumentType type = requestedType.getCanonicalType();

        validate(file, content, mimeType);
        String hash = sha256(content);

        Optional<EvidenceDocument> identical = documentRepository
                .findAllByWorkspaceIdAndFileHashAndSupersededAtIsNull(workspaceId, hash)
                .stream()
                .filter(candidate -> candidate.isActive())
                .findFirst();
        if (identical.isPresent()) {
            EvidenceDocument existing = identical.get();
            throw new IntegrityConflictException(
                    "These are the same bytes as " + existing.getOriginalFilename()
                            + " (" + existing.getDocumentType() + "), already stored for this workspace on "
                            + existing.getCreatedAt() + ". Supply the corrected document instead of a duplicate.");
        }

        Optional<EvidenceDocument> previousOfType = documentRepository
                .findByOrderIdAndDocumentTypeAndSupersededAtIsNull(orderId, type);

        String storagePath = store(file, content, orderId, type, filename);
        String storedFilename = storagePath.substring(storagePath.lastIndexOf('/') + 1);

        // The order of the three writes below is load-bearing, and it is deliberately NOT the order
        // Hibernate would pick on its own (it flushes inserts before updates).
        //
        // Only one document per (order, type) may be active, and the database enforces that with a
        // unique index over a marker derived from superseded_at. The previous document therefore has
        // to stop being active BEFORE its replacement is inserted: inserting first is refused by both
        // PostgreSQL's partial index and MySQL's generated-column index. But the previous document
        // cannot be linked to its replacement at that moment either, because
        // superseded_by_document_id is a foreign key and the replacement does not exist yet.
        //
        // The three steps resolve that cycle: stop being active, insert, then name the replacement.
        // Each is flushed explicitly so the ordering cannot silently depend on Hibernate's flush
        // sequence, and all three run in one transaction, so a failure anywhere rolls the whole
        // replacement back and leaves the original document active.
        EvidenceDocument document = EvidenceDocument.builder()
                .workspaceId(workspaceId)
                .orderId(orderId)
                .documentType(type)
                .origin(EvidenceDocumentOrigin.MERCHANT_UPLOAD)
                .originalFilename(filename)
                .storedFilename(storedFilename)
                .mimeType(mimeType)
                .fileSize(content.length)
                .fileHash(hash)
                .storagePath(storagePath)
                .uploadedBy(actorId)
                .actorLabel(actorLabel)
                .processingStatus(EvidenceProcessingStatus.RECEIVED)
                .supersedesDocumentId(previousOfType.map(EvidenceDocument::getId).orElse(null))
                .simulated(simulated)
                .build();

        // The database, not this method, is the arbiter of "one active document per (order, type)",
        // so a replaced document can lose a race. Two corrections of the same document may arrive
        // together: whichever inserts first takes the single active slot, and the other is refused by
        // the unique index (or rolled back as a deadlock victim). Both engines keep the invariant,
        // but they report the refusal as a data-access exception, which would reach the API layer as
        // an internal fault quoting engine text. Translating it here keeps the outcome honest and
        // actionable: nothing was changed, so reload and upload again.
        EvidenceDocument saved;
        try {
            if (previousOfType.isPresent()) {
                EvidenceDocument previous = previousOfType.get();
                previous.markSuperseded();
                previous.setFieldsConfirmed(false);
                documentRepository.saveAndFlush(previous);
            }

            saved = documentRepository.saveAndFlush(document);

            if (previousOfType.isPresent()) {
                EvidenceDocument previous = previousOfType.get();
                previous.supersedeWith(saved.getId());
                documentRepository.saveAndFlush(previous);
                audit(workspaceId, orderId, "EVIDENCE_DOCUMENT_SUPERSEDED", actorLabel, actorId, simulated,
                        Map.of("supersededDocumentId", previous.getId().toString(),
                                "replacementDocumentId", saved.getId().toString(),
                                "documentType", type.name()));
            }
        } catch (DataIntegrityViolationException | ConcurrencyFailureException e) {
            // The engine's detail is logged for an operator and never returned to the caller.
            log.warn("Evidence replacement (order {}, type {}) was refused by the database: {}",
                    orderId, type, e.getClass().getSimpleName(), e);
            throw new IntegrityConflictException(
                    "This document could not be made current: another change to this order's evidence was "
                            + "committed at the same moment. Nothing was changed. Reload the order and upload "
                            + "the document again.");
        }

        audit(workspaceId, orderId, "EVIDENCE_DOCUMENT_RECEIVED", actorLabel, actorId, simulated,
                detailOf(saved));

        log.info("Evidence document {} ({}) received for order {} in workspace {}",
                saved.getId(), type, orderId, workspaceId);
        return new Receipt(saved, previousOfType.isPresent(),
                previousOfType.map(EvidenceDocument::getId).orElse(null));
    }

    @Transactional(readOnly = true)
    public PaymentOrder requireOrder(UUID workspaceId, UUID orderId) {
        return orderRepository.findById(orderId)
                .filter(order -> order.getWorkspaceId().equals(workspaceId))
                .orElseThrow(() -> new IntegrityNotFoundException(
                        "Order " + orderId + " does not exist in this workspace"));
    }

    @Transactional(readOnly = true)
    public EvidenceDocument require(UUID workspaceId, UUID documentId) {
        return documentRepository.findByIdAndWorkspaceId(documentId, workspaceId)
                .orElseThrow(() -> new IntegrityNotFoundException(
                        "Evidence document " + documentId + " does not exist in this workspace"));
    }

    /** Only the documents that are current for their type, newest first. */
    @Transactional(readOnly = true)
    public List<EvidenceDocument> current(UUID workspaceId, UUID orderId) {
        requireOrder(workspaceId, orderId);
        return documentRepository.findAllByOrderIdAndSupersededAtIsNullOrderByCreatedAtDesc(orderId);
    }

    /** Full history including superseded versions, so a replacement is visible as a replacement. */
    @Transactional(readOnly = true)
    public List<EvidenceDocument> history(UUID workspaceId, UUID orderId) {
        requireOrder(workspaceId, orderId);
        return documentRepository.findAllByOrderIdOrderByCreatedAtDesc(orderId);
    }

    @Transactional(readOnly = true)
    public List<EvidenceDocument> currentInWorkspace(UUID workspaceId) {
        return documentRepository.findAllByWorkspaceIdAndSupersededAtIsNull(workspaceId);
    }

    /**
     * Streams the stored bytes back to an authorised caller.
     *
     * <p>Authorisation is performed by the caller against the workspace; this method only reads, and
     * refuses a document belonging to another workspace.
     * </p>
     */
    @Transactional(readOnly = true)
    public Resource load(UUID workspaceId, UUID documentId) {
        EvidenceDocument document = require(workspaceId, documentId);
        return storageService.load(document.getStoragePath());
    }

    @Transactional
    public void recordProcessingOutcome(EvidenceDocument document, EvidenceProcessingStatus status, String error) {
        document.setProcessingStatus(status);
        document.setProcessingError(error);
        documentRepository.save(document);
    }

    // ------------------------------------------------------------------ helpers

    private void validate(MultipartFile file, byte[] content, String mimeType) {
        if (file == null || file.isEmpty() || content == null || content.length == 0) {
            throw new IntegrityConflictException("The uploaded document is empty");
        }
        if (content.length > properties.getEvidence().getMaxDocumentBytes()) {
            throw new IntegrityConflictException(
                    "The document is " + content.length + " bytes, which exceeds the configured limit of "
                            + properties.getEvidence().getMaxDocumentBytes() + " bytes");
        }
        List<String> allowed = properties.getEvidence().getAllowedMimeTypes();
        if (mimeType == null || allowed.stream().noneMatch(value -> value.equalsIgnoreCase(mimeType))) {
            throw new IntegrityConflictException(
                    "Unsupported document type '" + mimeType + "'. Accepted: "
                            + String.join(", ", allowed));
        }
    }

    /**
     * Detects the MIME type from the bytes, never from the filename or the caller's claim.
     */
    public String detectMimeType(byte[] content) {
        try {
            return new Tika().detect(content);
        } catch (RuntimeException e) {
            throw new IntegrityConflictException("The uploaded document could not be identified");
        }
    }

    private String store(MultipartFile file, byte[] content, UUID orderId, DocumentType type, String filename) {
        try {
            return storageService.store(file, orderId, type);
        } catch (IOException e) {
            throw new IntegrityConflictException("The document could not be stored: " + e.getMessage());
        }
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception e) {
            throw new IntegrityConflictException("The document hash could not be computed");
        }
    }

    private Map<String, Object> detailOf(EvidenceDocument document) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("documentId", document.getId().toString());
        detail.put("documentType", document.getDocumentType().name());
        detail.put("originalFilename", document.getOriginalFilename());
        detail.put("fileSize", document.getFileSize());
        detail.put("fileHash", document.getFileHash());
        detail.put("mimeType", document.getMimeType());
        detail.put("origin", document.getOrigin().name());
        return detail;
    }

    private void audit(UUID workspaceId, UUID orderId, String eventType, String actorLabel, UUID actorId,
                       boolean simulated, Map<String, Object> detail) {
        auditService.record(eventType, "EVIDENCE", actorLabel, workspaceId, detail,
                orderId, null, null, null, null, null, actorId, simulated);
    }

    /** Reads the bytes of an upload once, so validation, hashing and storage agree on them. */
    public static byte[] readBytes(MultipartFile file) {
        try (InputStream stream = file.getInputStream()) {
            return stream.readAllBytes();
        } catch (IOException e) {
            throw new IntegrityConflictException("The uploaded document could not be read");
        }
    }

    /** Filename with any directory component removed, so nothing can escape the storage root. */
    public static String safeFilename(String originalFilename, String fallback) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return fallback;
        }
        String normalised = originalFilename.replace('\\', '/');
        String base = normalised.substring(normalised.lastIndexOf('/') + 1);
        String cleaned = base.replaceAll("[\\p{Cntrl}]", "").trim();
        if (cleaned.isEmpty() || cleaned.equals(".") || cleaned.equals("..")) {
            return fallback;
        }
        return cleaned.length() > 255 ? cleaned.substring(0, 255) : cleaned;
    }

    /** Lower-cased extension hint used only for messages and storage naming, never for validation. */
    static String extensionOf(String filename) {
        int dot = filename == null ? -1 : filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
