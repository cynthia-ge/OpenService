package ge.cynthia.openservice;

import static ge.cynthia.openservice.Values.*;
import static ge.cynthia.openservice.WorkflowSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

final class PermissionAcceptance {
    static void run(WorkflowSupport h) throws Exception {
        var a = new ConfigurationAcceptance(h);
        try {
            h.account("agent", "agent");
            h.account("reader", "reader");
            var definition = ConfigurationAcceptance.definition();
            object(object(definition.get("field_rules")).get("quantity"))
                    .put("write_roles", List.of("admin"));
            assertEquals(
                    200,
                    a.install(ConfigurationAcceptance.bundle(a.table, definition), false)
                            .statusCode());
            var row = h.add(a.table);
            String api = "/api/openservice/permissions/" + a.table + "/" + row.get("sys_id");
            var admin = a.result(h.get(api, auth("admin")), 200);
            assertEquals(row.get("sys_id"), admin.get("record_id"));
            assertEquals("admin", admin.get("role"));
            assertEquals(map("allowed", true, "reason", "allowed"), admin.get("delete"));
            var agent = a.result(h.get(api, auth("agent")), 200);
            assertEquals(map("allowed", true, "reason", "allowed"), agent.get("update"));
            assertEquals(map("allowed", false, "reason", "account_role"), agent.get("delete"));
            var fields = object(agent.get("fields"));
            assertEquals(map("allowed", false, "reason", "field_roles"), fields.get("quantity"));
            assertEquals(
                    map("allowed", true, "reason", "allowed"), fields.get("short_description"));
            assertEquals(
                    map("allowed", true, "reason", "allowed"),
                    object(admin.get("fields")).get("quantity"));
            String item = "/api/now/table/" + a.table + "/" + row.get("sys_id");
            assertEquals(
                    403, h.json("PATCH", item, map("quantity", "9"), auth("agent")).statusCode());
            assertEquals(
                    200,
                    h.json("PATCH", item, map("short_description", "Allowed"), auth("agent"))
                            .statusCode());
            var reader = a.result(h.get(api, auth("reader")), 200);
            assertEquals(map("allowed", false, "reason", "account_role"), reader.get("update"));
            for (Object field : object(reader.get("fields")).values())
                assertEquals(map("allowed", false, "reason", "account_role"), field);
            definition.put("version", 1);
            definition.put("record_roles", map("update", List.of(), "delete", List.of()));
            assertEquals(
                    200,
                    a.install(ConfigurationAcceptance.bundle(a.table, definition), false)
                            .statusCode());
            admin = a.result(h.get(api, auth("admin")), 200);
            assertEquals(map("allowed", false, "reason", "record_roles"), admin.get("update"));
            assertEquals(map("allowed", false, "reason", "record_roles"), admin.get("delete"));
            assertEquals(
                    map("allowed", false, "reason", "record_roles"),
                    object(admin.get("fields")).get("quantity"));
            assertEquals(
                    403, h.json("PATCH", item, map("quantity", "9"), auth("admin")).statusCode());
            assertEquals(400, h.get(api + "?user=admin", auth("reader")).statusCode());
            assertEquals(405, h.json("POST", api, map(), auth("admin")).statusCode());
            assertEquals(404, h.get(api.replace(a.table, "missing"), auth("reader")).statusCode());
            try (var db = h.store.open()) {
                RecordAccess.change(
                        db,
                        Service.record(db, a.table, row.get("sys_id").toString()),
                        RecordAccess.owner(db, "admin"),
                        map("visibility", "private", "owner_id", RecordAccess.owner(db, "agent")));
                db.connection.commit();
            }
            assertEquals(404, h.get(api, auth("reader")).statusCode());
            assertEquals(200, h.get(api, auth("agent")).statusCode());
        } finally {
            a.cleanup();
        }
    }
}
