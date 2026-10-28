package com.aegisx.integrity.evidence;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the fields the evidence checks actually need, out of already-extracted text.
 *
 * <p><strong>Why this is not the trade-document extractor.</strong> The compliance module's
 * {@code StructuredExtractionService} is tuned to find <em>a</em> plausible amount on a commercial
 * invoice, and falls back to "any number between 100 and 10,000,000" when it cannot find a labelled
 * one. That is acceptable when a human reviews every dossier. It is not acceptable here: a mistaken
 * total would be reported as a discrepancy against the payment, and an operator acting on a wrong
 * discrepancy can mis-state what a customer owes. So this extractor is deliberately strict — it
 * reads a total only from an explicitly labelled total, and it says so when it cannot.</p>
 *
 * <p>Every field carries its own confidence so the caller can publish exactly which values need a
 * human, rather than presenting a single blended score that hides the weak field. Amounts are
 * returned in <strong>minor units</strong>, matching the rest of the integrity module; the scale
 * comes from the currency's own definition, and an unrecognised currency is reported as uncertain
 * rather than assumed to have two decimal places.</p>
 *
 * <p>Nothing here can conclude that a document is genuine. Reading a value successfully is evidence
 * that the value is legible, not that it is true.</p>
 */
@Slf4j
@Component
public class EvidenceFieldExtractor {

    /** A field value plus how much the reader trusts it, on the same 0-100 scale as the pipeline. */
    public record Field(double confidence, boolean present) {

        public static Field absent() {
            return new Field(0.0, false);
        }
    }

    /**
     * The extraction result.
     *
     * @param values           field name to value, only for fields that were read
     * @param fieldConfidence  field name to confidence, for every field that was attempted
     * @param uncertainFields  attempted fields read below the caller's threshold
     * @param absentFields     attempted fields not found at all
     */
    public record Result(Map<String, Object> values,
                         Map<String, Double> fieldConfidence,
                         List<String> uncertainFields,
                         List<String> absentFields) {

        public Result {
            values = values == null ? Map.of() : Map.copyOf(values);
            fieldConfidence = fieldConfidence == null ? Map.of() : Map.copyOf(fieldConfidence);
            uncertainFields = uncertainFields == null ? List.of() : List.copyOf(uncertainFields);
            absentFields = absentFields == null ? List.of() : List.copyOf(absentFields);
        }

        public Optional<String> stringValue(String field) {
            Object value = values.get(field);
            return value == null ? Optional.empty() : Optional.of(value.toString());
        }

        public Optional<Long> amountMinor(String field) {
            Object value = values.get(field);
            return value instanceof Number number ? Optional.of(number.longValue()) : Optional.empty();
        }

        /** Mean confidence across attempted fields; zero when nothing was attempted. */
        public double overallConfidence() {
            if (fieldConfidence.isEmpty()) {
                return 0.0;
            }
            return fieldConfidence.values().stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        }
    }

    // Field names. Referenced by tests and by the correction endpoint, so they are constants.
    public static final String FIELD_INVOICE_NUMBER = "invoiceNumber";
    public static final String FIELD_INVOICE_DATE = "invoiceDate";
    public static final String FIELD_TOTAL = "total";
    public static final String FIELD_CURRENCY = "currency";
    public static final String FIELD_ORDER_REFERENCE = "orderReference";
    public static final String FIELD_PAYMENT_REFERENCE = "paymentReference";

    /*
     * A note on these label patterns.
     *
     * All three require either an explicit numbering keyword ("number", "no.", "id", ...) or a
     * separator (: or #) *between* the label and the value. Without that requirement the label word
     * itself gets captured: the patterns are case-insensitive, so on the text "COMMERCIAL INVOICE\n
     * Invoice Number: INV-77" a pattern with all-optional framing matched the word "Invoice" as the
     * invoice number. A meaningless identifier that looks authoritative is worse than no identifier,
     * so the framing is now mandatory rather than optional.
     */
    private static final Pattern INVOICE_NUMBER = Pattern.compile(
            "(?:invoice|bill|receipt)\\s*(?:number|no\\.?|#|id|ref(?:erence)?|[:#])\\s*[:#]?\\s*"
                    + "([A-Za-z0-9][A-Za-z0-9/\\-]{2,30})",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern ORDER_REFERENCE = Pattern.compile(
            "(?:order|purchase\\s*order|po)\\s*(?:number|no\\.?|#|id|ref(?:erence)?|[:#])\\s*[:#]?\\s*"
                    + "([A-Za-z0-9][A-Za-z0-9/\\-_]{2,30})",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern PAYMENT_REFERENCE = Pattern.compile(
            "(?:payment|transaction|txn|utr|reference)\\s*(?:number|no\\.?|#|id|ref(?:erence)?|[:#])\\s*"
                    + "[:#]?\\s*([A-Za-z0-9][A-Za-z0-9/\\-_]{4,40})",
            Pattern.CASE_INSENSITIVE);

    /**
     * A total must be explicitly labelled. The label set is intentionally narrow: "total", "amount
     * due", "balance due", "grand total". A bare "amount" is not accepted because invoices commonly
     * carry several sub-amounts (subtotal, tax amount, line amounts) and choosing the wrong one would
     * manufacture a false discrepancy.
     */
    private static final Pattern LABELLED_TOTAL = Pattern.compile(
            "(?:grand\\s+total|total\\s+(?:amount\\s+)?(?:due|payable)?|amount\\s+due|balance\\s+due|invoice\\s+total)"
                    + "\\s*[:=]?\\s*([A-Z]{3})?\\s*([$\\u20B9\\u20AC\\u00A3])?\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern CURRENCY_CODE = Pattern.compile("\\b([A-Z]{3})\\b");

    private static final Pattern DATE_CANDIDATE = Pattern.compile(
            "\\b(\\d{4}[/-]\\d{1,2}[/-]\\d{1,2}"
                    + "|\\d{1,2}[/-]\\d{1,2}[/-]\\d{4}"
                    + "|\\d{1,2}\\s+(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\s+\\d{4})\\b",
            Pattern.CASE_INSENSITIVE);

    private static final DateTimeFormatter[] DATE_FORMATS = {
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("MM/dd/yyyy"),
            DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMMM yyyy", java.util.Locale.ENGLISH)
    };

    private static final Map<String, String> SYMBOL_TO_CODE = Map.of(
            "$", "USD", "\u20B9", "INR", "\u20AC", "EUR", "\u00A3", "GBP");

    /**
     * @param rawText            text already read from the document by Tika or Tesseract
     * @param confidenceThreshold fields below this are published as uncertain
     */
    public Result extract(String rawText, double confidenceThreshold) {
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, Double> confidence = new LinkedHashMap<>();
        List<String> uncertain = new ArrayList<>();
        List<String> absent = new ArrayList<>();

        if (rawText == null || rawText.isBlank()) {
            // Nothing legible. Report every field as absent rather than as zero-confidence guesses.
            for (String field : List.of(FIELD_INVOICE_NUMBER, FIELD_INVOICE_DATE, FIELD_TOTAL,
                    FIELD_CURRENCY, FIELD_ORDER_REFERENCE, FIELD_PAYMENT_REFERENCE)) {
                confidence.put(field, 0.0);
                absent.add(field);
            }
            return new Result(values, confidence, uncertain, absent);
        }

        readInvoiceNumber(rawText, values, confidence);
        readInvoiceDate(rawText, values, confidence);
        readCurrencyAndTotal(rawText, values, confidence);
        readReference(ORDER_REFERENCE, FIELD_ORDER_REFERENCE, rawText, values, confidence);
        readReference(PAYMENT_REFERENCE, FIELD_PAYMENT_REFERENCE, rawText, values, confidence);

        for (Map.Entry<String, Double> entry : confidence.entrySet()) {
            if (entry.getValue() <= 0.0) {
                absent.add(entry.getKey());
            } else if (entry.getValue() < confidenceThreshold) {
                uncertain.add(entry.getKey());
            }
        }
        log.debug("Evidence field extraction: confident={} uncertain={} absent={}",
                values.keySet(), uncertain, absent);
        return new Result(values, confidence, uncertain, absent);
    }

    private void readInvoiceNumber(String text, Map<String, Object> values, Map<String, Double> confidence) {
        Matcher matcher = INVOICE_NUMBER.matcher(text);
        if (matcher.find()) {
            String candidate = matcher.group(1).trim();
            values.put(FIELD_INVOICE_NUMBER, candidate);
            // An explicit label was present, so this is a confident read; the value itself is not
            // validated against anything at this stage.
            confidence.put(FIELD_INVOICE_NUMBER, 90.0);
        } else {
            confidence.put(FIELD_INVOICE_NUMBER, 0.0);
        }
    }

    private void readInvoiceDate(String text, Map<String, Object> values, Map<String, Double> confidence) {
        Matcher matcher = DATE_CANDIDATE.matcher(text);
        while (matcher.find()) {
            String candidate = matcher.group(1);
            for (DateTimeFormatter format : DATE_FORMATS) {
                try {
                    LocalDate parsed = LocalDate.parse(candidate, format);
                    values.put(FIELD_INVOICE_DATE, parsed.toString());
                    // Two of the accepted formats are ambiguous for day/month order, so the read is
                    // published as a moderate-confidence value rather than a certain one.
                    confidence.put(FIELD_INVOICE_DATE, 75.0);
                    return;
                } catch (Exception ignored) {
                    // Try the next accepted format.
                }
            }
        }
        confidence.put(FIELD_INVOICE_DATE, 0.0);
    }

    private void readCurrencyAndTotal(String text, Map<String, Object> values, Map<String, Double> confidence) {
        Matcher total = LABELLED_TOTAL.matcher(text);
        if (total.find()) {
            String code = total.group(1);
            String symbol = total.group(2);
            String amount = total.group(3);
            String resolvedCode = resolveCurrency(code, symbol, text);

            long minorUnits;
            try {
                minorUnits = toMinorUnits(new BigDecimal(amount.replace(",", "")), resolvedCode);
            } catch (RuntimeException e) {
                log.debug("Could not interpret the labelled total '{}': {}", amount, e.getMessage());
                confidence.put(FIELD_TOTAL, 0.0);
                confidence.put(FIELD_CURRENCY, 0.0);
                return;
            }
            values.put(FIELD_TOTAL, minorUnits);
            // The value came from an explicit total label, which is the strongest signal available.
            confidence.put(FIELD_TOTAL, 85.0);
            if (resolvedCode == null) {
                confidence.put(FIELD_CURRENCY, 0.0);
            } else {
                boolean explicitOnTotalLine = code != null || symbol != null;
                values.put(FIELD_CURRENCY, resolvedCode);
                // A currency taken from elsewhere in the document is a weaker read than one printed
                // on the total line, and the difference is visible to the operator.
                confidence.put(FIELD_CURRENCY, explicitOnTotalLine ? 95.0 : 60.0);
            }
            return;
        }
        confidence.put(FIELD_TOTAL, 0.0);
        confidence.put(FIELD_CURRENCY, 0.0);
    }

    private String resolveCurrency(String codeOnTotalLine, String symbol, String text) {
        if (codeOnTotalLine != null && !codeOnTotalLine.isBlank()) {
            String upper = codeOnTotalLine.toUpperCase(java.util.Locale.ROOT);
            if (isKnownCurrency(upper)) {
                return upper;
            }
        }
        if (symbol != null) {
            String mapped = SYMBOL_TO_CODE.get(symbol);
            if (mapped != null) {
                return mapped;
            }
        }
        Matcher matcher = CURRENCY_CODE.matcher(text);
        while (matcher.find()) {
            String candidate = matcher.group(1);
            if (isKnownCurrency(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean isKnownCurrency(String code) {
        try {
            Currency.getInstance(code);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Converts a decimal major-unit amount to integer minor units using the currency's own scale.
     *
     * @throws IllegalStateException when the currency is unknown, because assuming two decimal places
     *                               would silently corrupt the value for currencies that have none.
     */
    static long toMinorUnits(BigDecimal major, String currencyCode) {
        if (currencyCode == null || currencyCode.isBlank()) {
            throw new IllegalStateException("currency unknown, cannot scale the amount");
        }
        int scale = Currency.getInstance(currencyCode.toUpperCase(java.util.Locale.ROOT)).getDefaultFractionDigits();
        if (scale < 0) {
            throw new IllegalStateException(currencyCode + " has no defined decimal scale");
        }
        return major.setScale(scale, RoundingMode.UNNECESSARY).movePointRight(scale).longValueExact();
    }

    private void readReference(Pattern pattern, String field, String text,
                               Map<String, Object> values, Map<String, Double> confidence) {
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            String candidate = matcher.group(1).trim();
            values.put(field, candidate);
            // Confident about what the document says, because an explicit label preceded the value.
            // The uncertainty here is interpretive, not optical: a reference may be the supplier's own
            // numbering rather than the merchant's. That is expressed by the finding's certainty level
            // rather than by pretending the read was unreliable, which would make the comparison
            // impossible to act on.
            confidence.put(field, 80.0);
        } else {
            confidence.put(field, 0.0);
        }
    }
}
