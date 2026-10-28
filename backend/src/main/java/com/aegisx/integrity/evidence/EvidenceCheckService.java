package com.aegisx.integrity.evidence;

import com.aegisx.integrity.domain.CertaintyLevel;
import com.aegisx.integrity.domain.EvidenceProcessingStatus;
import com.aegisx.integrity.domain.FindingType;
import com.aegisx.integrity.reconciliation.model.CandidateFactory;
import com.aegisx.integrity.reconciliation.model.FindingCandidate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The cross-record consistency checks: does the paperwork agree with the payment, the order and
 * itself?
 *
 * <p>Written as a pure function over an explicit input record rather than as a service that reads
 * the database. That is what makes the rules unit-testable without a Spring context and test data,
 * and it keeps the checks deterministic: the same facts always produce the same findings, so a
 * disagreement is always explainable by the data rather than by the order the query happened to
 * return rows in.</p>
 *
 * <p><strong>Every check states its own uncertainty.</strong> "The invoice total differs from the
 * order" is {@code LIKELY}, not certain, because the total was read from a document and reading can
 * be wrong. "A document names an order reference that is not this one" is {@code UNCERTAIN}, because
 * numbering schemes differ and only a person can say which case this is. A check that overstates its
 * certainty is worse than no check, because an operator learns to ignore the confident ones.</p>
 *
 * <p>No check concludes fraud, and no check has a side effect.</p>
 */
@Slf4j
@Component
public class EvidenceCheckService {

    /** The order as the checks see it. */
    public record OrderFacts(UUID orderId,
                             String internalReference,
                             String providerOrderId,
                             long expectedAmountMinor,
                             String currency) { }

    /**
     * One current document as the checks see it. Superseded versions are not passed in.
     *
     * <p>{@code uncertainFields} is already net of any fields a person has confirmed: the caller
     * subtracts the corrected fields before building these facts, so the checks never have to reason
     * about confirmation themselves.</p>
     */
    public record DocumentFacts(UUID documentId,
                                String documentType,
                                EvidenceProcessingStatus processingStatus,
                                String invoiceNumber,
                                Long amountMinor,
                                String currency,
                                String orderReference,
                                String paymentReference,
                                List<String> uncertainFields,
                                List<String> absentFields,
                                String fileHash,
                                String processingError) {

        public DocumentFacts {
            uncertainFields = uncertainFields == null ? List.of() : List.copyOf(uncertainFields);
            absentFields = absentFields == null ? List.of() : List.copyOf(absentFields);
        }
    }

    /**
     * @param requiredTypes          canonical document type names the order must have
     * @param amountToleranceMinor   permitted difference before a total is reported as a mismatch
     */
    public record Input(OrderFacts order,
                        List<DocumentFacts> documents,
                        List<String> requiredTypes,
                        long amountToleranceMinor) {

        public Input {
            documents = documents == null ? List.of() : List.copyOf(documents);
            requiredTypes = requiredTypes == null ? List.of() : List.copyOf(requiredTypes);
        }
    }

    /** Runs every check and returns the findings, ordered so the most actionable appear first. */
    public List<FindingCandidate> evaluate(Input input) {
        List<FindingCandidate> findings = new ArrayList<>();
        findings.addAll(missingRequiredEvidence(input));
        findings.addAll(unreadableDocuments(input));
        findings.addAll(uncertainExtractions(input));
        findings.addAll(amountDisagreements(input));
        findings.addAll(currencyDisagreements(input));
        findings.addAll(referenceDisagreements(input));
        findings.addAll(duplicateDocuments(input));
        log.debug("Evidence checks produced {} finding(s) for order {}",
                findings.size(), input.order().orderId());
        return List.copyOf(findings);
    }

    // ------------------------------------------------------------------- checks

    /**
     * A required document type has no current document.
     *
     * <p>One case per missing type rather than one per order: a merchant chasing an invoice and a
     * shipping bill has two separate conversations, and merging them into one case would hide one
     * behind the other.</p>
     */
    private List<FindingCandidate> missingRequiredEvidence(Input input) {
        List<FindingCandidate> findings = new ArrayList<>();
        for (String requiredType : input.requiredTypes()) {
            boolean present = input.documents().stream()
                    .anyMatch(document -> requiredType.equals(document.documentType()));
            if (present) {
                continue;
            }
            findings.add(CandidateFactory.of(
                    FindingType.MISSING_REQUIRED_EVIDENCE,
                    CertaintyLevel.CERTAIN,
                    input.order().orderId(), null, null, null,
                    input.order().orderId() + ":" + requiredType,
                    "Order " + reference(input.order()) + " requires a " + requiredType
                            + " and no current document of that type is held. The payment may still be "
                            + "correctly settled; it is the supporting record that is incomplete.",
                    List.of(CandidateFactory.describe("order internal reference", input.order().internalReference()),
                            CandidateFactory.describe("order expected amount (minor units)",
                                    input.order().expectedAmountMinor()),
                            CandidateFactory.describe("required document type", requiredType),
                            CandidateFactory.describe("current documents held", input.documents().size())),
                    List.of("a current " + requiredType + " for this order")));
        }
        return findings;
    }

    /** A document the engine cannot read. Its contents are unknown, which is not the same as absent. */
    private List<FindingCandidate> unreadableDocuments(Input input) {
        return input.documents().stream()
                .filter(document -> document.processingStatus() == EvidenceProcessingStatus.FAILED)
                .map(document -> CandidateFactory.of(
                        FindingType.EVIDENCE_UNREADABLE,
                        CertaintyLevel.CERTAIN,
                        input.order().orderId(), null, null, null,
                        document.documentId().toString(),
                        "The " + document.documentType() + " held for order "
                                + reference(input.order()) + " could not be read, so what it says is "
                                + "unknown. It has not been treated as a missing document.",
                        List.of(CandidateFactory.describe("document id", document.documentId()),
                                CandidateFactory.describe("document type", document.documentType()),
                                CandidateFactory.describe("processing status",
                                        document.processingStatus().name()),
                                CandidateFactory.describe("reported reason", nullToDash(document.processingError()))),
                        List.of("a legible copy of the " + document.documentType())))
                .toList();
    }

    /**
     * Values the engine relied on were read below the confidence threshold, and no one has confirmed
     * them.
     */
    private List<FindingCandidate> uncertainExtractions(Input input) {
        return input.documents().stream()
                .filter(document -> document.processingStatus() == EvidenceProcessingStatus.EXTRACTED)
                .filter(document -> !document.uncertainFields().isEmpty())
                .map(document -> CandidateFactory.of(
                        FindingType.EVIDENCE_EXTRACTION_UNCERTAIN,
                        CertaintyLevel.CERTAIN,
                        input.order().orderId(), null, null, null,
                        document.documentId().toString(),
                        "Values read from the " + document.documentType() + " for order "
                                + reference(input.order()) + " were read below the confidence threshold and "
                                + "have not been confirmed by a person: "
                                + String.join(", ", document.uncertainFields()) + ". Matching cannot rely on "
                                + "them until they are confirmed.",
                        List.of(CandidateFactory.describe("document id", document.documentId()),
                                CandidateFactory.describe("document type", document.documentType()),
                                CandidateFactory.describe("unread fields", document.uncertainFields()),
                                CandidateFactory.describe("fields not found", document.absentFields())),
                        List.of("human confirmation of each flagged field")))
                .toList();
    }

    /**
     * The total on the document disagrees with the amount the order expects.
     *
     * <p>Reported as {@code LIKELY} rather than certain: the total came from reading the document, and
     * a reading can be wrong. The finding names both amounts so an operator can see which side to
     * check.</p>
     *
     * <p>Suppressed when the total itself is one of the uncertain fields. Publishing a disagreement
     * based on a figure the engine has already said it may have misread would train an operator to
     * ignore the confident findings too.</p>
     */
    private List<FindingCandidate> amountDisagreements(Input input) {
        return input.documents().stream()
                .filter(document -> document.amountMinor() != null)
                .filter(document -> !document.uncertainFields().contains(
                        EvidenceFieldExtractor.FIELD_TOTAL))
                .filter(document -> Math.abs(document.amountMinor() - input.order().expectedAmountMinor())
                        > input.amountToleranceMinor())
                .map(document -> CandidateFactory.of(
                        FindingType.EVIDENCE_AMOUNT_MISMATCH,
                        CertaintyLevel.LIKELY,
                        input.order().orderId(), null, null, null,
                        document.documentId().toString(),
                        "The " + document.documentType() + " states a total of " + document.amountMinor()
                                + " minor units, while order " + reference(input.order()) + " expects "
                                + input.order().expectedAmountMinor()
                                + ". One of the two records is wrong; this does not by itself say which.",
                        List.of(CandidateFactory.describe("document id", document.documentId()),
                                CandidateFactory.describe("document type", document.documentType()),
                                CandidateFactory.describe("document total (minor units)",
                                        document.amountMinor()),
                                CandidateFactory.describe("order expected amount (minor units)",
                                        input.order().expectedAmountMinor()),
                                CandidateFactory.describe("difference (minor units)",
                                        document.amountMinor() - input.order().expectedAmountMinor())),
                        List.of("a corrected document or a corrected order amount, and a decision about which "
                                + "of the two is authoritative")))
                .toList();
    }

    private List<FindingCandidate> currencyDisagreements(Input input) {
        return input.documents().stream()
                .filter(document -> document.currency() != null)
                .filter(document -> !document.uncertainFields().contains(
                        EvidenceFieldExtractor.FIELD_CURRENCY))
                .filter(document -> !document.currency().equalsIgnoreCase(input.order().currency()))
                .map(document -> CandidateFactory.of(
                        FindingType.EVIDENCE_CURRENCY_MISMATCH,
                        CertaintyLevel.LIKELY,
                        input.order().orderId(), null, null, null,
                        document.documentId().toString(),
                        "The " + document.documentType() + " is denominated in " + document.currency()
                                + " while order " + reference(input.order()) + " is in "
                                + input.order().currency() + ". No conversion is assumed.",
                        List.of(CandidateFactory.describe("document id", document.documentId()),
                                CandidateFactory.describe("document currency", document.currency()),
                                CandidateFactory.describe("order currency", input.order().currency())),
                        List.of("confirmation of which currency the obligation was placed in")))
                .toList();
    }

    /**
     * A document names an order or payment reference that is not this one.
     *
     * <p>Deliberately reported as {@code UNCERTAIN}. A reference printed on a supplier's invoice may
     * be their own numbering rather than the merchant's, so a mismatch is a reason to look, not a
     * conclusion that the document is for a different payment. A check that called this
     * {@code CERTAIN} would generate a stream of false alarms.</p>
     */
    private List<FindingCandidate> referenceDisagreements(Input input) {
        List<FindingCandidate> findings = new ArrayList<>();
        for (DocumentFacts document : input.documents()) {
            List<String> mismatched = new ArrayList<>();
            if (document.orderReference() != null && !matchesOrderReference(input, document.orderReference())) {
                mismatched.add("orderReference=" + document.orderReference());
            }
            if (document.paymentReference() != null
                    && !matchesOrderReference(input, document.paymentReference())) {
                mismatched.add("paymentReference=" + document.paymentReference());
            }
            if (mismatched.isEmpty()) {
                continue;
            }
            findings.add(CandidateFactory.of(
                    FindingType.EVIDENCE_REFERENCE_MISMATCH,
                    CertaintyLevel.UNCERTAIN,
                    input.order().orderId(), null, null, null,
                    document.documentId().toString(),
                    "The " + document.documentType() + " names " + String.join(" and ", mismatched)
                            + ", which matches neither the internal reference nor the provider order id of order "
                            + reference(input.order()) + ". The document may belong to a different payment, or "
                            + "the supplier may simply use their own numbering.",
                    List.of(CandidateFactory.describe("document id", document.documentId()),
                            CandidateFactory.describe("document type", document.documentType()),
                            CandidateFactory.describe("references on the document", mismatched),
                            CandidateFactory.describe("order internal reference",
                                    input.order().internalReference()),
                            CandidateFactory.describe("order provider reference",
                                    nullToDash(input.order().providerOrderId()))),
                    List.of("confirmation that this document belongs to this payment")));
        }
        return findings;
    }

    /**
     * Two current documents that are the same document.
     *
     * <p>Identical bytes are {@code CERTAIN}. The same invoice number in two different files is
     * {@code LIKELY}: suppliers do reissue a document with the same number, so it needs a look rather
     * than an assertion.</p>
     */
    private List<FindingCandidate> duplicateDocuments(Input input) {
        List<FindingCandidate> findings = new ArrayList<>();

        Map<String, List<DocumentFacts>> byHash = input.documents().stream()
                .filter(document -> document.fileHash() != null)
                .collect(Collectors.groupingBy(DocumentFacts::fileHash));
        for (Map.Entry<String, List<DocumentFacts>> entry : byHash.entrySet()) {
            List<DocumentFacts> group = entry.getValue();
            if (group.size() < 2) {
                continue;
            }
            findings.add(duplicateFinding(input, group, CertaintyLevel.CERTAIN,
                    "Two current documents for order " + reference(input.order())
                            + " hold byte-identical content (" + entry.getKey().substring(0, 12)
                            + "...). One of them is redundant, or one of them is a re-upload that was not "
                            + "recognised as replacing the other."));
        }

        Map<String, List<DocumentFacts>> byInvoiceNumber = input.documents().stream()
                .filter(document -> document.invoiceNumber() != null && !document.invoiceNumber().isBlank())
                .collect(Collectors.groupingBy(
                        document -> document.invoiceNumber().toUpperCase(Locale.ROOT)));
        for (Map.Entry<String, List<DocumentFacts>> entry : byInvoiceNumber.entrySet()) {
            List<DocumentFacts> group = entry.getValue();
            if (group.size() < 2) {
                continue;
            }
            // Different bytes but the same stated invoice number: a reissue, or a genuine conflict.
            boolean sameBytes = group.stream().map(DocumentFacts::fileHash).distinct().count() == 1;
            if (sameBytes) {
                continue; // already reported above
            }
            findings.add(duplicateFinding(input, group, CertaintyLevel.LIKELY,
                    "Two current documents for order " + reference(input.order())
                            + " both state invoice number " + entry.getKey()
                            + " but their contents differ. Decide which one is authoritative."));
        }
        return findings;
    }

    private FindingCandidate duplicateFinding(Input input, List<DocumentFacts> group,
                                              CertaintyLevel certainty, String summary) {
        List<DocumentFacts> ordered = group.stream()
                .sorted(Comparator.comparing(document -> document.documentId().toString()))
                .toList();
        String entityKey = ordered.stream()
                .map(document -> document.documentId().toString())
                .collect(Collectors.joining("+"));
        List<String> evidence = ordered.stream()
                .map(document -> CandidateFactory.describe(
                        "document " + document.documentId(),
                        document.documentType() + ", hash " + shorten(document.fileHash())
                                + ", invoice number " + nullToDash(document.invoiceNumber())))
                .toList();
        return CandidateFactory.of(
                FindingType.DUPLICATE_EVIDENCE_DOCUMENT,
                certainty,
                input.order().orderId(), null, null, null,
                entityKey, summary, evidence,
                List.of("a decision about which of the listed documents is the authoritative one"));
    }

    // ------------------------------------------------------------------ helpers

    private static boolean matchesOrderReference(Input input, String candidate) {
        String normalised = normalise(candidate);
        if (normalised.isEmpty()) {
            return true;
        }
        return normalised.equals(normalise(input.order().internalReference()))
                || (input.order().providerOrderId() != null
                        && normalised.equals(normalise(input.order().providerOrderId())));
    }

    private static String normalise(String value) {
        return value == null ? "" : value.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
    }

    private static String reference(OrderFacts order) {
        return order.internalReference() == null ? order.orderId().toString() : order.internalReference();
    }

    private static String nullToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static String shorten(String hash) {
        return hash == null ? "-" : hash.substring(0, Math.min(12, hash.length()));
    }

    /** Convenience for callers that only need to know whether anything blocks completion. */
    public static boolean isBlocking(FindingCandidate candidate) {
        return isBlocking(candidate.type());
    }

    /**
     * Whether a finding prevents the evidence from being considered complete.
     *
     * <p>An uncertain value is deliberately <em>not</em> blocking: it is a request for a human, and the
     * readiness model expresses that as {@code READY_FOR_REVIEW} rather than as {@code ACTION_REQUIRED}.
     * Treating the two the same would tell a merchant their evidence is wrong when it is merely
     * unconfirmed.</p>
     */
    public static boolean isBlocking(FindingType type) {
        return switch (type) {
            case MISSING_REQUIRED_EVIDENCE, EVIDENCE_UNREADABLE, EVIDENCE_AMOUNT_MISMATCH,
                 EVIDENCE_CURRENCY_MISMATCH, EVIDENCE_REFERENCE_MISMATCH,
                 DUPLICATE_EVIDENCE_DOCUMENT, EVIDENCE_ORPHANED -> true;
            case EVIDENCE_EXTRACTION_UNCERTAIN -> false;
            default -> false;
        };
    }

    /** Extracted for readability at call sites that need the canonical type list. */
    public static List<String> canonicalTypes(List<com.aegisx.document.entity.DocumentType> types) {
        return types.stream()
                .filter(Objects::nonNull)
                .map(type -> type.getCanonicalType().name())
                .distinct()
                .toList();
    }

    /** Optional helper used by tests and the console to render a compact reason string. */
    public static String summarise(Optional<FindingCandidate> candidate) {
        return candidate.map(FindingCandidate::summary).orElse("");
    }
}
