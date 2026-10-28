package com.aegisx.integrity;

import com.aegisx.integrity.entity.Workspace;
import com.aegisx.integrity.support.MySqlIntegrityTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UUID storage on a real MySQL 8.4 server — §4 of the migration plan.
 *
 * <p>The plan chose {@code CHAR(36)} in an ASCII, binary collation, with
 * {@code hibernate.type.preferred_uuid_jdbc_type=CHAR}, rather than Hibernate's own MySQL preference
 * of {@code BINARY(16)}. Two consequences of that choice are asserted here, because both are the kind
 * of thing that looks fine until a query silently matches the wrong row:</p>
 *
 * <ul>
 *   <li>the <em>stored representation</em> is the canonical 36-character lowercase string, so ad-hoc
 *       {@code WHERE id = '<uuid>'} queries, dumps and comparisons stay legible;</li>
 *   <li>comparison is <em>exact</em>. The server default collation {@code utf8mb4_0900_ai_ci} is
 *       case-insensitive, under which two UUID strings differing only in letter case would compare
 *       equal — a different identity model from PostgreSQL's native {@code uuid}. {@code ascii_bin}
 *       restores exact comparison.</li>
 * </ul>
 *
 * <p>The value used deliberately contains hex letters, so "case-insensitive" would be observable
 * rather than theoretical.</p>
 */
@EnabledIfEnvironmentVariable(named = "INTEGRITY_MYSQL_URL", matches = ".+")
@DisplayName("UUID storage on MySQL")
class MySqlUuidRoundTripTest extends MySqlIntegrityTestSupport {

    /** Contains letters a–e and therefore differs from its uppercase form. */
    private static final UUID FIXED_ID = UUID.fromString("deadbeef-1234-4abc-8def-0123456789ab");

    /**
     * The identity is deliberately fixed, so it has to be removed before each method: otherwise the
     * second method would collide on the primary key, and on the unique workspace name too. The
     * cascade clears anything that pointed at it.
     */
    @BeforeEach
    void removePreviousRun() {
        jdbc().update("DELETE FROM pi_workspaces WHERE id = ?", FIXED_ID.toString());
    }

    @Test
    @DisplayName("a UUID written to the database reads back identical through a fresh session")
    void uuidRoundTripsThroughAFreshSession() {
        UUID workspaceId = insertWorkspaceWithFixedId();

        // workspaceService.get opens its own transaction, so this is a genuine write → fresh
        // session → read rather than a read of the persistence context that wrote it.
        Workspace reloaded = workspaceService.get(workspaceId);

        assertThat(reloaded.getId())
                .as("the UUID must survive the round trip unchanged")
                .isEqualTo(FIXED_ID);
    }

    @Test
    @DisplayName("the stored representation is the canonical 36-character string")
    void storedRepresentationIsCanonical() {
        insertWorkspaceWithFixedId();

        String stored = jdbc().queryForObject(
                "SELECT id FROM pi_workspaces WHERE id = ?", String.class, FIXED_ID.toString());

        assertThat(stored)
                .as("CHAR(36) must hold the canonical lowercase form, so ad-hoc queries and dumps "
                        + "stay readable")
                .isEqualTo("deadbeef-1234-4abc-8def-0123456789ab")
                .hasSize(36);

        assertThat(jdbc().queryForObject(
                "SELECT column_type FROM information_schema.columns WHERE table_schema = DATABASE() "
                        + "AND table_name = 'pi_workspaces' AND column_name = 'id'",
                String.class))
                .as("not BINARY(16), which is what Hibernate would have chosen on its own")
                .isEqualTo("char(36)");

        assertThat(jdbc().queryForObject(
                "SELECT collation_name FROM information_schema.columns WHERE table_schema = DATABASE() "
                        + "AND table_name = 'pi_workspaces' AND column_name = 'id'",
                String.class))
                .as("a binary collation, so equality is exact")
                .isEqualTo("ascii_bin");
    }

    @Test
    @DisplayName("UUID comparison is case-sensitive, not the server's case-insensitive default")
    void uuidComparisonIsExact() {
        insertWorkspaceWithFixedId();

        assertThat(jdbc().queryForObject(
                "SELECT COUNT(*) FROM pi_workspaces WHERE id = ?", Integer.class, FIXED_ID.toString()))
                .as("the canonical form matches")
                .isEqualTo(1);

        // Under the server default collation utf8mb4_0900_ai_ci these two strings would be equal and
        // this would return 1 — which is precisely the failure this assertion exists to catch.
        Integer uppercaseMatches = jdbc().queryForObject(
                "SELECT COUNT(*) FROM pi_workspaces WHERE id = ?",
                Integer.class, FIXED_ID.toString().toUpperCase(java.util.Locale.ROOT));

        assertThat(uppercaseMatches)
                .as("an upper-cased UUID is a different string and must not match; if this ever "
                        + "returns 1 the binary collation has been lost and identifier equality no "
                        + "longer means what PostgreSQL's uuid type means")
                .isZero();
    }

    private UUID insertWorkspaceWithFixedId() {
        jdbc().update(
                "INSERT INTO pi_workspaces (id, name, environment, provider_mode, transfer_capability, "
                        + "default_currency, staleness_threshold_seconds) VALUES (?,?,?,?,?,?,?)",
                FIXED_ID.toString(), "uuid-roundtrip-" + FIXED_ID, "TEST", "SIMULATED", "OK", "INR", 900);
        return FIXED_ID;
    }
}
