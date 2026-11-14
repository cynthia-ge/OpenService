package ge.cynthia.openservice;

import static ge.cynthia.openservice.Values.*;
import static ge.cynthia.openservice.WorkflowSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

final class AssignmentRevisionAcceptance {
    static void run(WorkflowSupport h) throws Exception {
        var a = new ConfigurationAcceptance(h);
        try {
            h.account("source", "agent");
            h.account("target", "agent");
            String source, target, first, second;
            try (var db = h.store.open()) {
                source = RecordAccess.owner(db, "source");
                target = RecordAccess.owner(db, "target");
                String group = AssignmentAcceptance.seed(db, a.table, source, 2);
                GroupMembers.change(
                        db, group, RecordAccess.owner(db, "admin"), map("user_id", target), true);
                var rows = objects(Assignments.page(db, source, Map.of()).get("records"));
                first = rows.getFirst().get("sys_id").toString();
                second = rows.getLast().get("sys_id").toString();
                db.connection.commit();
            }
            String path = "/api/openservice/users/" + source + "/assignments/transfer";
            var revisions = map(first, "0", second, "0");
            var body =
                    map(
                            "new_owner_id",
                            target,
                            "record_ids",
                            List.of(first, second),
                            "expected_revisions",
                            revisions);
            var preview =
                    a.result(h.json("POST", path + "?dry_run=true", body, auth("admin")), 200);
            assertEquals(revisions, preview.get("expected_revisions"));
            var unknown = new LinkedHashMap<>(body);
            unknown.put("unknown", true);
            assertEquals(400, h.json("POST", path, unknown, auth("admin")).statusCode());
            for (Object invalid :
                    Arrays.asList(
                            null,
                            List.of(),
                            map(),
                            map(first, "0"),
                            map(first, "0", second, "0", id(), "0"),
                            map(first, 0, second, "0"),
                            map(first, "-1", second, "0"),
                            map(first, "00", second, "0"),
                            map(first, "10000000000000000000", second, "0"))) {
                var bad = new LinkedHashMap<>(body);
                bad.put("expected_revisions", invalid);
                assertEquals(400, h.json("POST", path, bad, auth("admin")).statusCode());
            }
            assertEquals(
                    200,
                    h.json(
                                    "PATCH",
                                    "/api/now/table/" + a.table + "_b/" + second,
                                    map("short_description", "Edited after preview"),
                                    auth("admin"))
                            .statusCode());
            for (String query : List.of("?dry_run=true", "?dry_run=false"))
                assertEquals(409, h.json("POST", path + query, body, auth("admin")).statusCode());
            try (var db = h.store.open()) {
                var unchanged = Service.record(db, a.table, first);
                assertEquals(source, object(unchanged.get("data")).get("owner"));
                assertEquals("0", unchanged.get("revision").toString());
                var changed = Service.record(db, a.table + "_b", second);
                assertEquals(source, object(changed.get("data")).get("owner"));
                assertEquals(
                        "Edited after preview",
                        object(changed.get("data")).get("short_description"));
                assertEquals("1", changed.get("revision").toString());
                assertEquals(
                        0,
                        db.count(
                                "SELECT COUNT(*) FROM os_event WHERE record_id=? AND"
                                        + " action='updated'",
                                first));
                assertEquals(
                        0,
                        db.count(
                                "SELECT COUNT(*) FROM os_security_event WHERE"
                                        + " action='account.assignments_transferred'"));
            }
            String ui = "/admin/accounts/" + source + "/assignments/transfer";
            assertEquals(
                    400,
                    h.form(ui, map("new_owner", "target", "record_ids", first), auth("admin"))
                            .statusCode());
            assertEquals(
                    400,
                    h.request(
                                    "POST",
                                    ui,
                                    ("new_owner=target&record_ids="
                                                    + first
                                                    + "&revision_"
                                                    + first
                                                    + "=0&revision_"
                                                    + first
                                                    + "=0")
                                            .getBytes(StandardCharsets.UTF_8),
                                    "application/x-www-form-urlencoded",
                                    auth("admin"))
                            .statusCode());
            revisions.put(second, "1");
            var refreshed =
                    a.result(h.json("POST", path + "?dry_run=true", body, auth("admin")), 200);
            assertEquals(revisions, refreshed.get("expected_revisions"));
            var applied = a.result(h.json("POST", path, body, auth("admin")), 200);
            assertEquals(revisions, applied.get("expected_revisions"));
            assertEquals(2, applied.get("transferred_count"));
            try (var db = h.store.open()) {
                for (var row : objects(Assignments.page(db, target, Map.of()).get("records"))) {
                    String id = row.get("sys_id").toString();
                    assertEquals(id.equals(first) ? "1" : "2", row.get("revision"));
                }
                assertEquals(2L, Assignments.page(db, target, Map.of()).get("total"));
                assertEquals(
                        1,
                        db.count(
                                "SELECT COUNT(*) FROM os_security_event WHERE"
                                        + " action='account.assignments_transferred'"));
            }
        } finally {
            a.cleanup();
        }
    }
}
