package ge.cynthia.openservice;

import static ge.cynthia.openservice.Values.*;
import static ge.cynthia.openservice.WorkflowSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

final class RecordPolicyAcceptance {
    static void run(WorkflowSupport h) throws Exception {
        var a = new ConfigurationAcceptance(h);
        try {
            h.account("agent", "agent");
            var definition = ConfigurationAcceptance.definition();
            definition.put(
                    "record_roles",
                    map(
                            "create",
                            List.of("admin"),
                            "update",
                            List.of("admin"),
                            "delete",
                            List.of()));
            assertEquals(
                    200,
                    a.install(ConfigurationAcceptance.bundle(a.table, definition), false)
                            .statusCode());
            String path = "/api/now/table/" + a.table;
            assertEquals(
                    403,
                    h.json("POST", path, map("short_description", "denied"), auth("agent"))
                            .statusCode());
            var row =
                    a.result(
                            h.json(
                                    "POST",
                                    path,
                                    map("short_description", "Managed"),
                                    auth("admin")),
                            201);
            String item = path + "/" + row.get("sys_id");
            assertEquals(
                    403,
                    h.json("PATCH", item, map("short_description", "denied"), auth("agent"))
                            .statusCode());
            assertEquals(
                    200,
                    h.json("PATCH", item, map("short_description", "Allowed"), auth("admin"))
                            .statusCode());
            assertEquals(403, h.json("DELETE", item, null, auth("admin")).statusCode());
            assertEquals(403, h.json("DELETE", item, null, auth("agent")).statusCode());
            assertEquals(
                    201,
                    h.json(
                                    "POST",
                                    "/api/openservice/comments/"
                                            + a.table
                                            + "/"
                                            + row.get("sys_id"),
                                    map("body", "Discussion remains open"),
                                    auth("agent"))
                            .statusCode());
            String token = h.login("agent");
            assertEquals(
                    403,
                    h.form(
                                    "/record/" + a.table + "/new",
                                    map("csrf", token, "short_description", "denied"),
                                    null)
                            .statusCode());
            assertEquals(
                    403,
                    h.form(
                                    "/record/" + a.table + "/" + row.get("sys_id"),
                                    map("csrf", token, "short_description", "denied"),
                                    null)
                            .statusCode());
            definition.put("version", 1);
            definition.put(
                    "record_roles",
                    map(
                            "create",
                            List.of("agent"),
                            "update",
                            List.of("agent"),
                            "delete",
                            List.of("admin")));
            assertEquals(
                    200,
                    a.install(ConfigurationAcceptance.bundle(a.table, definition), false)
                            .statusCode());
            assertEquals(
                    403,
                    h.json("POST", path, map("short_description", "denied"), auth("admin"))
                            .statusCode());
            assertEquals(
                    201,
                    h.json("POST", path, map("short_description", "Agent owned"), auth("agent"))
                            .statusCode());
            assertEquals(
                    403, h.json("PATCH", item, map("quantity", "2"), auth("admin")).statusCode());
            assertEquals(
                    200, h.json("PATCH", item, map("quantity", "2"), auth("agent")).statusCode());
            assertEquals(
                    403,
                    h.json(
                                    "POST",
                                    "/api/openservice/import/" + a.table + "?dry_run=false",
                                    List.of(map("sys_id", id(), "short_description", "denied")),
                                    auth("admin"))
                            .statusCode());
            assertEquals(204, h.json("DELETE", item, null, auth("admin")).statusCode());
            assertEquals(404, h.get(item, auth("admin")).statusCode());
        } finally {
            a.cleanup();
        }
    }
}
