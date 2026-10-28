package com.aegisx.integrity.evidence;

import com.aegisx.document.entity.DocumentType;
import com.aegisx.integrity.config.IntegrityProperties;
import com.aegisx.integrity.entity.PaymentOrder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides which supporting documents an order requires.
 *
 * <p>Deterministic and side-effect free: the same order always produces the same requirement list,
 * and the list depends on the order alone. It deliberately does <strong>not</strong> consult payment
 * state. Evidence is what justifies an obligation, so the obligation creates the requirement — a
 * pending payment does not excuse a missing invoice, and a captured payment does not supply one.</p>
 *
 * <p>Thresholds come from {@code aegisx.integrity.evidence.*} so the reasoning is inspectable rather
 * than compiled in. The tiers below are a documented default policy for a small marketplace, not a
 * regulatory position:</p>
 * <ul>
 *   <li>every order requires a commercial invoice — it is the record of what was owed;</li>
 *   <li>from 5,000.00 a shipping bill is also required — it evidences that goods actually moved;</li>
 *   <li>from 10,000.00 a certificate of origin is also required.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class EvidenceRequirementResolver {

    private final IntegrityProperties properties;

    /**
     * @param required  the document types this order must have, in the order they should be chased
     * @param rationale human-readable justification, shown in the console next to the requirement
     */
    public record Requirements(List<DocumentType> required, String rationale) {

        public Requirements {
            required = required == null ? List.of() : List.copyOf(required);
        }

        public boolean isEmpty() {
            return required.isEmpty();
        }
    }

    public Requirements resolve(PaymentOrder order) {
        IntegrityProperties.Evidence settings = properties.getEvidence();
        long amount = order.getExpectedAmountMinor();
        List<DocumentType> required = new ArrayList<>();

        if (amount >= settings.getInvoiceRequiredFromMinor()) {
            required.add(DocumentType.COMMERCIAL_INVOICE);
        }
        if (amount >= settings.getShippingBillRequiredFromMinor()) {
            required.add(DocumentType.SHIPPING_BILL);
        }
        if (amount >= settings.getCertificateOfOriginRequiredFromMinor()) {
            required.add(DocumentType.CERTIFICATE_OF_ORIGIN);
        }

        String rationale = required.isEmpty()
                ? "This order is below the configured threshold at which supporting documents are required"
                : "Required because the order value is " + order.getCurrency() + " "
                        + formatMajor(amount) + " and the configured evidence policy requires "
                        + required.size() + " document type(s) at that value";
        return new Requirements(required, rationale);
    }

    /** Renders integer minor units as a decimal string for operator-facing text. */
    static String formatMajor(long minorUnits) {
        long whole = minorUnits / 100;
        long fraction = Math.abs(minorUnits % 100);
        return whole + "." + (fraction < 10 ? "0" + fraction : Long.toString(fraction));
    }
}
