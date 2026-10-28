package com.aegisx.integrity.support;

import com.aegisx.integrity.audit.IntegrityAuditService;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * Base class for the tests that must run against a <em>real</em> MySQL 8.4 server.
 *
 * <p>It exists so the MySQL suites inherit the whole fixture surface of
 * {@link IntegrityTestSupport} — the same services, repositories and helpers — instead of growing a
 * second, divergent copy of it. Only the profile changes: {@code mysqlverify} replaces the H2
 * datasource with a real MySQL one, turns Hibernate {@code ddl-auto} into {@code validate} and lets
 * Flyway build the schema from {@code db/migration}.</p>
 *
 * <p>Why this matters, stated plainly: the default suite runs on H2 with the schema generated from
 * the entity model. H2 in PostgreSQL mode enforces neither the CHECK constraints nor the partial
 * unique indexes, so a whole class of defect — the one that produced the V27 {@code exception_type}
 * bug — is invisible there. Anything asserted here is asserted against the schema that production
 * would actually run, with the constraints that are supposed to protect it.</p>
 *
 * <p>Skipped unless {@code INTEGRITY_MYSQL_URL} is set, so a plain {@code mvn test} is unaffected.</p>
 */
@SpringBootTest
@ActiveProfiles({"test", "mysqlverify"})
@EnabledIfEnvironmentVariable(named = "INTEGRITY_MYSQL_URL", matches = ".+")
public abstract class MySqlIntegrityTestSupport extends IntegrityTestSupport {

    @Autowired
    protected DataSource dataSource;

    /** Needed to drive explicit transaction boundaries in the rollback tests. */
    @Autowired
    protected PlatformTransactionManager transactionManager;

    /**
     * Writes an audit record through the service, which is annotated {@code REQUIRES_NEW}. Used to
     * prove that an audit entry survives the rollback of the transaction that asked for it.
     */
    @Autowired
    protected IntegrityAuditService auditService;

    /** Direct database access, used to prove what the <em>database</em> enforces. */
    protected JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }
}
