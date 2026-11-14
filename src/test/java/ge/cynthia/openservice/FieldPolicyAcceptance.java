package ge.cynthia.openservice;

import static ge.cynthia.openservice.Values.*;
import static ge.cynthia.openservice.WorkflowSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

final class FieldPolicyAcceptance {
    static void run(WorkflowSupport h) throws Exception {
        var a = new ConfigurationAcceptance(h);
        try {
            h.account("agent", "agent");
            var definition = ConfigurationAcceptance.definition();
            object(object(definition.get("field_rules")).get("quantity"))
                    .put("write_roles", List.of("admin"));
            object(object(definition.get("field_rules")).get("amount"))
                    .put("write_roles", List.of("agent"));
            object(object(definition.get("field_rules")).get("enabled"))
                    .put("write_roles", List.of());
            assertEquals(
                    200,
                    a.install(ConfigurationAcceptance.bundle(a.table, definition), false)
                            .statusCode());
            String path = "/api/now/table/" + a.table;
            assertEquals(
                    403,
                    h.json(
                                    "POST",
                                    path,
                                    map("short_description", "denied", "quantity", "7"),
                                    auth("agent"))
                            .statusCode());
            assertEquals(
                    403,
                    h.json(
                                    "POST",
                                    path,
                                    map("short_description", "denied", "enabled", "true"),
                                    auth("admin"))
                            .statusCode());
            var row =
                    a.result(
                            h.json(
                                    "POST",
                                    path,
                                    map("short_description", "Allowed", "quantity", "7"),
                                    auth("admin")),
                            201);
            String item = path + "/" + row.get("sys_id");
            assertEquals(
                    403, h.json("PATCH", item, map("quantity", "8"), auth("agent")).statusCode());
            assertEquals(
                    403, h.json("PATCH", item, map("quantity", ""), auth("agent")).statusCode());
            assertEquals(
                    200,
                    h.json(
                                    "PATCH",
                                    item,
                                    map(
                                            "quantity",
                                            "7",
                                            "short_description",
                                            "Allowed edit",
                                            "amount",
                                            "1.5"),
                                    auth("agent"))
                            .statusCode());
            assertEquals(
                    403, h.json("PATCH", item, map("amount", "3"), auth("admin")).statusCode());
            assertEquals(
                    200, h.json("PATCH", item, map("quantity", "8"), auth("admin")).statusCode());
            assertEquals("8", a.result(h.get(item, auth("agent")), 200).get("quantity"));
            assertEquals("1.5", a.result(h.get(item, auth("admin")), 200).get("amount"));
            String imported = id(),
                    importing = "/api/openservice/import/" + a.table + "?dry_run=false";
            assertEquals(
                    400,
                    h.json(
                                    "POST",
                                    importing,
                                    List.of(
                                            map(
                                                    "sys_id",
                                                    imported,
                                                    "short_description",
                                                    "Import",
                                                    "amount",
                                                    "2")),
                                    auth("admin"))
                            .statusCode());
            assertEquals(404, h.get(path + "/" + imported, auth("admin")).statusCode());
            assertEquals(
                    200,
                    h.json(
                                    "POST",
                                    importing,
                                    List.of(
                                            map(
                                                    "sys_id",
                                                    imported,
                                                    "short_description",
                                                    "Import",
                                                    "quantity",
                                                    "2")),
                                    auth("admin"))
                            .statusCode());
            assertEquals(
                    "2",
                    a.result(h.get(path + "/" + imported, auth("agent")), 200).get("quantity"));
            String csrf = h.login("agent");
            assertEquals(
                    403,
                    h.form(
                                    "/record/" + a.table + "/" + row.get("sys_id"),
                                    map("csrf", csrf, "quantity", "99"),
                                    null)
                            .statusCode());
        } finally {
            a.cleanup();
        }
    }
}
