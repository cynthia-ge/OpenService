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
            assertEquals(Migrations.VERSION, db.count("SELECT COUNT(*) FROM os_migration"));
            var file = db.one("SELECT * FROM os_attachment WHERE sys_id='legacy'");
            assertArrayEquals(new byte[] {0, 1, -1}, (byte[]) file.get("content"));
            assertEquals("application/octet-stream", file.get("content_type"));
            assertTrue(
                    Passwords.matches(
                            (String)
                                    db.one("SELECT password FROM os_user WHERE name='admin'")
                                            .get("password"),
                            "migration-test-password"));
            db.update("INSERT INTO os_migration(version,name) VALUES(99,'future')");
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
    void legacyIdentitiesBackfillWithoutChangingCredentials(boolean postgres) throws Exception {
        var config = configuration(postgres);
        var store = new Store(config);
        String hash;
        try (var db = store.open()) {
            hash =
                    (String)
                            db.one("SELECT password FROM os_user WHERE name='admin'")
                                    .get("password");
            db.update("DROP INDEX ix_os_user_identity");
            db.update("ALTER TABLE os_user DROP COLUMN user_id");
            db.update("DELETE FROM os_migration WHERE version>=2");
            db.update("INSERT INTO os_user(name,password,role) VALUES('legacy',?,'reader')", hash);
            db.connection.commit();
        }
        new Store(config);
        List<Map<String, Object>> before;
        try (var db = store.open()) {
            before = db.all("SELECT * FROM os_user ORDER BY name");
            assertEquals(2, before.size());
            for (var user : before) {
                assertTrue(user.get("user_id").toString().matches("[0-9a-f]{32}"));
                assertEquals(hash, user.get("password"));
            }
            assertNotEquals(before.get(0).get("user_id"), before.get(1).get("user_id"));
            assertThrows(
                    SQLException.class,
                    () ->
                            db.update(
                                    "UPDATE os_user SET user_id=? WHERE name='legacy'",
                                    before.get(0).get("user_id")));
            db.connection.rollback();
        }
        new Store(config);
        try (var db = store.open()) {
            assertEquals(before, db.all("SELECT * FROM os_user ORDER BY name"));
        }
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
            assertEquals(Migrations.VERSION, db.count("SELECT COUNT(*) FROM os_migration"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentDeactivationKeepsAnActiveAdministrator(boolean postgres) throws Exception {
        var store = new Store(configuration(postgres));
        String admin, second;
        try (var db = store.open()) {
            Service.createAccount(
                    db,
                    Values.map(
                            "name",
                            "SecondAdmin",
                            "password",
                            "test-only-password-long",
                            "role",
                            "admin"));
            admin =
                    db.one("SELECT user_id FROM os_user WHERE name='admin'")
                            .get("user_id")
                            .toString();
            second =
                    db.one("SELECT user_id FROM os_user WHERE name='SecondAdmin'")
                            .get("user_id")
                            .toString();
            db.connection.commit();
            assertEquals(
                    403,
                    assertThrows(
                                    Values.Failure.class,
                                    () ->
                                            Service.changeActive(
                                                    db,
                                                    "missing",
                                                    second,
                                                    Values.map("active", false)))
                            .status);
        }
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (String target : List.of(admin, second))
                results.add(
                        executor.submit(() -> {
                            start.await();
                            try (var db = store.open()) {
                                try {
                                    Service.changeActive(
                                            db, admin, target, Values.map("active", false));
                                    db.connection.commit();
                                    return 200;
                                } catch (Values.Failure failure) {
                                    db.connection.rollback();
                                    return failure.status;
                                }
                            }
                        }));
            start.countDown();
            var statuses =
                    List.of(
                            results.get(0).get(30, TimeUnit.SECONDS),
                            results.get(1).get(30, TimeUnit.SECONDS));
            assertEquals(1, statuses.stream().filter(status -> status == 200).count());
            assertTrue(
                    statuses.stream().allMatch(status -> Set.of(200, 403, 409).contains(status)));
        }
        try (var db = store.open()) {
            assertEquals(
                    1, db.count("SELECT COUNT(*) FROM os_user WHERE active=1 AND role='admin'"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentDemotionKeepsAnActiveAdministrator(boolean postgres) throws Exception {
        var store = new Store(configuration(postgres));
        String admin, second;
        try (var db = store.open()) {
            Service.createAccount(
                    db,
                    Values.map(
                            "name",
                            "SecondAdmin",
                            "password",
                            "test-only-password-long",
                            "role",
                            "admin"));
            admin =
                    db.one("SELECT user_id FROM os_user WHERE name='admin'")
                            .get("user_id")
                            .toString();
            second =
                    db.one("SELECT user_id FROM os_user WHERE name='SecondAdmin'")
                            .get("user_id")
                            .toString();
            db.connection.commit();
            assertEquals(
                    403,
                    assertThrows(
                                    Values.Failure.class,
                                    () ->
                                            Service.changeRole(
                                                    db,
                                                    "missing",
                                                    second,
                                                    Values.map("role", "reader")))
                            .status);
        }
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (String target : List.of(admin, second))
                results.add(
                        executor.submit(() -> {
                            start.await();
                            try (var db = store.open()) {
                                try {
                                    Service.changeRole(
                                            db, admin, target, Values.map("role", "reader"));
                                    db.connection.commit();
                                    return 200;
                                } catch (Values.Failure failure) {
                                    db.connection.rollback();
                                    return failure.status;
                                }
                            }
                        }));
            start.countDown();
            var statuses =
                    List.of(
                            results.get(0).get(30, TimeUnit.SECONDS),
                            results.get(1).get(30, TimeUnit.SECONDS));
            assertEquals(1, statuses.stream().filter(status -> status == 200).count());
            assertTrue(
                    statuses.stream().allMatch(status -> Set.of(200, 403, 409).contains(status)));
        }
        try (var db = store.open()) {
            assertEquals(
                    1, db.count("SELECT COUNT(*) FROM os_user WHERE active=1 AND role='admin'"));
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
                    assertEquals(Migrations.VERSION, db.count("SELECT COUNT(*) FROM os_migration"));
                    assertEquals(1, db.count("SELECT COUNT(*) FROM os_user"));
                    assertEquals(7, db.count("SELECT COUNT(*) FROM os_schema"));
                }
            }
        }
    }
}
