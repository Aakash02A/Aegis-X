package com.aegisx.integrity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the MySQL migration work end to end against a <em>real</em> MySQL 8.4 server:
 * Flyway builds the schema from empty using {@code db/migration}, and Hibernate
 * <em>validates</em> that schema against the entity model.
 *
 * <p>Hibernate validation is not asserted in a line of code — it is a precondition of the
 * context starting at all. {@code ddl-auto=validate} with {@code flyway.enabled=true} means
 * that if any entity disagreed with the migrated schema, this test would fail while creating
 * the Spring context. That is the same mechanism that caught the {@code confidence_score}
 * and {@code updated_at} drift on PostgreSQL (V24, V25).</p>
 *
 * <p>Skipped unless {@code INTEGRITY_MYSQL_URL} is set, so a plain {@code mvn test} run is
 * unaffected. See the header of {@code application-mysqlverify.yml} for the command.</p>
 */
@SpringBootTest
@ActiveProfiles({"test", "mysqlverify"})
@EnabledIfEnvironmentVariable(named = "INTEGRITY_MYSQL_URL", matches = ".+")
class MySqlSchemaMigrationTest {

    /** Every table the MySQL baseline must create. 34 objects, mirroring V1..V27. */
    private static final List<String> EXPECTED_TABLES = List.of(
            // compliance / auth module
            "users", "transactions", "documents", "required_documents", "document_extractions",
            "ai_analysis", "policy_evaluations", "policy_violations", "compliance_reviews",
            "evidence_checklists", "manual_review_notes", "audit_events", "settlements",
            "notification_messages", "webhook_events", "audit_event_types",
            // payment integrity engine
            "pi_workspaces", "pi_orders", "pi_payments", "pi_refunds", "pi_transfers",
            "pi_webhook_events", "pi_webhook_deliveries", "pi_provider_observations",
            "pi_state_transitions", "pi_reconciliation_runs", "pi_reconciliation_findings",
            "pi_exceptions", "pi_recovery_recommendations", "pi_review_actions", "pi_audit_events",
            // evidence intelligence
            "pi_evidence_documents", "pi_evidence_extractions", "pi_evidence_readiness");

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    @Test
    void flywayAppliedTheBaselineToAnEmptyDatabaseAndHibernateValidatedIt() {
        JdbcTemplate jdbc = jdbc();

        List<String> applied = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank",
                String.class);
        assertThat(applied)
                .as("Flyway must have recorded the MySQL baseline as applied")
                .contains("1");

        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()",
                String.class);
        assertThat(tables)
                .as("the MySQL baseline must create every table the PostgreSQL history creates")
                .containsAll(EXPECTED_TABLES);
    }

    @Test
    void noApplicationColumnUsesTheTimestampType() {
        // MySQL TIMESTAMP converts through the session time zone and ends in 2038. Every
        // instant column must be DATETIME(6); finding a single TIMESTAMP column here means a
        // silent time-zone bug was introduced by the port.
        // flyway_schema_history is Flyway's own bookkeeping table, not part of the application
        // schema, and Flyway chooses its column types itself. Every other table is ours.
        List<Map<String, Object>> timestamps = jdbc().queryForList(
                "SELECT table_name, column_name FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND data_type = 'timestamp' "
                        + "AND table_name <> 'flyway_schema_history'");
        assertThat(timestamps)
                .as("no application column may use MySQL TIMESTAMP; use DATETIME(6)")
                .isEmpty();

        // Listed explicitly rather than counted, so that adding a column cannot quietly satisfy the
        // assertion and so that microsecond precision is actually checked on every one of them.
        List<String> microsecondInstantColumns = jdbc().queryForList(
                "SELECT CONCAT(table_name, '.', column_name) FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() "
                        + "AND table_name IN ('pi_payments','pi_orders','pi_webhook_events') "
                        + "AND column_name IN ('created_at','updated_at','received_at') "
                        + "AND data_type = 'datetime' AND datetime_precision = 6",
                String.class);
        assertThat(microsecondInstantColumns)
                .as("every Instant column must be DATETIME(6), to microsecond precision")
                .containsExactlyInAnyOrder(
                        "pi_orders.created_at", "pi_orders.updated_at",
                        "pi_payments.created_at", "pi_payments.updated_at",
                        "pi_webhook_events.created_at", "pi_webhook_events.updated_at",
                        "pi_webhook_events.received_at");
    }

    @Test
    void moneyIsNeverStoredAsFloatingPoint() {
        String amountType = jdbc().queryForObject(
                "SELECT data_type FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'pi_payments' "
                        + "AND column_name = 'amount_minor'",
                String.class);
        assertThat(amountType).isEqualTo("bigint");

        String settlementAmount = jdbc().queryForObject(
                "SELECT data_type FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'settlements' "
                        + "AND column_name = 'settlement_amount'",
                String.class);
        assertThat(settlementAmount).isEqualTo("decimal");

        List<Map<String, Object>> floatMoney = jdbc().queryForList(
                "SELECT table_name, column_name, data_type FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND data_type IN ('float','double') "
                        + "AND (column_name LIKE '%amount%' OR column_name LIKE '%fee%' "
                        + "OR column_name LIKE '%price%' OR column_name LIKE '%rate%') "
                        + "AND column_name <> 'exchange_rate'");
        assertThat(floatMoney).as("no monetary column may be floating point").isEmpty();
    }

    @Test
    void theDuplicateGuardsThatPreventDoubleBusinessEffectsExist() {
        // The webhook idempotency guard: one row per unique provider event in a workspace. V30 scopes
        // it by workspace because provider event ids are allocated per provider account, so the
        // previous (provider, provider_event_id) key made one workspace's delivery look like a
        // duplicate of another's.
        assertUniqueIndexExists("pi_webhook_events", "uq_pi_webhook_events_workspace_provider_event");
        // The exception dedupe guard: one case per (workspace, finding, entity).
        assertUniqueIndexExists("pi_exceptions", "uq_pi_exceptions_dedupe");
        // One evidence readiness row per order.
        assertUniqueIndexExists("pi_evidence_readiness", "uq_pi_evidence_readiness_order");
        // Exactly one ACTIVE evidence document per (order, type) — the partial index of V26.
        assertUniqueIndexExists("pi_evidence_documents", "uq_pi_evidence_doc_active_type");
        // The four provider-identifier guards that port directly from the partial indexes.
        assertUniqueIndexExists("pi_orders", "uq_pi_orders_provider_order");
        assertUniqueIndexExists("pi_payments", "uq_pi_payments_provider_payment");
        assertUniqueIndexExists("pi_refunds", "uq_pi_refunds_provider_refund");
        assertUniqueIndexExists("pi_transfers", "uq_pi_transfers_provider_transfer");
    }

    @Test
    void theActiveDocumentMarkerIsAGeneratedColumn() {
        // The uniqueness guard above only works because active_document_type is derived and
        // is NULL for superseded rows. If someone ever "simplifies" it into a plain column,
        // the invariant silently changes meaning, so assert how it is produced.
        Map<String, Object> column = jdbc().queryForMap(
                "SELECT extra, generation_expression FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'pi_evidence_documents' "
                        + "AND column_name = 'active_document_type'");
        assertThat(String.valueOf(column.get("extra")))
                .as("the active-document marker must be a generated column")
                .contains("GENERATED");
        // Rebuilt on superseded_at by V28. It must not be derived from superseded_by_document_id:
        // that column is a foreign key to the replacement, so a marker derived from it cannot be
        // cleared before the replacement exists, which made every supersede fail on a real engine.
        assertThat(String.valueOf(column.get("generation_expression")))
                .as("it must be NULL exactly when the document has been superseded")
                .containsIgnoringCase("superseded_at")
                .doesNotContainIgnoringCase("superseded_by_document_id");
    }

    @Test
    void identifiersStayPortableAcrossOperatingSystems() {
        // The development server reports lower_case_table_names = 1 (Windows). Any identifier
        // that depends on case would work there and break on a Linux server, so require that
        // the migrated schema contains none.
        List<String> upperTables = jdbc().queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE() "
                        + "AND table_name <> LOWER(table_name)",
                String.class);
        assertThat(upperTables).as("no table name may depend on letter case").isEmpty();

        List<String> upperColumns = jdbc().queryForList(
                "SELECT CONCAT(table_name, '.', column_name) FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND column_name <> LOWER(column_name)",
                String.class);
        assertThat(upperColumns).as("no column name may depend on letter case").isEmpty();
    }

    @Test
    void identifierColumnsAreCaseSensitive() {
        // The server default collation is utf8mb4_0900_ai_ci (case-INsensitive). Under that
        // collation 'ABC' = 'abc', which would silently change the meaning of every unique
        // identifier in a payment product. Each of these must be a case-sensitive collation.
        List<String> collations = jdbc().queryForList(
                "SELECT CONCAT(table_name, '.', column_name, '=', collation_name) "
                        + "FROM information_schema.columns WHERE table_schema = DATABASE() "
                        + "AND ((table_name = 'pi_webhook_events' AND column_name = 'provider_event_id') "
                        + "  OR (table_name = 'pi_exceptions' AND column_name = 'dedupe_key') "
                        + "  OR (table_name = 'users' AND column_name = 'email') "
                        + "  OR (table_name = 'pi_orders' AND column_name = 'internal_reference') "
                        + "  OR (table_name = 'transactions' AND column_name = 'reference_number'))",
                String.class);
        assertThat(collations).hasSize(5);
        assertThat(collations)
                .as("identifier columns must not use a case-insensitive collation")
                .allSatisfy(value -> assertThat(value).endsWith("_as_cs"));
    }

    private void assertUniqueIndexExists(String table, String index) {
        Integer n = jdbc().queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = ? "
                        + "AND index_name = ? AND non_unique = 0",
                Integer.class, table, index);
        assertThat(n).as("unique index %s on %s must exist", index, table).isGreaterThan(0);
    }
}
