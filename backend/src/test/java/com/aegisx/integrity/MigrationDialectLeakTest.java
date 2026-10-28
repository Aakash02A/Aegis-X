package com.aegisx.integrity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the single MySQL migration tree against PostgreSQL syntax.
 *
 * <p>MySQL is now the only supported database, and {@code db/migration} is its only tree. The
 * baseline was produced by translating the old PostgreSQL history, and hand translation is where a
 * dialect leak hides: a stray {@code jsonb} or {@code ::} cast produces a migration that fails on a
 * real MySQL 8.4 server and nowhere else. The H2 suite never runs migrations at all, so nothing else
 * in the build would notice.</p>
 *
 * <p>This is the cheap half of the verification: it reads the SQL as text and needs no database, so
 * it runs in every build. {@code MySqlSchemaMigrationTest} is the expensive half, which applies the
 * same files to a real MySQL 8.4 and validates the result against the entity model. Together they
 * mean a dialect leak is caught even on a machine with no MySQL available.</p>
 *
 * <p>Comment lines are removed before scanning: a PostgreSQL construct named in a comment, as in the
 * translation-rule header of the baseline, is documentation rather than a defect. The stripping is
 * line-based and does not understand string literals containing {@code --}; no migration in the tree
 * has one, and the assertions below are what would reveal it if that changed.</p>
 */
@DisplayName("Migration dialect isolation")
class MigrationDialectLeakTest {

    private static final String MIGRATION_TREE = "db/migration";

    /** Constructs that exist in PostgreSQL and are not valid MySQL 8.4 SQL. */
    private static final Map<String, String> POSTGRES_ONLY = new LinkedHashMap<>();

    static {
        POSTGRES_ONLY.put("jsonb", "JSONB was not translated to JSON");
        POSTGRES_ONLY.put("timestamptz", "TIMESTAMPTZ was not translated to DATETIME(6)");
        POSTGRES_ONLY.put("timestamp with time zone", "TIMESTAMP WITH TIME ZONE was not translated");
        POSTGRES_ONLY.put("bytea", "BYTEA has no MySQL equivalent and must be translated");
        POSTGRES_ONLY.put("gen_random_uuid", "pgcrypto's UUID generator is not available in MySQL");
        POSTGRES_ONLY.put("uuid_generate_v4", "uuid-ossp's generator is not available in MySQL");
        POSTGRES_ONLY.put("nextval(", "a PostgreSQL sequence is not available in MySQL");
        POSTGRES_ONLY.put("::", "a PostgreSQL cast is not valid MySQL syntax");
        POSTGRES_ONLY.put("bigserial", "SERIAL is not valid MySQL syntax");
        POSTGRES_ONLY.put("on conflict", "ON CONFLICT is PostgreSQL-only upsert syntax");
        POSTGRES_ONLY.put("returning ", "RETURNING is not supported by MySQL");
        POSTGRES_ONLY.put("using gist", "MySQL has no GiST index method");
        POSTGRES_ONLY.put("create extension", "MySQL has no CREATE EXTENSION");
        POSTGRES_ONLY.put("ilike", "ILIKE does not exist in MySQL");
        POSTGRES_ONLY.put("skip locked", "FOR UPDATE SKIP LOCKED differs in MySQL and must be deliberate");
        POSTGRES_ONLY.put("to_tsvector", "PostgreSQL full-text search has no MySQL equivalent");
        POSTGRES_ONLY.put("text[]", "PostgreSQL array columns have no MySQL equivalent");
        POSTGRES_ONLY.put("varchar[]", "PostgreSQL array columns have no MySQL equivalent");
    }

    @Test
    @DisplayName("no PostgreSQL-only SQL reaches the MySQL migration tree")
    void theMigrationTreeContainsNoPostgresSyntax() throws IOException {
        List<Path> files = migrationFiles();
        assertThat(files)
                .as("%s must exist and hold the MySQL migrations", MIGRATION_TREE)
                .isNotEmpty();

        for (Path file : files) {
            String sql = executableSql(file).toLowerCase();
            for (Map.Entry<String, String> entry : POSTGRES_ONLY.entrySet()) {
                assertThat(sql.contains(entry.getKey()))
                        .as("%s contains PostgreSQL-only syntax (\"%s\"): %s",
                                file.getFileName(), entry.getKey(), entry.getValue())
                        .isFalse();
            }
        }
    }

    @Test
    @DisplayName("the scan actually reads the migrations it claims to")
    void theScanIsNotVacuous() throws IOException {
        // A guard that silently reads zero files passes forever. This makes the test above
        // trustworthy: the tree must still hold the baseline and V28, and every file must contain
        // executable SQL once its comments are gone.
        List<Path> files = migrationFiles();
        assertThat(files)
                .as("the tree must contain the baseline and V28 at minimum")
                .hasSizeGreaterThanOrEqualTo(2);

        for (Path path : files) {
            String sql = executableSql(path).toLowerCase();
            boolean hasStatement = Stream.of("create", "alter", "insert", "update", "delete", "drop")
                    .anyMatch(sql::contains);
            assertThat(hasStatement)
                    .as("%s produced no executable SQL once comments were removed, so the leak scan "
                            + "above is reading nothing", path.getFileName())
                    .isTrue();
        }
    }

    /** Every {@code Vn__description.sql} in the migration resource directory, in version order. */
    private static List<Path> migrationFiles() throws IOException {
        URL url = MigrationDialectLeakTest.class.getClassLoader().getResource(MIGRATION_TREE);
        assertThat(url).as("resource directory %s must be on the classpath", MIGRATION_TREE).isNotNull();
        Path directory = Paths.get(url.getPath().replaceFirst("^/(?=[A-Za-z]:)", ""));
        try (Stream<Path> entries = Files.list(directory)) {
            return entries
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith("V") && name.endsWith(".sql");
                    })
                    .sorted()
                    .collect(Collectors.toCollection(ArrayList::new));
        }
    }

    /** The migration with comment lines removed, so documentation is not mistaken for a defect. */
    private static String executableSql(Path path) throws IOException {
        return Files.readAllLines(path, StandardCharsets.UTF_8).stream()
                .map(line -> {
                    int comment = line.indexOf("--");
                    return comment < 0 ? line : line.substring(0, comment);
                })
                .collect(Collectors.joining("\n"));
    }
}
