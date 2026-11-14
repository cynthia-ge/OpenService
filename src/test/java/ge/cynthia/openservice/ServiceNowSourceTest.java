package ge.cynthia.openservice;

import static ge.cynthia.openservice.Values.*;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.*;

import java.util.*;

class ServiceNowSourceTest {
    @AfterEach
    void clean() {
        Thread.interrupted();
        MigrationSource.reset();
    }

    @Test
    void validatesSourceOriginsWithoutAllowingCredentialOrPathInjection() {
        assertEquals(
                "https://instance.service-now.com",
                ServiceNowSource.origin("https://instance.service-now.com/", ""));
        assertEquals(
                MigrationSource.origin(),
                ServiceNowSource.origin(MigrationSource.origin(), MigrationSource.origin()));
        for (String url :
                List.of(
                        "%",
                        "file:///etc/passwd",
                        "https:opaque",
                        "https://user@instance.service-now.com",
                        "https://instance.service-now.com?x=y",
                        "https://instance.service-now.com#fragment",
                        "https://instance.service-now.com/path",
                        "http://instance.service-now.com",
                        "https://instance.service-now.com:8443",
                        "https://host.example",
                        "https://service-now.com",
                        "https://nested.instance.service-now.com")) {
            assertEquals(
                    400,
                    assertThrows(Failure.class, () -> ServiceNowSource.origin(url, "")).status,
                    url);
        }
        assertEquals(
                "https://custom.example:8443",
                ServiceNowSource.origin(
                        "https://custom.example:8443",
                        "https://other.example,https://custom.example:8443"));
    }

    @Test
    void boundedHttpTransportRejectsErrorsRedirectsOversizeAndInterruptedRequests() {
        MigrationSource.reset();
        assertEquals(
                400,
                assertThrows(
                                Failure.class,
                                () ->
                                        ServiceNowSource.get(
                                                MigrationSource.origin(),
                                                "bad token",
                                                "/api/now/table/incident",
                                                100))
                        .status);
        var unauthorized =
                assertThrows(
                        Failure.class,
                        () ->
                                ServiceNowSource.get(
                                        MigrationSource.origin(),
                                        "wrong",
                                        "/api/now/table/incident",
                                        100));
        assertEquals(502, unauthorized.status);
        assertFalse(unauthorized.getMessage().contains("secret-source"));
        MigrationSource.raw = "longer than one byte";
        assertEquals(
                502,
                assertThrows(
                                Failure.class,
                                () ->
                                        ServiceNowSource.get(
                                                MigrationSource.origin(),
                                                MigrationSource.TOKEN,
                                                "/any",
                                                1))
                        .status);
        for (int status : List.of(302, 403, 429, 500)) {
            MigrationSource.status = status;
            assertTrue(
                    assertThrows(
                                    Failure.class,
                                    () ->
                                            ServiceNowSource.get(
                                                    MigrationSource.origin(),
                                                    MigrationSource.TOKEN,
                                                    "/any",
                                                    100))
                            .getMessage()
                            .contains("HTTP " + status));
        }
        assertEquals(
                502,
                assertThrows(
                                Failure.class,
                                () ->
                                        ServiceNowSource.get(
                                                "http://localhost:1",
                                                MigrationSource.TOKEN,
                                                "/",
                                                100))
                        .status);
        Thread.currentThread().interrupt();
        assertEquals(
                502,
                assertThrows(
                                Failure.class,
                                () ->
                                        ServiceNowSource.get(
                                                MigrationSource.origin(),
                                                MigrationSource.TOKEN,
                                                "/any",
                                                100))
                        .status);
        assertTrue(Thread.interrupted());
    }

    @Test
    void rejectsMalformedOrUnorderedPagesAndPreservesScalarReferenceValues() {
        MigrationSource.reset();
        for (String raw :
                List.of(
                        "not-json",
                        "[]",
                        "{}",
                        "{\"result\":null}",
                        "{\"result\":[null]}",
                        "{\"result\":[{}]}",
                        JSON.writeValueAsString(
                                map(
                                        "result",
                                        List.of(
                                                map(
                                                        "sys_id",
                                                        map("value", MigrationSource.key(1)))))),
                        "{\"result\":[{\"sys_id\":\"bad\"}]}",
                        JSON.writeValueAsString(map("result", Collections.nCopies(101, map()))),
                        JSON.writeValueAsString(
                                map(
                                        "result",
                                        List.of(
                                                map("sys_id", MigrationSource.key(1)),
                                                map("sys_id", MigrationSource.key(1))))))) {
            MigrationSource.raw = raw;
            assertEquals(
                    502,
                    assertThrows(
                                    Failure.class,
                                    () ->
                                            ServiceNowSource.page(
                                                    MigrationSource.origin(),
                                                    MigrationSource.TOKEN,
                                                    "incident",
                                                    "",
                                                    "",
                                                    "sys_id"))
                            .status,
                    raw);
        }
        MigrationSource.raw =
                JSON.writeValueAsString(
                        map("result", List.of(map("sys_id", MigrationSource.key(1)))));
        assertThrows(
                Failure.class,
                () ->
                        ServiceNowSource.page(
                                MigrationSource.origin(),
                                MigrationSource.TOKEN,
                                "incident",
                                "name=x",
                                MigrationSource.key(2),
                                "sys_id"));
        assertEquals("", ServiceNowSource.text(map("field", null), "field"));
        assertEquals("", ServiceNowSource.text(map("field", map()), "field"));
        assertEquals("42", ServiceNowSource.text(map("field", 42), "field"));
        assertEquals("value", ServiceNowSource.text(map("field", map("value", "value")), "field"));
    }
}
