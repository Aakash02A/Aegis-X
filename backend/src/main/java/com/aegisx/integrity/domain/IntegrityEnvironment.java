package com.aegisx.integrity.domain;

/**
 * Environment for an integrity workspace.
 *
 * <p>Deliberately has exactly one value. The prototype cannot be pointed at live payment
 * providers, which is what enforces the "test mode only" requirement at the type level
 * rather than by convention or configuration review.</p>
 */
public enum IntegrityEnvironment {
    TEST
}
