package ge.cynthia.openservice;

import static ge.cynthia.openservice.Values.*;
import static ge.cynthia.openservice.WorkflowSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

final class AssignmentTransferAcceptance {
    static void run(WorkflowSupport h) throws Exception {
        var a = new ConfigurationAcceptance(h);
        try {
            for (String name : List.of("source", "target", "outsider")) h.account(name, "agent");
            h.account("reader", "reader");
            String source, target, outsider, reader, group, record, second;
            try (var db = h.store.open()) {
                source = RecordAccess.owner(db, "source");
                target = RecordAccess.owner(db, "target");
                outsider = RecordAccess.owner(db, "outsider");
                reader = RecordAccess.owner(db, "reader");
                group = AssignmentAcceptance.seed(db, a.table, source, 2);
                GroupMembers.change(
                        db, group, RecordAccess.owner(db, "admin"), map("user_id", target), true);
                var rows = objects(Assignments.page(db, source, Map.of()).get("records"));
                record = rows.getFirst().get("sys_id").toString();
                second = rows.getLast().get("sys_id").toString();
                RecordAccess.change(
                        db,
                        Service.record(db, a.table, record),
                        RecordAccess.owner(db, "admin"),
                        map("visibility", "private", "owner_id", source));
                db.connection.commit();
            }
            String path = "/api/openservice/users/" + source + "/assignments/transfer";
            var good = map("new_owner_id", target, "record_ids", List.of(record, second));
            var locked = AssignmentAcceptance.definition();
            locked.put("version", 1);
            locked.put("record_roles", map("update", List.of()));
            assertEquals(
                    200,
                    a.install(ConfigurationAcceptance.bundle(a.table + "_b", locked), false)
                            .statusCode());
            assertEquals(403, h.json("POST", path, good, auth("admin")).statusCode());
            try (var db = h.store.open()) {
                assertEquals(
                        source,
                        object(Service.record(db, a.table, record).get("data")).get("owner"));
            }
            locked.put("version", 2);
            locked.put("record_roles", map());
            assertEquals(
                    200,
                    a.install(ConfigurationAcceptance.bundle(a.table + "_b", locked), false)
                            .statusCode());
            try (var db = h.store.open()) {
                Service.changeActive(
                        db, RecordAccess.owner(db, "admin"), source, map("active", false));
                Service.changeActive(
                        db, RecordAccess.owner(db, "admin"), target, map("active", false));
                db.connection.commit();
            }
            assertEquals(400, h.json("POST", path, good, auth("admin")).statusCode());
            try (var db = h.store.open()) {
                Service.changeActive(
                        db, RecordAccess.owner(db, "admin"), target, map("active", true));
                db.connection.commit();
            }

            for (Object invalid :
                    List.of(
                            map(),
                            map("new_owner_id", 4, "record_ids", List.of(record)),
                            map("new_owner_id", "bad", "record_ids", List.of(record)),
                            map("new_owner_id", source, "record_ids", List.of(record)),
                            map("new_owner_id", target, "record_ids", "bad"),
                            map("new_owner_id", target, "record_ids", List.of()),
                            map(
                                    "new_owner_id",
                                    target,
                                    "record_ids",
                                    Collections.nCopies(101, record)),
                            map("new_owner_id", target, "record_ids", List.of(record, record)),
                            map("new_owner_id", target, "record_ids", List.of(4)),
                            map("new_owner_id", target, "record_ids", List.of("bad")),
                            map("new_owner_id", reader, "record_ids", List.of(record)),
                            map("new_owner_id", id(), "record_ids", List.of(record))))
                assertEquals(400, h.json("POST", path, invalid, auth("admin")).statusCode());
            assertEquals(
                    400,
                    h.json(
                                    "POST",
                                    path,
                                    map("new_owner_id", outsider, "record_ids", List.of(record)),
                                    auth("admin"))
                            .statusCode());
            assertEquals(
                    404,
                    h.json(
                                    "POST",
                                    path,
                                    map(
                                            "new_owner_id",
                                            target,
                                            "record_ids",
                                            List.of(record, id())),
                                    auth("admin"))
                            .statusCode());
            String plain = h.add("incident").get("sys_id").toString();
            assertEquals(
                    409,
                    h.json(
                                    "POST",
                                    path,
                                    map(
                                            "new_owner_id",
                                            target,
                                            "record_ids",
                                            List.of(record, plain)),
                                    auth("admin"))
                            .statusCode());
            try (var db = h.store.open()) {
                assertEquals(
                        source,
                        object(Service.record(db, a.table, record).get("data")).get("owner"));
                assertEquals(
                        0,
                        db.count(
                                "SELECT COUNT(*) FROM os_security_event WHERE"
                                        + " action='account.assignments_transferred'"));
            }
            assertEquals(403, h.json("POST", path, good, auth("target")).statusCode());
            assertEquals(403, h.json("POST", path, good, auth("reader")).statusCode());
            assertEquals(405, h.get(path, auth("admin")).statusCode());
            assertEquals(400, h.json("POST", path + "?x=1", good, auth("admin")).statusCode());
            assertEquals(
                    404,
                    h.json("POST", path.replace(source, id()), good, auth("admin")).statusCode());
            String ui = "/admin/accounts/" + source + "/assignments/transfer";
            assertEquals(
                    400,
                    h.form(ui, map("new_owner", "bad name", "record_ids", record), auth("admin"))
                            .statusCode());
            assertEquals(400, h.form(ui, map("new_owner", "target"), auth("admin")).statusCode());
            assertEquals(
                    400,
                    h.request(
                                    "POST",
                                    ui,
                                    ("new_owner=target&new_owner=outsider&record_ids=" + record)
                                            .getBytes(StandardCharsets.UTF_8),
                                    "application/x-www-form-urlencoded",
                                    auth("admin"))
                            .statusCode());
            for (int i = 0; i < 2; i++) {
                var preview =
                        a.result(h.json("POST", path + "?dry_run=true", good, auth("admin")), 200);
                assertEquals(true, preview.get("dry_run"));
                assertEquals(2, preview.get("transferred_count"));
                try (var db = h.store.open()) {
                    var unchanged = Service.record(db, a.table, record);
                    assertEquals(source, object(unchanged.get("data")).get("owner"));
                    assertEquals("0", unchanged.get("revision").toString());
                    assertEquals(
                            0,
                            db.count(
                                    "SELECT COUNT(*) FROM os_event WHERE action='updated' AND"
                                        + " record_id=?",
                                    record));
                    assertEquals(
                            0,
                            db.count(
                                    "SELECT COUNT(*) FROM os_security_event WHERE"
                                        + " action='account.assignments_transferred'"));
                }
            }
            assertEquals(
                    400, h.json("POST", path + "?dry_run=maybe", good, auth("admin")).statusCode());
            assertEquals(
                    400,
                    h.json("POST", path + "?dry_run=true&dry_run=false", good, auth("admin"))
                            .statusCode());
            assertEquals(
                    400,
                    h.form(
                                    ui,
                                    map(
                                            "new_owner",
                                            "target",
                                            "record_ids",
                                            record,
                                            "operation",
                                            "bad"),
                                    auth("admin"))
                            .statusCode());
            assertEquals(
                    400,
                    h.form(
                                    ui + "?x=1",
                                    map("new_owner", "target", "record_ids", record),
                                    auth("admin"))
                            .statusCode());
            var result =
                    a.result(h.json("POST", path + "?dry_run=false", good, auth("admin")), 200);
            assertEquals(false, result.get("dry_run"));
            assertEquals(2, result.get("transferred_count"));
            assertEquals(List.of(record, second), result.get("record_ids"));
            assertEquals(target, result.get("new_owner_id"));
            try (var db = h.store.open()) {
                assertEquals(
                        target,
                        object(Service.record(db, a.table, record).get("data")).get("owner"));
                assertEquals(source, RecordAccess.status(db, record).get("owner_id"));
                assertEquals(false, Service.account(db, source).get("active"));
                assertEquals(
                        1,
                        db.count(
                                "SELECT COUNT(*) FROM os_security_event WHERE"
                                        + " action='account.assignments_transferred'"));
            }
            assertEquals(409, h.json("POST", path, good, auth("admin")).statusCode());
        } finally {
            a.cleanup();
        }
    }
}
