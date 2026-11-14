package ge.cynthia.openservice;

import static ge.cynthia.openservice.Values.*;
import static ge.cynthia.openservice.WorkflowSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

final class AssignmentAcceptance {
    static Map<String, Object> definition() {
        return map(
                "version",
                0,
                "label",
                "Assigned work",
                "prefix",
                "WORK",
                "fields",
                map("short_description", "Summary", "owner", "Assignee", "team", "Group"),
                "states",
                map(),
                "flow",
                map(),
                "field_rules",
                map(
                        "owner",
                        map("type", "account"),
                        "team",
                        map("type", "reference", "target", "sys_user_group")),
                "assignment",
                map("user_field", "owner", "group_field", "team"));
    }

    static String seed(Store.Db db, String table, String user, int count) throws Exception {
        String admin = RecordAccess.owner(db, "admin");
        Configuration.install(
                db,
                map(
                        "format",
                        Configuration.FORMAT,
                        "tables",
                        map(table, definition(), table + "_b", definition())),
                admin,
                false);
        String group =
                Service.create(
                                db,
                                "sys_user_group",
                                map("short_description", "Assignment group"),
                                "admin")
                        .get("sys_id")
                        .toString();
        GroupMembers.change(db, group, admin, map("user_id", user), true);
        for (int i = 0; i < count; i++)
            Service.create(
                    db,
                    i % 2 == 0 ? table : table + "_b",
                    map("short_description", "Assigned task " + i, "owner", user, "team", group),
                    "admin");
        return group;
    }

    static void run(WorkflowSupport h) throws Exception {
        var a = new ConfigurationAcceptance(h);
        try {
            h.account("agent", "agent");
            h.account("reader", "reader");
            String user;
            try (var db = h.store.open()) {
                user = RecordAccess.owner(db, "agent");
            }
            String path = "/api/openservice/users/" + user + "/assignments";
            var empty = a.result(h.get(path, auth("admin")), 200);
            assertEquals(0, ((Number) empty.get("total")).intValue());
            assertEquals(List.of(), empty.get("records"));
            try (var db = h.store.open()) {
                seed(db, a.table, user, 30);
                Service.create(db, a.table, map("short_description", "Unassigned"), "admin");
                Service.changeActive(
                        db, RecordAccess.owner(db, "admin"), user, map("active", false));
                db.connection.commit();
            }
            var first = a.result(h.get(path, auth("admin")), 200);
            assertEquals(30, ((Number) first.get("total")).intValue());
            assertEquals(2, ((Number) first.get("pages")).intValue());
            assertEquals(false, object(first.get("account")).get("active"));
            var records = objects(first.get("records"));
            assertEquals(25, records.size());
            assertEquals(
                    Set.of("sys_id", "table", "number", "summary"), records.getFirst().keySet());
            var second =
                    objects(a.result(h.get(path + "?page=2", auth("admin")), 200).get("records"));
            assertEquals(5, second.size());
            var ids = new HashSet<Object>();
            records.forEach(r -> assertTrue(ids.add(r.get("sys_id"))));
            second.forEach(r -> assertTrue(ids.add(r.get("sys_id"))));
            for (String query :
                    List.of(
                            "?page=0",
                            "?page=x",
                            "?page=1000000",
                            "?page=3",
                            "?unknown=1",
                            "?page=1&page=2"))
                assertEquals(400, h.get(path + query, auth("admin")).statusCode());
            assertEquals(403, h.get(path, auth("reader")).statusCode());
            assertEquals(401, h.get(path, auth("agent")).statusCode());
            h.account("activeagent", "agent");
            assertEquals(403, h.get(path, auth("activeagent")).statusCode());
            assertEquals(405, h.json("POST", path, map(), auth("admin")).statusCode());
            assertEquals(404, h.get(path.replace(user, id()), auth("admin")).statusCode());
            String ui = "/admin/accounts/" + user + "/assignments";
            assertTrue(html(h.get(ui, auth("admin"))).contains("Next assignments"));
            assertTrue(html(h.get(ui + "?page=2", auth("admin"))).contains("Previous assignments"));
            assertEquals(403, h.get(ui, auth("activeagent")).statusCode());
        } finally {
            a.cleanup();
        }
    }
}
