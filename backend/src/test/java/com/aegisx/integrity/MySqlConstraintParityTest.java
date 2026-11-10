package com.aegisx.integrity;

import com.aegisx.integrity.support.MySqlIntegrityTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves no business constraint was silently dropped or weakened by the port.
 *
 * <p>The contract below was not invented: it was read out of the PostgreSQL history by querying
 * {@code pg_constraint} on a schema migrated to V28, and then compared with MySQL's
 * {@code information_schema}. Three differences exist and all three are named and accounted for at
 * the end of this file. Every other constraint must be present on both engines, and any unnamed
 * (auto-generated) CHECK that appears or disappears changes the set — which is the point.</p>
 *
 * <p>The last assertions check the trap in §6 of the migration plan: a {@code REGEXP_LIKE} check on a
 * case-insensitive column would accept {@code 'inr'} for {@code 'INR'}, which would quietly weaken the
 * currency and rule-id rules. MySQL needs the {@code 'c'} match type for that, so its absence is
 * asserted directly rather than trusted.</p>
 */
@EnabledIfEnvironmentVariable(named = "INTEGRITY_MYSQL_URL", matches = ".+")
@DisplayName("Constraint parity on MySQL")
class MySqlConstraintParityTest extends MySqlIntegrityTestSupport {

    /**
     * Every CHECK constraint that must exist, as {@code table|constraint}. 60 in total.
     *
     * <p>Both engines have exactly this set, with one addition on PostgreSQL
     * ({@code chk_pi_evidence_doc_supersede_marker}, which MySQL cannot express — see
     * {@link #theOnlyDocumentedDivergenceIsTheSupersessionMarker()}).</p>
     */
    private static final List<String> EXPECTED_CHECKS = List.of(
            "ai_analysis|chk_ai_analysis_confidence",
            "compliance_reviews|chk_compliance_justification_length",
            "compliance_reviews|chk_compliance_previous_state",
            "compliance_reviews|chk_compliance_review_decision",
            "documents|chk_document_type",
            "pi_evidence_documents|chk_pi_evidence_doc_origin",
            "pi_evidence_documents|chk_pi_evidence_doc_processing",
            "pi_evidence_documents|chk_pi_evidence_doc_size",
            "pi_evidence_extractions|chk_pi_evidence_extraction_method",
            "pi_evidence_readiness|chk_pi_evidence_readiness_status",
            "pi_exceptions|chk_pi_exceptions_certainty",
            "pi_exceptions|chk_pi_exceptions_detection_count",
            "pi_exceptions|chk_pi_exceptions_severity",
            "pi_exceptions|chk_pi_exceptions_status",
            "pi_exceptions|chk_pi_exceptions_type",
            "pi_orders|chk_pi_orders_amount_positive",
            "pi_orders|chk_pi_orders_currency_format",
            "pi_orders|chk_pi_orders_provider_state",
            "pi_orders|chk_pi_orders_state",
            "pi_payments|chk_pi_payments_amount_non_negative",
            "pi_payments|chk_pi_payments_currency_format",
            "pi_payments|chk_pi_payments_provider_state",
            "pi_payments|chk_pi_payments_state",
            "pi_provider_observations|chk_pi_observations_rank",
            "pi_provider_observations|chk_pi_observations_resource",
            "pi_provider_observations|chk_pi_observations_source",
            "pi_reconciliation_findings|chk_pi_findings_certainty",
            "pi_reconciliation_findings|chk_pi_findings_severity",
            "pi_reconciliation_findings|chk_pi_findings_type",
            "pi_reconciliation_runs|chk_pi_runs_strategy",
            "pi_reconciliation_runs|chk_pi_runs_trigger",
            "pi_recovery_recommendations|chk_pi_recommendations_category",
            "pi_recovery_recommendations|chk_pi_recommendations_status",
            "pi_refunds|chk_pi_refunds_amount_positive",
            "pi_refunds|chk_pi_refunds_provider_state",
            "pi_refunds|chk_pi_refunds_state",
            "pi_review_actions|chk_pi_review_actions_type",
            "pi_state_transitions|chk_pi_transitions_entity",
            "pi_state_transitions|chk_pi_transitions_source",
            "pi_transfers|chk_pi_transfers_amount_positive",
            "pi_transfers|chk_pi_transfers_provider_state",
            "pi_transfers|chk_pi_transfers_state",
            "pi_webhook_events|chk_pi_webhook_events_state",
            "pi_workspaces|chk_pi_workspaces_environment",
            "pi_workspaces|chk_pi_workspaces_provider_mode",
            "pi_workspaces|chk_pi_workspaces_threshold",
            "policy_evaluations|chk_policy_final_decision",
            "policy_evaluations|chk_policy_verdict",
            "policy_violations|chk_violation_rule_id",
            "policy_violations|chk_violation_severity",
            "settlements|chk_exchange_rate_positive",
            "settlements|chk_max_retries_positive",
            "settlements|chk_payment_processor_valid",
            "settlements|chk_processing_fees_non_negative",
            "settlements|chk_retry_count_non_negative",
            "settlements|chk_retry_count_within_max",
            "settlements|chk_settlement_amount_positive",
            "settlements|chk_settlement_currency_format",
            "settlements|chk_status_valid",
            "transactions|chk_txn_status");

    private static final String SUPERSESSION_MARKER = "chk_pi_evidence_doc_supersede_marker";

    @Test
    @DisplayName("the CHECK constraint set matches the PostgreSQL history exactly, by name and table")
    void checkConstraintsMatchTheContract() {
        List<String> present = jdbc().queryForList(
                "SELECT CONCAT(table_name, '|', constraint_name) AS x FROM "
                        + "information_schema.table_constraints WHERE constraint_schema = DATABASE() "
                        + "AND constraint_type = 'CHECK' ORDER BY x",
                String.class);

        assertThat(present)
                .as("a missing CHECK is a silently weakened business rule; an added one changes the "
                        + "contract the tests rely on")
                .containsExactlyInAnyOrderElementsOf(EXPECTED_CHECKS);
    }

    @Test
    @DisplayName("every UNIQUE index from the PostgreSQL history exists here too")
    void uniqueIndexesMatchThePostgresHistory() {
        // The PostgreSQL names are auto-generated for five of these, so the mapping is explicit
        // rather than inferred. Both engines total nineteen unique indexes.
        List<String> present = jdbc().queryForList(
                "SELECT CONCAT(table_name, '|', index_name) AS x FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND non_unique = 0 "
                        + "AND index_name <> 'PRIMARY' GROUP BY table_name, index_name ORDER BY x",
                String.class);

        assertThat(present)
                .as("the duplicate guards are the reason repeated delivery and repeated "
                        + "reconciliation are harmless")
                .containsExactlyInAnyOrder(
                        "evidence_checklists|uq_evidence_checklists_transaction",
                        "pi_evidence_documents|uq_pi_evidence_doc_active_type",
                        "pi_evidence_readiness|uq_pi_evidence_readiness_order",
                        "pi_exceptions|uq_pi_exceptions_dedupe",
                        "pi_orders|uq_pi_orders_provider_order",
                        "pi_orders|uq_pi_orders_reference",
                        "pi_payments|uq_pi_payments_provider_payment",
                        "pi_refunds|uq_pi_refunds_provider_refund",
                        "pi_transfers|uq_pi_transfers_provider_transfer",
                        "pi_webhook_events|uq_pi_webhook_events_workspace_provider_event",
                        "pi_workspaces|uq_pi_workspaces_name",
                        "settlements|uq_settlements_reference",
                        // V34: prevent more than one settlement for a transaction.
                        "settlements|uq_settlements_transaction_id",
                        "transactions|uq_transactions_reference_number",
                        // V31: prevent reusing a Razorpay order across transactions.
                        "transactions|uq_transactions_razorpay_order_id",
                        "users|uq_users_email",
                        "webhook_events|uq_webhook_events_event_id",
                        "required_documents|uq_required_doc_tx_type",
                        // V33: prevent duplicate membership for a user/workspace pair.
                        "workspace_members|uq_workspace_members_user_workspace");
    }

    @Test
    @DisplayName("a lowercase currency is rejected, and the uppercase one accepted")
    void lowercaseCurrencyIsRejected() {
        UUID workspaceId = insertWorkspace("currency-case");

        // pi_orders.currency has CHECK (currency ~ '^[A-Z]{3}$') in PostgreSQL, ported to
        // REGEXP_LIKE(currency, '^[A-Z]{3}$', 'c'). Without the 'c' flag this insert would succeed
        // and the currency rule would be meaningfully weaker than it is on PostgreSQL.
        //
        // The exception type is asserted loosely on purpose. MySQL reports a CHECK violation with
        // SQL state HY000 (error 3819), not in the ANSI integrity-constraint class, so Spring's
        // SQLExceptionSubclassTranslator cannot classify it as a DataIntegrityViolationException and
        // raises an UncategorizedSQLException instead. What matters here is that the database refused
        // it and named the constraint; the exception taxonomy is a separate, pre-existing concern.
        assertThatThrownBy(() -> insertOrder(workspaceId, "REF-lower", "inr"))
                .as("a lowercase currency must be refused by the database")
                .hasMessageContaining("chk_pi_orders_currency_format");

        insertOrder(workspaceId, "REF-upper", "INR");

        assertThat(jdbc().queryForObject(
                "SELECT COUNT(*) FROM pi_orders WHERE workspace_id = ?", Integer.class, workspaceId.toString()))
                .as("the refused insert must have left nothing behind")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("all four regex CHECKs carry the case-sensitive match type")
    void regexChecksAreCaseSensitive() {
        List<String> clauses = jdbc().queryForList(
                "SELECT CONCAT(t.table_name, '.', t.constraint_name, ' ', cc.check_clause) AS x "
                        + "FROM information_schema.table_constraints t "
                        + "JOIN information_schema.check_constraints cc "
                        + "  ON cc.constraint_schema = t.constraint_schema "
                        + " AND cc.constraint_name = t.constraint_name "
                        + "WHERE t.constraint_schema = DATABASE() AND t.constraint_type = 'CHECK' "
                        + "AND cc.check_clause LIKE '%regexp%' ORDER BY x",
                String.class);

        assertThat(clauses)
                .as("the four predicate CHECKs ported from PostgreSQL's ~ operator")
                .hasSize(4);
        // The catalog renders the flag as the escaped literal _utf8mb4\'c\'. Without it, a
        // case-insensitive collation would accept 'inr' and 'pr-01', which is why each of the four
        // must carry it. The end-to-end consequence is proved separately by
        // lowercaseCurrencyIsRejected.
        assertThat(clauses)
                .as("every one must pass the case-sensitive 'c' match type")
                .allSatisfy(clause -> assertThat(clause).contains("c\\'"));
    }

    @Test
    @DisplayName("the only documented divergence is the supersession marker check")
    void theOnlyDocumentedDivergenceIsTheSupersessionMarker() {
        // Recorded here so the divergence cannot be forgotten or mistaken for an oversight:
        // this CHECK exists on PostgreSQL and cannot exist on MySQL, because MySQL forbids a column
        // that participates in a foreign key with a referential action (here ON DELETE SET NULL) from
        // appearing in a CHECK. The refusal is measured:
        //   ERROR 3823: Column 'superseded_by_document_id' cannot be used in a check constraint
        //              ... needed in a foreign key constraint 'fk_pi_evidence_doc_superseded_by'
        // The invariant it guards is an addition introduced by V28, the load-bearing uniqueness
        // invariant is enforced on both engines, and EvidenceDocumentService maintains the pair.
        assertThat(jdbc().queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema = DATABASE() "
                        + "AND constraint_type = 'CHECK' AND constraint_name = ?",
                Integer.class, SUPERSESSION_MARKER))
                .as("MySQL genuinely cannot have this constraint; if that ever changes, the "
                        + "divergence should be removed rather than left recorded")
                .isZero();
    }

    /**
     * The name is suffixed because {@code uq_pi_workspaces_name} is a real unique constraint and the
     * MySQL schema persists between runs; a fixed name would fail on the second run for a reason that
     * has nothing to do with what is being tested.
     */
    private UUID insertWorkspace(String namePrefix) {
        UUID id = UUID.randomUUID();
        jdbc().update(
                "INSERT INTO pi_workspaces (id, name, environment, provider_mode, transfer_capability, "
                        + "default_currency, staleness_threshold_seconds) VALUES (?,?,?,?,?,?,?)",
                id.toString(), namePrefix + "-" + id, "TEST", "SIMULATED", "OK", "INR", 900);
        return id;
    }

    /** Reference is suffixed for the same reason as the workspace name: it is uniquely constrained. */
    private void insertOrder(UUID workspaceId, String referencePrefix, String currency) {
        jdbc().update(
                "INSERT INTO pi_orders (id, workspace_id, internal_reference, expected_amount_minor, "
                        + "currency, state) VALUES (?,?,?,?,?,?)",
                UUID.randomUUID().toString(), workspaceId.toString(),
                referencePrefix + "-" + UUID.randomUUID(), 1000L, currency, "CREATED");
    }
}
