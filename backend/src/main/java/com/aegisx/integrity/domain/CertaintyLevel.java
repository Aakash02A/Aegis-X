package com.aegisx.integrity.domain;

/**
 * How confident the system is in a classification, expressed as a discrete category.
 *
 * <p>Aegis-X deliberately does not emit a numeric confidence score: the specification requires a
 * defined, validated method before an arbitrary number may be presented as a probability, and no
 * such method has been validated for this prototype.</p>
 */
public enum CertaintyLevel {
    /** Two independent provider-authoritative sources agree, or the provider API itself stated it. */
    CERTAIN,
    /** A single provider-authoritative source supports it. */
    LIKELY,
    /** Only internal or inferred evidence supports it; a provider lookup is still required. */
    UNCERTAIN
}
