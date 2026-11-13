package ge.cynthia.openservice;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import java.net.URI;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

class MigrationsTest {
    @TempDir Path root;

    MockEnvironment configuration(boolean postgres) throws Exception {
        String url = "jdbc:sqlite:" + root.resolve("migration.db");
        if (postgres) {
            URI uri =
                    URI.create(DockerDatabase.url().replace("postgresql+psycopg:", "postgresql:"));
            String schema = "migration_" + UUID.randomUUID().toString().replace("-", "");
            url =
                    "jdbc:postgresql://"
                            + uri.getHost()
                            + ":"
                            + uri.getPort()
                            + uri.getPath()
                            + "?user=openservice&password=disposable-integration-password";
            try (var connection = DriverManager.getConnection(url);
                    var statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA " + schema);
            }
            url += "&currentSchema=" + schema;
        }
        return new MockEnvironment()
                .withProperty("DATABASE_URL", url)
                .withProperty("SECRET_KEY", "migration-test-secret")
                .withProperty("ADMIN_PASSWORD", "migration-test-password");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void legacyUpgradeIsAtomicRepeatableAndPreservesData(boolean postgres) throws Exception {
        var config = configuration(postgres);
        var store = new Store(config);
        try (var db = store.open()) {
            db.update(
                    "INSERT INTO os_attachment(sys_id,record_id,name,content) VALUES(?,?,?,?)",
                    "legacy",
                    "parent",
                    "file.bin",
                    new byte[] {0, 1, -1});
            db.update("ALTER TABLE os_attachment DROP COLUMN content_type");
            db.update("DROP TABLE os_migration");
            db.connection.commit();
        }
        new Store(config);
        new Store(config.withProperty("ADMIN_PASSWORD", "changed-bootstrap-password"));
        try (var db = store.open()) {
            assertEquals(1, db.count("SELECT COUNT(*) FROM os_migration"));
            var file = db.one("SELECT * FROM os_attachment WHERE sys_id='legacy'");
            assertArrayEquals(new byte[] {0, 1, -1}, (byte[]) file.get("content"));
            assertEquals("application/octet-stream", file.get("content_type"));
            assertTrue(
                    Passwords.matches(
                            (String)
                                    db.one("SELECT password FROM os_user WHERE name='admin'")
                                            .get("password"),
                            "migration-test-password"));
            db.update("INSERT INTO os_migration(version,name) VALUES(2,'future')");
            db.connection.commit();
        }
        assertThrows(SQLException.class, () -> new Store(config));
        try (var db = store.open()) {
            db.update("DELETE FROM os_migration");
            db.update("INSERT INTO os_migration(version,name) VALUES(0,'invalid')");
            db.connection.commit();
        }
        assertThrows(SQLException.class, () -> new Store(config));
        try (var db = store.open()) {
            db.update("DELETE FROM os_migration");
            db.update("ALTER TABLE os_attachment DROP COLUMN content_type");
            db.connection.commit();
        }
        // Failure after DDL must roll back both the schema change and its journal entry.
        try (var db = store.open()) {
            Migrations.lock(db);
            Migrations.apply(db);
            assertThrows(
                    SQLException.class, () -> db.update("INSERT INTO missing_table VALUES(1)"));
            db.connection.rollback();
        }
        try (var db = store.open()) {
            assertEquals(0, db.count("SELECT COUNT(*) FROM os_migration"));
            try (var columns =
                    db.connection
                            .getMetaData()
                            .getColumns(
                                    null,
                                    db.connection.getSchema(),
                                    "os_attachment",
                                    "content_type")) {
                assertFalse(columns.next());
            }
        }
        new Store(config);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void adoptsAlreadyUpgradedInstallationsWithoutReplacingMetadata(boolean postgres)
            throws Exception {
        var config = configuration(postgres);
        var store = new Store(config);
        try (var db = store.open()) {
            db.update(
                    "INSERT INTO os_attachment(sys_id,record_id,name,content,content_type)"
                        + " VALUES(?,?,?,?,?)",
                    "existing",
                    "parent",
                    "file.pdf",
                    new byte[] {7},
                    "application/pdf");
            db.update("DROP TABLE os_migration");
            db.connection.commit();
        }
        new Store(config);
        try (var db = store.open()) {
            assertEquals(
                    "application/pdf",
                    db.one("SELECT content_type FROM os_attachment WHERE sys_id='existing'")
                            .get("content_type"));
            assertEquals(1, db.count("SELECT COUNT(*) FROM os_migration"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentFreshStartupSerializesBootstrapAndMigration(boolean postgres) throws Exception {
        var config = configuration(postgres);
        try (var executor = Executors.newFixedThreadPool(4)) {
            var start = new CountDownLatch(1);
            List<Future<Store>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++)
                futures.add(
                        executor.submit(() -> {
                            start.await();
                            return new Store(config);
                        }));
            start.countDown();
            for (var future : futures) {
                try (var db = future.get(30, TimeUnit.SECONDS).open()) {
                    assertEquals(1, db.count("SELECT COUNT(*) FROM os_migration"));
                    assertEquals(1, db.count("SELECT COUNT(*) FROM os_user"));
                    assertEquals(7, db.count("SELECT COUNT(*) FROM os_schema"));
                }
            }
        }
    }
}
