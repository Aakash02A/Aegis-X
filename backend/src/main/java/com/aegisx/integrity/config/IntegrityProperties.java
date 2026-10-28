package com.aegisx.integrity.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Configuration for the payment integrity engine.
 *
 * <p>Secrets are read from the environment only. {@link #allowFinancialExecution} defaults to
 * {@code false} so that the prototype cannot perform a financial side effect even if an operator
 * approves one — the approval is recorded and execution is refused with an explicit outcome.</p>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "aegisx.integrity")
public class IntegrityProperties {

    /** Age after which a still-pending provider operation counts as stale. */
    private Duration stalenessThreshold = Duration.ofMinutes(15);

    private String defaultCurrency = "INR";

    /** Retain raw webhook payloads for this many days, then purge them in place. */
    private int payloadRetentionDays = 30;

    /** Hard cap on stored payload size; larger bodies are truncated with a marker. */
    private int maxStoredPayloadBytes = 65536;

    /** When false (default), approved FINANCIAL recommendations are never executed. */
    private boolean allowFinancialExecution = false;

    /** Where benchmark result artefacts are written. */
    private String benchmarkOutputDir = "benchmark/results";

    private final Razorpay razorpay = new Razorpay();
    private final Evidence evidence = new Evidence();

    @Getter
    @Setter
    public static class Razorpay {
        /** Test Mode API base. Never point this at a live key pair. */
        private String baseUrl = "https://api.razorpay.com/v1";
        private String keyId = "";
        private String keySecret = "";
        private String webhookSecret = "";
        /**
         * Route is an account-level product. It cannot be detected reliably without a real API call,
         * so it must be asserted explicitly; otherwise transfer checks are suppressed as
         * "capability not enabled" rather than reported as mismatches.
         */
        private boolean routeEnabled = false;
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration readTimeout = Duration.ofSeconds(10);

        public boolean isConfigured() {
            return keyId != null && !keyId.isBlank() && keySecret != null && !keySecret.isBlank();
        }

        /** Non-secret hint for the UI: {@code rzp_test_****abcd}. */
        public String maskedKeyId() {
            if (keyId == null || keyId.isBlank()) {
                return null;
            }
            String suffix = keyId.length() <= 4 ? keyId : keyId.substring(keyId.length() - 4);
            return "rzp_****" + suffix;
        }
    }

    /**
     * Evidence-intelligence settings.
     *
     * <p>Every threshold here is a documented default rather than a magic number buried in a rule,
     * so an operator can see why a document was required or why a value was flagged as uncertain.</p>
     */
    @Getter
    @Setter
    public static class Evidence {

        /**
         * A commercial invoice is required from this order amount upwards, in minor units.
         * Zero means always: without an invoice there is no record of what was owed.
         */
        private long invoiceRequiredFromMinor = 0L;

        /** A shipping bill is additionally required from this order amount upwards (5,000.00). */
        private long shippingBillRequiredFromMinor = 500_000L;

        /** A certificate of origin is additionally required from this amount upwards (10,000.00). */
        private long certificateOfOriginRequiredFromMinor = 1_000_000L;

        /**
         * A field read below this confidence is published as uncertain and must be confirmed by a
         * person. Deliberately high: an unconfirmed value must not silently drive matching.
         */
        private double confidenceThreshold = 70.0;

        /** Largest evidence document accepted, in bytes. */
        private long maxDocumentBytes = 20L * 1024 * 1024;

        /** MIME types Tika is allowed to report for an accepted document. */
        private List<String> allowedMimeTypes = List.of("application/pdf", "image/jpeg", "image/png");

        /**
         * Tolerance applied when comparing a document total with the order amount, in minor units.
         * Zero by default: rounding differences are a finding to explain, not something to hide.
         */
        private long amountToleranceMinor = 0L;
    }

}
