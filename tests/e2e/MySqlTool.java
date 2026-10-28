import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Small verification helper for the isolated MySQL database used by the e2e harness.
 * Usage:
 *   MySqlTool <targetDb> recreate
 *   MySqlTool <targetDb> sql "<statement>"
 *   MySqlTool <targetDb> query "<select>"
 *
 * The admin connection carries no schema: MySQL cannot drop the database it is currently
 * connected to, unlike PostgreSQL where the tool used to terminate other sessions first.
 */
public class MySqlTool {
    public static void main(String[] args) throws Exception {
        String adminUrl = env("ADMIN_MYSQL_URL", "jdbc:mysql://localhost:3306");
        String user = env("DB_USERNAME", "root");
        String pass = System.getenv("DB_PASSWORD");
        String target = args[0];
        String mode = args.length > 1 ? args[1] : "recreate";
        String sql = args.length > 2 ? args[2] : null;

        // Split the query string once so "/<db>" is replaced, never a database-looking fragment
        // inside the parameters.
        int queryStart = adminUrl.indexOf('?');
        String base = queryStart < 0 ? adminUrl : adminUrl.substring(0, queryStart);
        String params = queryStart < 0 ? "" : adminUrl.substring(queryStart);
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }

        String url = "recreate".equals(mode)
                ? adminUrl
                : base + "/" + target + params;

        try (Connection c = DriverManager.getConnection(url, user, pass);
             Statement s = c.createStatement()) {
            switch (mode) {
                case "recreate" -> {
                    s.execute("DROP DATABASE IF EXISTS `" + target + "`");
                    s.execute("CREATE DATABASE `" + target
                            + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
                    System.out.println("recreated " + target);
                }
                case "sql" -> {
                    int rows = s.executeUpdate(sql);
                    System.out.println("rows=" + rows);
                }
                case "query" -> {
                    try (ResultSet rs = s.executeQuery(sql)) {
                        int cols = rs.getMetaData().getColumnCount();
                        while (rs.next()) {
                            StringBuilder line = new StringBuilder();
                            for (int i = 1; i <= cols; i++) {
                                if (i > 1) line.append(" | ");
                                line.append(rs.getString(i));
                            }
                            System.out.println(line);
                        }
                    }
                }
                default -> System.out.println("unknown mode " + mode);
            }
        }
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v;
    }
}
