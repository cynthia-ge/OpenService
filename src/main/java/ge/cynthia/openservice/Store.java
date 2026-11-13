package ge.cynthia.openservice;

import static ge.cynthia.openservice.Values.*;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

@Component
public class Store {
    private final String url;
    private final Properties properties = new Properties();
    final boolean postgres;
    public final String secret;

    public Store(Environment environment) throws Exception {
        secret = environment.getProperty("SECRET_KEY");
        String password = environment.getProperty("ADMIN_PASSWORD");
        if (secret == null || secret.isBlank() || password == null || password.isBlank())
            throw new IllegalArgumentException(
                    "Set SECRET_KEY and ADMIN_PASSWORD before starting OpenService");
        String configured = environment.getProperty("DATABASE_URL", "sqlite:///openservice.db");
        if (configured.startsWith("sqlite:///")) url = "jdbc:sqlite:" + configured.substring(10);
        else if (configured.startsWith("jdbc:")) url = configured;
        else {
            URI uri = URI.create(configured.replace("postgresql+psycopg:", "postgresql:"));
            if (!uri.getScheme().equals("postgresql"))
                throw new IllegalArgumentException("Use SQLite or PostgreSQL");
            url =
                    "jdbc:postgresql://"
                            + uri.getHost()
                            + (uri.getPort() < 0 ? "" : ":" + uri.getPort())
                            + uri.getRawPath()
                            + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
            if (uri.getRawUserInfo() != null) {
                String[] credentials = uri.getRawUserInfo().split(":", 2);
                properties.setProperty(
                        "user", URLDecoder.decode(credentials[0], StandardCharsets.UTF_8));
                properties.setProperty(
                        "password",
                        credentials.length == 2
                                ? URLDecoder.decode(credentials[1], StandardCharsets.UTF_8)
                                : "");
            }
        }
        postgres = url.startsWith("jdbc:postgresql:");
        if (!postgres) {
            properties.setProperty("busy_timeout", "10000");
            properties.setProperty("transaction_mode", "IMMEDIATE");
        }
        try (var db = open()) {
            String sequence = postgres ? "SERIAL PRIMARY KEY" : "INTEGER PRIMARY KEY AUTOINCREMENT",
                    json = postgres ? "JSON" : "TEXT",
                    binary = postgres ? "BYTEA" : "BLOB";
            db.update(
                    "CREATE TABLE IF NOT EXISTS os_schema (name VARCHAR(80) PRIMARY KEY, definition"
                            + " "
                            + json
                            + " NOT NULL)");
            db.update(
                    "CREATE TABLE IF NOT EXISTS os_user (name VARCHAR(80) PRIMARY KEY, password"
                            + " TEXT NOT NULL, role VARCHAR(20) NOT NULL)");
            db.update(
                    "CREATE TABLE IF NOT EXISTS os_record (seq "
                            + sequence
                            + ",sys_id VARCHAR(32) NOT NULL UNIQUE,\"table\" VARCHAR(80) NOT"
                            + " NULL,data "
                            + json
                            + " NOT NULL,original "
                            + json
                            + " NOT NULL,created VARCHAR(40) NOT NULL,updated VARCHAR(40) NOT"
                            + " NULL,creator VARCHAR(80) NOT NULL,updater VARCHAR(80) NOT"
                            + " NULL,revision INTEGER NOT NULL)");
            db.update(
                    "CREATE TABLE IF NOT EXISTS os_event (seq "
                            + sequence
                            + ",record_id VARCHAR(32) NOT NULL,actor VARCHAR(80) NOT NULL,action"
                            + " VARCHAR(40) NOT NULL,created VARCHAR(40) NOT NULL,detail "
                            + json
                            + " NOT NULL)");
            db.update(
                    "CREATE TABLE IF NOT EXISTS os_attachment (sys_id VARCHAR(32) PRIMARY"
                        + " KEY,record_id VARCHAR(32) NOT NULL,name VARCHAR(255) NOT NULL,content "
                            + binary
                            + " NOT NULL)");
            for (String[] index :
                    List.of(
                            new String[] {"ix_os_record_table", "os_record", "\"table\""},
                            new String[] {"ix_os_event_record_id", "os_event", "record_id"},
                            new String[] {
                                "ix_os_attachment_record_id", "os_attachment", "record_id"
                            }))
                db.update(
                        "CREATE INDEX IF NOT EXISTS "
                                + index[0]
                                + " ON "
                                + index[1]
                                + "("
                                + index[2]
                                + ")");
            if (db.one("SELECT name FROM os_user WHERE name=?", "admin") == null)
                db.update(
                        "INSERT INTO os_user(name,password,role) VALUES(?,?,?)",
                        "admin",
                        Passwords.hash(password),
                        "admin");
            try (var input = Store.class.getResourceAsStream("/catalog.json")) {
                for (var entry :
                        object(JSON.readValue(Objects.requireNonNull(input), Object.class))
                                .entrySet())
                    if (db.one("SELECT name FROM os_schema WHERE name=?", entry.getKey()) == null)
                        db.update(
                                "INSERT INTO os_schema(name,definition) VALUES(?,"
                                        + db.jsonParameter()
                                        + ")",
                                entry.getKey(),
                                JSON.writeValueAsString(entry.getValue()));
            }
            db.connection.commit();
        }
    }

    public Db open() throws SQLException {
        return new Db(DriverManager.getConnection(url, properties), postgres);
    }

    public static class Db implements AutoCloseable {
        public final Connection connection;
        final boolean postgres;

        public Db(Connection connection, boolean postgres) throws SQLException {
            this.connection = connection;
            this.postgres = postgres;
            connection.setAutoCommit(false);
        }

        public String jsonParameter() {
            return postgres ? "CAST(? AS JSON)" : "?";
        }

        public PreparedStatement statement(String sql, Object... values) throws SQLException {
            var query = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < values.length; i++) query.setObject(i + 1, values[i]);
            return query;
        }

        public int update(String sql, Object... values) throws SQLException {
            try (var query = statement(sql, values)) {
                return query.executeUpdate();
            }
        }

        public List<Map<String, Object>> all(String sql, Object... values) throws SQLException {
            var rows = new ArrayList<Map<String, Object>>();
            try (var query = statement(sql, values);
                    var result = query.executeQuery()) {
                while (result.next()) {
                    var row = new LinkedHashMap<String, Object>();
                    for (int i = 1; i <= result.getMetaData().getColumnCount(); i++) {
                        String key = result.getMetaData().getColumnLabel(i);
                        Object value = result.getObject(i);
                        if (Set.of("data", "original", "definition", "detail").contains(key))
                            value = JSON.readValue(value.toString(), Object.class);
                        row.put(key, value);
                    }
                    rows.add(row);
                }
            }
            return rows;
        }

        public Map<String, Object> one(String sql, Object... values) throws SQLException {
            var rows = all(sql, values);
            return rows.isEmpty() ? null : rows.getFirst();
        }

        public long count(String sql, Object... values) throws SQLException {
            return ((Number) one(sql, values).values().iterator().next()).longValue();
        }

        public void close() throws SQLException {
            connection.close();
        }
    }
}
