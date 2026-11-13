package ge.cynthia.openservice;

import static ge.cynthia.openservice.Values.*;

import static org.junit.jupiter.api.Assertions.*;

import org.bouncycastle.crypto.generators.SCrypt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;

class StoreTest {
    @TempDir Path root;

    MockEnvironment configuration(String url) {
        return new MockEnvironment()
                .withProperty("DATABASE_URL", url)
                .withProperty("SECRET_KEY", "test-secret")
                .withProperty("ADMIN_PASSWORD", "test-only-password-long");
    }

    @Test
    void configurationRequiresBothSecretsAndHandlesUncredentialedUrls() throws Exception {
        for (var environment :
                List.of(
                        new MockEnvironment().withProperty("SECRET_KEY", " "),
                        new MockEnvironment().withProperty("SECRET_KEY", "secret"),
                        new MockEnvironment()
                                .withProperty("SECRET_KEY", "secret")
                                .withProperty("ADMIN_PASSWORD", " ")))
            assertThrows(IllegalArgumentException.class, () -> new Store(environment));
        assertThrows(
                SQLException.class,
                () ->
                        new Store(
                                configuration(
                                        "postgresql://missing.invalid/openservice?connectTimeout=1")));
    }

    @Test
    void configurationExistingDataAndAccountConstraints() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new Store(new MockEnvironment()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new Store(configuration("mysql://localhost/unsupported")));
        assertThrows(
                SQLException.class,
                () ->
                        new Store(
                                configuration(
                                        "postgresql://test_user@127.0.0.1:1/openservice?connectTimeout=1")));
        String url = "jdbc:sqlite:" + root.resolve("store.db");
        var store = new Store(configuration(url));
        try (var db = store.open()) {
            var admin = db.one("SELECT * FROM os_user WHERE name=?", "admin");
            assertTrue(
                    Passwords.matches((String) admin.get("password"), "test-only-password-long"));
            assertThrows(
                    Failure.class,
                    () ->
                            Service.createAccount(
                                    db,
                                    map(
                                            "name",
                                            "admin",
                                            "password",
                                            "replacement-password",
                                            "role",
                                            "reader")));
            assertEquals(admin, db.one("SELECT * FROM os_user WHERE name=?", "admin"));
            Service.createAccount(
                    db,
                    map(
                            "name",
                            "AfterRollback",
                            "password",
                            "replacement-password",
                            "role",
                            "reader"));
            db.connection.commit();
        }
        new Store(configuration(url).withProperty("ADMIN_PASSWORD", "different-password"));
        try (var db = store.open()) {
            assertTrue(
                    Passwords.matches(
                            (String)
                                    db.one("SELECT password FROM os_user WHERE name=?", "admin")
                                            .get("password"),
                            "test-only-password-long"));
            assertNotNull(db.one("SELECT name FROM os_user WHERE name=?", "AfterRollback"));
            var source =
                    map(
                            "sys_id",
                            "a".repeat(32),
                            "short_description",
                            true,
                            "assigned_to",
                            false,
                            "description",
                            null,
                            "sys_created_by",
                            null);
            assertEquals(
                    1,
                    Service.migrate(db, "incident", List.of(source), "admin", false)
                            .get("created"));
            var record = Service.serialize(Service.record(db, "incident", "a".repeat(32)));
            assertEquals("True", record.get("short_description"));
            assertEquals("False", record.get("assigned_to"));
            assertEquals("", record.get("description"));
            assertEquals("None", record.get("sys_created_by"));
            db.connection.commit();
            assertThrows(Failure.class, () -> Service.number(null, 1, 10));
            db.update("DROP TABLE os_user");
            assertThrows(
                    SQLException.class,
                    () ->
                            Service.createAccount(
                                    db,
                                    map(
                                            "name",
                                            "Valid",
                                            "password",
                                            "replacement-password",
                                            "role",
                                            "reader")));
        }
        assertEquals("evidence.txt", WebController.filename("../evidence.txt"));
        assertEquals("attachment", WebController.filename("???"));
        assertEquals("resume.txt", WebController.filename("résumé.txt"));
    }

    @Test
    void privateSetupNeverOverwritesOrPrintsCredentials() throws Exception {
        var capture = new java.io.ByteArrayOutputStream();
        var original = System.out;
        try {
            System.setOut(new java.io.PrintStream(capture));
            Application.main(new String[] {"setup", root.toString()});
        } finally {
            System.setOut(original);
        }
        String contents = java.nio.file.Files.readString(root.resolve(".env"));
        assertEquals(
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
                java.nio.file.Files.getPosixFilePermissions(root.resolve(".env")));
        assertEquals(3, contents.lines().count());
        for (String line : contents.lines().toList()) {
            String value = line.split("=", 2)[1];
            assertEquals(48, value.length());
            assertFalse(capture.toString().contains(value));
        }
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .filter(arg -> arg.startsWith("-javaagent:") && arg.contains("jacoco"))
                .forEach(command::add);
        command.addAll(
                List.of(
                        "-cp",
                        System.getProperty("java.class.path"),
                        Application.class.getName(),
                        "setup"));
        var process =
                new ProcessBuilder(command)
                        .directory(root.toFile())
                        .redirectErrorStream(true)
                        .start();
        assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(
                0,
                process.exitValue(),
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        assertTrue(Setup.create(root).contains("preserved"));
        assertEquals(contents, java.nio.file.Files.readString(root.resolve(".env")));
        assertThrows(
                IllegalArgumentException.class,
                () -> Application.main(new String[] {"setup", "one", "two"}));
    }

    @Test
    void passwordFormatsAndMalformedHashes() throws Exception {
        String password = "test-only-password-long", encoded = Passwords.hash(password);
        assertTrue(Passwords.matches(encoded, password));
        assertFalse(Passwords.matches(encoded, "wrong"));
        String legacy =
                "scrypt:32768:8:1$fixture$"
                        + HexFormat.of()
                                .formatHex(
                                        SCrypt.generate(
                                                password.getBytes(StandardCharsets.UTF_8),
                                                "fixture".getBytes(StandardCharsets.UTF_8),
                                                32768,
                                                8,
                                                1,
                                                64));
        assertTrue(Passwords.matches(legacy, password));
        assertFalse(Passwords.matches(legacy, "wrong"));
        for (String invalid :
                List.of(
                        "broken",
                        "unknown$salt$00",
                        "other:sha256:1$salt$00",
                        "pbkdf2:sha512:1$salt$00",
                        "other:1:1:1$salt$00",
                        "scrypt:131072:8:1$salt$00",
                        "scrypt:32768:0:1$salt$00",
                        "scrypt:32768:8:0$salt$00",
                        "pbkdf2:sha256:no$salt$00",
                        "pbkdf2:sha256:0$salt$00",
                        "pbkdf2:sha256:10000001$salt$00",
                        "pbkdf2:sha256:1$salt$zz",
                        "scrypt:1:8:1$salt$00",
                        "scrypt:32768:17:1$salt$00",
                        "scrypt:32768:8:5$salt$00"))
            assertFalse(Passwords.matches(invalid, password));
    }
}
