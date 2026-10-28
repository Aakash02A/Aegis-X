package com.aegisx.integrity;

import com.aegisx.integrity.support.MySqlIntegrityTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Transaction behaviour on a real MySQL 8.4 server, including the one engine difference the webhook
 * ingestion fix depends on.
 *
 * <p>The plan's §7 recorded that the two engines disagree about how far a constraint violation
 * reaches: MySQL rolls back the failing statement, PostgreSQL aborts the entire transaction. That
 * disagreement is not academic — it determines whether a caller that catches a duplicate-key error may
 * keep writing, which is exactly what idempotent webhook ingestion needs to do. It is measured here
 * rather than assumed, and the second test proves the mitigation (a {@code REQUIRES_NEW} write) really
 * does survive an outer rollback.</p>
 */
@EnabledIfEnvironmentVariable(named = "INTEGRITY_MYSQL_URL", matches = ".+")
@DisplayName("Transaction rollback on MySQL")
class MySqlTransactionRollbackTest extends MySqlIntegrityTestSupport {

    @Test
    @DisplayName("a failed write rolls the whole transaction back, not just the failing statement")
    void aFailedWriteRollsBackTheWholeTransaction() {
        UUID workspaceId = insertWorkspace("rollback-scope");
        UUID goodOrderId = UUID.randomUUID();
        String goodReference = "REF-rollback-good-" + goodOrderId;

        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> tx.execute(status -> {
            // A perfectly valid row, written first in the same transaction.
            jdbc().update(
                    "INSERT INTO pi_orders (id, workspace_id, internal_reference, expected_amount_minor, "
                            + "currency, state) VALUES (?,?,?,?,?,?)",
                    goodOrderId.toString(), workspaceId.toString(), goodReference, 1000L, "INR", "CREATED");

            // Then one the database refuses: lowercase currency violates chk_pi_orders_currency_format.
            jdbc().update(
                    "INSERT INTO pi_orders (id, workspace_id, internal_reference, expected_amount_minor, "
                            + "currency, state) VALUES (?,?,?,?,?,?)",
                    UUID.randomUUID().toString(), workspaceId.toString(),
                    "REF-rollback-bad-" + UUID.randomUUID(), 1000L, "inr", "CREATED");
            return null;
        }))
                .as("the invalid currency must be refused")
                .hasMessageContaining("chk_pi_orders_currency_format");

        assertThat(jdbc().queryForObject(
                "SELECT COUNT(*) FROM pi_orders WHERE internal_reference = ?",
                Integer.class, goodReference))
                .as("the valid row must be gone: a failed transaction leaves nothing partial behind, "
                        + "which is what makes a half-applied replacement impossible")
                .isZero();
    }

    @Test
    @DisplayName("a REQUIRES_NEW audit record survives the rollback of the transaction that asked for it")
    void auditRecordSurvivesAnOuterRollback() {
        UUID workspaceId = insertWorkspace("audit-independence");
        String marker = "ROLLBACK-PROBE-" + UUID.randomUUID();

        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> tx.execute(status -> {
            auditService.record("ROLLBACK_PROBE", "SYSTEM", "rollback-test", workspaceId,
                    Map.of("marker", marker), null, null, null, null, null, null, null, false);
            // Deliberately fail after the audit write, so the audit entry exists only if it was
            // committed independently of this transaction.
            throw new IllegalStateException("deliberate failure to force a rollback");
        }))
                .as("the outer transaction must fail as instructed")
                .isInstanceOf(IllegalStateException.class);

        String detail = jdbc().queryForObject(
                "SELECT detail_json FROM pi_audit_events WHERE workspace_id = ? AND event_type = ?",
                String.class, workspaceId.toString(), "ROLLBACK_PROBE");

        assertThat(detail)
                .as("the audit trail is written in its own transaction on purpose, so it records "
                        + "what was attempted even when the attempt is rolled back. Applying the same "
                        + "pattern to the webhook reservation is what makes duplicate recovery "
                        + "engine-independent")
                .contains(marker);
    }

    private UUID insertWorkspace(String namePrefix) {
        UUID id = UUID.randomUUID();
        jdbc().update(
                "INSERT INTO pi_workspaces (id, name, environment, provider_mode, transfer_capability, "
                        + "default_currency, staleness_threshold_seconds) VALUES (?,?,?,?,?,?,?)",
                id.toString(), namePrefix + "-" + id, "TEST", "SIMULATED", "OK", "INR", 900);
        return id;
    }
}
