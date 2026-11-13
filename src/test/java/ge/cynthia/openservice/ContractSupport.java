package ge.cynthia.openservice;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

abstract class ContractSupport {
    static final List<Map<String, Object>> CASES = fixtures();
    static final Path DATABASE = database();
    @Autowired Environment environment;
    @Autowired Store store;

    static void properties(DynamicPropertyRegistry registry) {
        registry.add(
                "DATABASE_URL",
                () ->
                        System.getenv()
                                .getOrDefault(
                                        "OPEN_SERVICE_TEST_DATABASE_URL", "sqlite:///" + DATABASE));
        registry.add("SECRET_KEY", () -> "test-secret-key-for-disposable-evaluation");
        registry.add("ADMIN_PASSWORD", () -> "test-only-password-long");
        registry.add("COOKIE_SECURE", () -> "false");
    }

    @TestFactory
    Stream<DynamicTest> contracts() {
        return CASES.stream()
                .map(test ->
                        DynamicTest.dynamicTest((String) test.get("name"), () -> replay(test)));
    }

    void replay(Map<String, Object> test) throws Exception {
        try (var db = store.open()) {
            for (String table : List.of("os_attachment", "os_event", "os_record"))
                db.update("DELETE FROM " + table);
            db.update("DELETE FROM os_user WHERE name<>?", "admin");
            if (db.postgres) {
                db.update("ALTER SEQUENCE os_record_seq_seq RESTART WITH 1");
                db.update("ALTER SEQUENCE os_event_seq_seq RESTART WITH 1");
            } else db.update("DELETE FROM sqlite_sequence");
            db.connection.commit();
        }
        String base = "http://localhost:" + environment.getProperty("local.server.port");
        Map<String, String> substitutions = new LinkedHashMap<>();
        substitutions.put("http://localhost/", base + "/");
        try (HttpClient client = HttpClient.newHttpClient()) {
            int index = 0;
            for (var step : maps(test.get("requests"))) {
                String path = replace((String) step.get("path"), substitutions);
                var request = HttpRequest.newBuilder(URI.create((base + path).replace("^", "%5E")));
                cast(step.get("headers"))
                        .forEach((key, value) -> request.header(key, (String) value));
                byte[] bytes = Base64.getDecoder().decode((String) step.get("body"));
                String body =
                        replace(new String(bytes, StandardCharsets.ISO_8859_1), substitutions);
                request.method(
                        (String) step.get("method"),
                        HttpRequest.BodyPublishers.ofByteArray(
                                body.getBytes(StandardCharsets.ISO_8859_1)));
                var response =
                        client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
                String context =
                        test.get("name")
                                + " #"
                                + (index++)
                                + " "
                                + step.get("method")
                                + " "
                                + path
                                + " => "
                                + new String(response.body(), StandardCharsets.UTF_8);
                assertEquals(
                        ((Number) step.get("status")).intValue(), response.statusCode(), context);
                assertEquals(
                        "no-store",
                        response.headers().firstValue("Cache-Control").orElse(""),
                        context);
                assertEquals(
                        "nosniff",
                        response.headers().firstValue("X-Content-Type-Options").orElse(""),
                        context);
                if (step.get("response") != null) {
                    Object actual = Values.JSON.readValue(response.body(), Object.class);
                    Object expected = step.get("response");
                    if (path.contains("sysparm_group_by=sys_id")) {
                        var sorted = new ArrayList<>(maps(cast(expected).get("result")));
                        sorted.sort(
                                Comparator.comparing(row ->
                                        replace(
                                                (String)
                                                        maps(row.get("groupby_fields"))
                                                                .getFirst()
                                                                .get("value"),
                                                substitutions)));
                        expected = Values.map("result", sorted);
                    }
                    compare(
                            expected,
                            actual,
                            "",
                            substitutions,
                            response.statusCode() >= 400,
                            context);
                } else if (response.headers()
                        .firstValue("Content-Type")
                        .orElse("")
                        .startsWith("text/html")) {
                    assertFalse(
                            new String(response.body(), StandardCharsets.UTF_8)
                                    .contains("Whitelabel Error Page"),
                            context);
                } else if (response.statusCode() != 204
                        && response.statusCode() != 302
                        && !step.get("method").equals("HEAD")) {
                    assertArrayEquals(
                            Base64.getDecoder().decode((String) step.get("raw")),
                            response.body(),
                            context);
                }
                if (cast(step.get("response_headers")).containsKey("Content-Disposition")) {
                    assertEquals(
                            cast(step.get("response_headers")).get("Content-Disposition"),
                            response.headers().firstValue("Content-Disposition").orElse(""),
                            context);
                }
            }
        }
    }

    void compare(
            Object expected,
            Object actual,
            String field,
            Map<String, String> bindings,
            boolean error,
            String context) {
        if (error
                && Set.of("errorMessages", "message", "details", "description", "detail")
                        .contains(field)) {
            assertNotNull(actual, context);
            assertFalse(actual.toString().isBlank(), context);
            return;
        }
        if (expected instanceof Map<?, ?> left) {
            assertInstanceOf(Map.class, actual, context);
            var right = cast(actual);
            assertEquals(left.keySet(), right.keySet(), context);
            String previous = bindings.get("__record"), revision = bindings.get("__revision");
            if (left.containsKey("sys_id")) {
                compare(
                        left.get("sys_id"),
                        right.get("sys_id"),
                        "sys_id",
                        bindings,
                        error,
                        context);
                bindings.put("__record", left.get("sys_id").toString());
                bindings.put("__revision", Objects.toString(left.get("sys_mod_count"), "0"));
            }
            left.forEach((key, value) ->
                    compare(value, right.get(key), (String) key, bindings, error, context));
            if (previous == null) bindings.remove("__record");
            else bindings.put("__record", previous);
            if (revision == null) bindings.remove("__revision");
            else bindings.put("__revision", revision);
        } else if (expected instanceof List<?> left) {
            assertInstanceOf(List.class, actual, context);
            var right = (List<?>) actual;
            assertEquals(left.size(), right.size(), context);
            for (int index = 0; index < left.size(); index++)
                compare(left.get(index), right.get(index), field, bindings, error, context);
        } else if (expected instanceof String value && actual instanceof String result) {
            if (Set.of("sys_created_on", "sys_updated_on").contains(field)
                    && value.startsWith("2026-11-13")) {
                assertDoesNotThrow(
                        () ->
                                LocalDateTime.parse(
                                        result, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                        context);
                String key =
                        "timestamp:"
                                + bindings.get("__record")
                                + ":"
                                + field
                                + ":"
                                + (field.equals("sys_updated_on") ? bindings.get("__revision") : "")
                                + ":"
                                + value;
                String prior = bindings.putIfAbsent(key, result);
                if (prior != null) assertEquals(prior, result, context);
            } else if (bindings.containsKey(value)) {
                assertEquals(bindings.get(value), result, context);
            } else if (value.matches("\\d{4}-\\d{2}-\\d{2}T.*")
                    && Set.of(
                                    "created",
                                    "updated",
                                    "created_at",
                                    "updated_at",
                                    "createdAt",
                                    "lastEditedAt",
                                    "CreatedDate",
                                    "LastModifiedDate")
                            .contains(field)) {
                assertDoesNotThrow(() -> Instant.parse(result), context);
                bindings.put(value, result);
            } else if (Set.of(
                                    "id",
                                    "Id",
                                    "staticId",
                                    "projectId",
                                    "cellId",
                                    "traceId",
                                    "insertAfterCellId",
                                    "sys_id",
                                    "table_sys_id")
                            .contains(field)
                    && (value.matches("[0-9a-f-]{36}")
                            || value.matches("[0-9a-f]{32}")
                            || value.matches("[A-Za-z0-9]{18}"))) {
                assertEquals(value.length(), result.length(), context);
                bindings.put(value, result);
                if (value.length() == 18)
                    bindings.put(value.substring(0, 15), result.substring(0, 15));
            } else {
                assertEquals(replace(value, bindings), result, context);
            }
        } else {
            assertEquals(expected, actual, context);
        }
    }

    static String replace(String value, Map<String, String> bindings) {
        for (String key :
                bindings.keySet().stream()
                        .filter(key -> !key.startsWith("__") && !key.startsWith("timestamp:"))
                        .sorted(Comparator.comparingInt(String::length).reversed())
                        .toList()) value = value.replace(key, bindings.get(key));
        return value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> cast(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> maps(Object value) {
        return (List<Map<String, Object>>) value;
    }

    static List<Map<String, Object>> fixtures() {
        try (var input =
                new GZIPInputStream(
                        Objects.requireNonNull(
                                ContractSupport.class.getResourceAsStream("/contracts.json.gz")))) {
            return maps(Values.JSON.readValue(input, List.class));
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    static Path database() {
        try {
            return Files.createTempFile("contract-test-", ".db");
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}
