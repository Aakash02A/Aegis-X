package com.aegisx.integrity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * DIAGNOSTIC TOOL — not an assertion of correctness, and deliberately not part of the normal suite.
 *
 * <p>Boots the application against an EMPTY MySQL schema with {@code ddl-auto=create} and Flyway
 * disabled, so Hibernate writes the schema <em>it</em> believes the entities need. The resulting
 * information_schema is dumped to {@code target/mysql-ddl/} so it can be diffed against the schema
 * Flyway produced from {@code db/migration}. The diff is the authoritative punch list of every
 * place the migration and the entity model disagree — Hibernate's validator only ever reports the
 * mismatches it reaches before it gives up.</p>
 *
 * <p>It runs only when {@code INTEGRITY_MYSQL_DDL_URL} is set, and that is deliberately a
 * <em>different</em> variable from the one the read-only MySQL suites use. {@code ddl-auto=create}
 * drops every table it owns, so sharing a switch with the verification suites would allow a
 * diagnostic run to destroy the schema under a real test. A throwaway schema is the only safe target.</p>
 *
 * <pre>
 * INTEGRITY_MYSQL_DDL_URL="jdbc:mysql://host:port/aegisx_hbddl?..." \
 *   ./mvnw test -Dtest=MySqlDdlGenerateTest
 * </pre>
 */
@SpringBootTest
@ActiveProfiles({"test", "mysqlddl"})
@EnabledIfEnvironmentVariable(named = "INTEGRITY_MYSQL_DDL_URL", matches = ".+")
class MySqlDdlGenerateTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void dumpHibernateAuthoredSchema() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        List<Map<String, Object>> columns = jdbc.queryForList(
                "SELECT table_name, column_name, data_type, column_type, is_nullable, column_default, "
                        + "extra, character_set_name, collation_name "
                        + "FROM information_schema.columns WHERE table_schema = DATABASE() "
                        + "ORDER BY table_name, ordinal_position");

        Path out = Path.of("target", "mysql-ddl");
        Files.createDirectories(out);

        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> row : columns) {
            sb.append(row.get("table_name")).append('|')
                    .append(row.get("column_name")).append('|')
                    .append(row.get("data_type")).append('|')
                    .append(row.get("column_type")).append('|')
                    .append(row.get("is_nullable")).append('|')
                    .append(row.get("column_default")).append('|')
                    .append(row.get("extra")).append('|')
                    .append(row.get("character_set_name")).append('|')
                    .append(row.get("collation_name"))
                    .append('\n');
        }
        Files.writeString(out.resolve("hibernate-columns.txt"), sb.toString());
        System.out.println("HB_COLUMN_COUNT=" + columns.size());
    }
}
