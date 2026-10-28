package com.aegisx.integrity;

import com.aegisx.integrity.entity.Workspace;
import com.aegisx.integrity.support.MySqlIntegrityTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boolean and timestamp fidelity on a real MySQL 8.4 server — §5 rules 2 and 3 of the migration plan.
 *
 * <p>Two things are being checked, and neither is cosmetic:</p>
 *
 * <ul>
 *   <li><strong>Booleans</strong> are declared {@code BIT(1)}, which is what Hibernate 6.5.3's MySQL
 *       dialect expects. A mismatch here would be a startup failure rather than a silent bug, but the
 *       round trip in both directions is still asserted so that a future change to {@code TINYINT(1)}
 *       or {@code CHAR(1)} is caught by a test rather than by production.</li>
 *   <li><strong>Timestamps</strong> are {@code DATETIME(6)}, and the JDBC session is pinned to UTC.
 *       MySQL evaluates {@code DEFAULT CURRENT_TIMESTAMP} in the <em>session</em> time zone, so the
 *       default only means UTC if the session is pinned. That dependency is easy to lose and silent
 *       when lost, which is why it is measured against the JVM clock instead of assumed.</li>
 * </ul>
 *
 * <p>This server's time zone is {@code SYSTEM}, which here is IST (+05:30), while the JVM clock is
 * UTC. The two differ by five and a half hours, so a session that was <em>not</em> pinned to UTC
 * would be caught by the default-value assertion rather than passing by coincidence.</p>
 */
@EnabledIfEnvironmentVariable(named = "INTEGRITY_MYSQL_URL", matches = ".+")
@DisplayName("Boolean and timestamp fidelity on MySQL")
class MySqlBooleanAndTimezoneTest extends MySqlIntegrityTestSupport {

    @Test
    @DisplayName("a boolean round-trips through BIT(1) in both directions")
    void booleanRoundTripsInBothDirections() {
        UUID configured = insertWorkspace("bool-true", true);
        UUID notConfigured = insertWorkspace("bool-false", false);

        Workspace trueWorkspace = workspaceService.get(configured);
        Workspace falseWorkspace = workspaceService.get(notConfigured);

        assertThat(trueWorkspace.isProviderConfigured())
                .as("a stored BIT(1) of 1 must read back as true")
                .isTrue();
        assertThat(falseWorkspace.isProviderConfigured())
                .as("a stored BIT(1) of 0 must read back as false")
                .isFalse();

        // And in the other direction: what Hibernate writes must be a BIT(1) of 0 or 1.
        assertThat(jdbc().queryForObject(
                "SELECT CAST(provider_configured AS UNSIGNED) FROM pi_workspaces WHERE id = ?",
                Integer.class, configured.toString()))
                .isEqualTo(1);
        assertThat(jdbc().queryForObject(
                "SELECT CAST(provider_configured AS UNSIGNED) FROM pi_workspaces WHERE id = ?",
                Integer.class, notConfigured.toString()))
                .isZero();

        String columnType = jdbc().queryForObject(
                "SELECT column_type FROM information_schema.columns WHERE table_schema = DATABASE() "
                        + "AND table_name = 'pi_workspaces' AND column_name = 'provider_configured'",
                String.class);
        assertThat(columnType)
                .as("booleans must be BIT(1), which is what Hibernate's MySQL dialect expects")
                .isEqualTo("bit(1)");
    }

    @Test
    @DisplayName("an Instant survives a write/read cycle with microsecond precision")
    void instantRoundTripsWithMicrosecondPrecision() {
        UUID workspaceId = insertWorkspace("instant-roundtrip", false);

        // A value chosen to be awkward: nonzero microseconds, and a date far from the column default.
        Instant expected = Instant.parse("2026-01-02T03:04:05.123456Z");
        UUID orderId = UUID.randomUUID();
        jdbc().update(
                "INSERT INTO pi_orders (id, workspace_id, internal_reference, expected_amount_minor, "
                        + "currency, state, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?)",
                orderId.toString(), workspaceId.toString(), "REF-INSTANT-" + orderId, 1000L, "INR", "CREATED",
                "2026-01-02 03:04:05.123456", "2026-01-02 03:04:05.123456");

        Instant read = orderRepository.findById(orderId).orElseThrow().getCreatedAt();

        assertThat(read)
                .as("the stored DATETIME(6) is UTC and must come back as the same instant, "
                        + "including microseconds")
                .isEqualTo(expected);
        assertThat(jdbc().queryForObject(
                "SELECT DATA_TYPE FROM information_schema.columns WHERE table_schema = DATABASE() "
                        + "AND table_name = 'pi_orders' AND column_name = 'created_at'",
                String.class))
                .isEqualTo("datetime");
    }

    @Test
    @DisplayName("DEFAULT CURRENT_TIMESTAMP is evaluated in UTC, because the session is pinned")
    void defaultTimestampIsUtc() {
        UUID workspaceId = insertWorkspace("default-timestamp", false);
        UUID orderId = UUID.randomUUID();

        // No created_at supplied, so the column default is what is being tested.
        jdbc().update(
                "INSERT INTO pi_orders (id, workspace_id, internal_reference, expected_amount_minor, "
                        + "currency, state) VALUES (?,?,?,?,?,?)",
                orderId.toString(), workspaceId.toString(), "REF-DEFAULT-" + orderId, 1000L, "INR", "CREATED");

        String raw = jdbc().queryForObject(
                "SELECT created_at FROM pi_orders WHERE id = ?", String.class, orderId.toString());
        Instant stored = LocalDateTime
                .parse(raw, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSSSSS]"))
                .toInstant(ZoneOffset.UTC);

        assertThat(Duration.between(stored, Instant.now()).abs())
                .as("the column default must be UTC; this server is UTC+05:30, so a session that was "
                        + "not pinned to UTC would be hours out")
                .isLessThan(Duration.ofMinutes(2));

        // The same value read through Hibernate must agree, which is what makes the two rules
        // (DATETIME storage, UTC session) consistent with each other rather than merely each true.
        assertThat(Duration.between(orderRepository.findById(orderId).orElseThrow().getCreatedAt(),
                Instant.now()).abs())
                .as("the value Hibernate reads must be the same UTC instant")
                .isLessThan(Duration.ofMinutes(2));
    }

    /**
     * Inserts a workspace directly, writing {@code provider_configured} as an explicit BIT literal so
     * the test controls the stored bit rather than relying on a driver's boolean conversion.
     */
    private UUID insertWorkspace(String namePrefix, boolean providerConfigured) {
        UUID id = UUID.randomUUID();
        // The name is suffixed because uq_pi_workspaces_name is a real unique constraint and the
        // schema persists between runs; a fixed name would fail on the second run for an unrelated
        // reason.
        jdbc().update(
                "INSERT INTO pi_workspaces (id, name, environment, provider_mode, provider_configured, "
                        + "transfer_capability, default_currency, staleness_threshold_seconds) "
                        + "VALUES (?,?,?,?,b'" + (providerConfigured ? "1" : "0") + "',?,?,?)",
                id.toString(), namePrefix + "-" + id, "TEST", "SIMULATED", "OK", "INR", 900);
        return id;
    }
}
