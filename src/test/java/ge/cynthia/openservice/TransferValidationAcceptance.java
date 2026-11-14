package ge.cynthia.openservice;

import static ge.cynthia.openservice.MigrationSource.key;
import static ge.cynthia.openservice.TransferAcceptance.*;
import static ge.cynthia.openservice.Values.*;
import static ge.cynthia.openservice.WorkflowSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

final class TransferValidationAcceptance {
    static Map<String, Object> basic() {
        return map(
                "source",
                MigrationSource.origin(),
                "tables",
                map(
                        "incident",
                        mapping(
                                "incident",
                                map(
                                        "short_description",
                                        "short_description",
                                        "state",
                                        "state",
                                        "priority",
                                        "priority"))));
    }

    static void bad(WorkflowSupport h, Object plan) throws Exception {
        assertEquals(
                400,
                h.json("POST", API, plan, auth("admin")).statusCode(),
                JSON.writeValueAsString(plan));
    }

    static Map<String, Object> copy(Object value) {
        return object(JSON.readValue(JSON.writeValueAsString(value), Object.class));
    }

    static String staged(WorkflowSupport h, Map<String, Object> plan) throws Exception {
        String id = result(h.json("POST", API, plan, auth("admin")), 201).get("job_id").toString();
        try (var db = h.store.open()) {
            for (var table : MigrationSource.tables.entrySet())
                for (var row : table.getValue())
                    db.update(
                            "INSERT INTO"
                                + " os_transfer_item(job_id,source_table,source_id,data,content)"
                                + " VALUES(?,?,?,?,?)",
                            id,
                            table.getKey(),
                            row.get("sys_id"),
                            JSON.writeValueAsString(row),
                            MigrationSource.files.get(row.get("sys_id")));
            var state = object(TransferJobs.job(db, id).get("detail"));
            state.put("status", "review");
            state.put("phase", TransferJobs.phases(plan).size());
            TransferJobs.save(db, id, state);
            db.connection.commit();
        }
        return id;
    }

    static void replace(WorkflowSupport h, String job, String table, Map<String, Object> data)
            throws Exception {
        try (var db = h.store.open()) {
            db.update(
                    "UPDATE os_transfer_item SET data=? WHERE job_id=? AND source_table=? AND"
                            + " source_id=?",
                    JSON.writeValueAsString(data),
                    job,
                    table,
                    data.get("sys_id"));
            db.connection.commit();
        }
    }

    static void run(WorkflowSupport h) throws Exception {
        MigrationSource.reset();
        bad(h, map());
        bad(h, List.of());
        var p = basic();
        p.put("unexpected", true);
        bad(h, p);
        p = basic();
        p.put("tables", map());
        bad(h, p);
        p = basic();
        var many = map();
        for (int i = 0; i < 21; i++)
            many.put("t" + i, mapping("t" + i, map("short_description", "name")));
        p.put("tables", many);
        bad(h, p);
        for (String table : List.of("bad/path", "sys_user")) {
            p = basic();
            p.put("tables", map(table, mapping("incident", map("short_description", "name"))));
            bad(h, p);
        }
        p = basic();
        object(object(p.get("tables")).get("incident")).remove("values");
        bad(h, p);
        p = basic();
        object(object(p.get("tables")).get("incident")).put("target", "bad/path");
        bad(h, p);
        p = basic();
        object(p.get("tables"))
                .put("problem", mapping("incident", map("short_description", "name")));
        bad(h, p);
        for (Object fields :
                List.of(
                        map(),
                        map("bad/path", "name"),
                        map("short_description", 1),
                        map("short_description", "bad/path"))) {
            p = basic();
            object(object(p.get("tables")).get("incident")).put("fields", fields);
            bad(h, p);
        }
        many = map();
        for (int i = 0; i < 101; i++) many.put("field" + i, "name");
        p = basic();
        object(object(p.get("tables")).get("incident")).put("fields", many);
        bad(h, p);
        for (Object values :
                List.of(map("unknown", map("1", "2")), map("state", map("1", false)))) {
            p = basic();
            object(object(p.get("tables")).get("incident")).put("values", values);
            bad(h, p);
        }
        p = basic();
        p.put("history", "true");
        bad(h, p);
        for (Object role : Arrays.asList("superadmin", false, null)) {
            p = basic();
            p.put("roles", map("source", role));
            bad(h, p);
        }
        p = basic();
        p.put("identities", true);
        bad(h, p);
        assertEquals(200, h.get(API, auth("admin")).statusCode());
        // User-supplied translation installs a typed custom schema and maps source choices
        // atomically.
        String table = "u_migrated_" + id();
        var definition =
                map(
                        "version",
                        0,
                        "label",
                        "Migrated work",
                        "prefix",
                        "MW",
                        "fields",
                        map("short_description", "Summary", "category", "Category"),
                        "states",
                        map(),
                        "flow",
                        map(),
                        "field_rules",
                        map("category", map("type", "choice", "choices", List.of("standard"))));
        p =
                map(
                        "source",
                        MigrationSource.origin(),
                        "tables",
                        map(
                                "custom_source",
                                map(
                                        "target",
                                        table,
                                        "fields",
                                        map("short_description", "title", "category", "category"),
                                        "values",
                                        map("category", map("old", "standard")))),
                        "configuration",
                        map("format", Configuration.FORMAT, "tables", map(table, definition)));
        MigrationSource.tables.put(
                "custom_source",
                List.of(
                        map(
                                "sys_id",
                                key(100),
                                "title",
                                "Mapped custom record",
                                "category",
                                "old")));
        String custom = staged(h, p);
        result(h.json("POST", API + "/" + custom + "/preview", map(), auth("admin")), 200);
        try (var db = h.store.open()) {
            assertNull(db.one("SELECT name FROM os_schema WHERE name=?", table));
        }
        result(h.json("POST", API + "/" + custom + "/apply", map(), auth("admin")), 200);
        try (var db = h.store.open()) {
            assertEquals(
                    "standard",
                    object(Service.record(db, table, key(100)).get("data")).get("category"));
            db.update("DELETE FROM os_record WHERE sys_id=?", key(100));
            db.update("DELETE FROM os_record_access WHERE record_id=?", key(100));
            db.update("DELETE FROM os_schema WHERE name=?", table);
            db.update("DELETE FROM os_transfer_link");
            db.connection.commit();
        }
        seed();
        String job = staged(h, plan()), path = API + "/" + job;
        // Every failure rolls back accounts, records and correlations together.
        for (Object value : Arrays.asList(map("unexpected", "object"), null, "x".repeat(81))) {
            var invalidMetadata = copy(MigrationSource.tables.get("incident").getFirst());
            invalidMetadata.put("sys_created_by", value);
            replace(h, job, "incident", invalidMetadata);
            assertEquals(400, h.json("POST", path + "/apply", map(), auth("admin")).statusCode());
        }
        var incident = copy(MigrationSource.tables.get("incident").getFirst());
        incident.put("short_description", "");
        replace(h, job, "incident", incident);
        assertEquals(400, h.json("POST", path + "/apply", map(), auth("admin")).statusCode());
        replace(h, job, "incident", MigrationSource.tables.get("incident").getFirst());
        h.account("ImportedAgent", "agent");
        assertEquals(400, h.json("POST", path + "/apply", map(), auth("admin")).statusCode());
        try (var db = h.store.open()) {
            db.update("DELETE FROM os_user WHERE name='ImportedAgent'");
            db.connection.commit();
        }
        var unstagedMember = copy(MigrationSource.tables.get("sys_user_grmember").getFirst());
        try (var db = h.store.open()) {
            unstagedMember.put("user", RecordAccess.owner(db, "admin"));
        }
        replace(h, job, "sys_user_grmember", unstagedMember);
        assertEquals(400, h.json("POST", path + "/apply", map(), auth("admin")).statusCode());
        for (String part : List.of("user", "group")) {
            var member = copy(MigrationSource.tables.get("sys_user_grmember").getFirst());
            member.put(part, key(999));
            replace(h, job, "sys_user_grmember", member);
            assertEquals(400, h.json("POST", path + "/apply", map(), auth("admin")).statusCode());
        }
        var member = copy(MigrationSource.tables.get("sys_user_grmember").getFirst());
        member.put("group", "invalid");
        replace(h, job, "sys_user_grmember", member);
        assertEquals(400, h.json("POST", path + "/apply", map(), auth("admin")).statusCode());
        replace(
                h,
                job,
                "sys_user_grmember",
                MigrationSource.tables.get("sys_user_grmember").getFirst());
        var wrongParent = copy(MigrationSource.tables.get("sys_attachment").getFirst());
        wrongParent.put("table_name", "sys_user");
        wrongParent.put("table_sys_id", key(1));
        replace(h, job, "sys_attachment", wrongParent);
        assertEquals(400, h.json("POST", path + "/apply", map(), auth("admin")).statusCode());
        for (String name :
                List.of(
                        "",
                        "a".repeat(256),
                        "line\n.txt",
                        "delete\u007f.txt",
                        "path/file",
                        "path\\file")) {
            var attachment = copy(MigrationSource.tables.get("sys_attachment").getFirst());
            attachment.put("file_name", name);
            replace(h, job, "sys_attachment", attachment);
            assertEquals(400, h.json("POST", path + "/apply", map(), auth("admin")).statusCode());
        }
        var oversizedType = copy(MigrationSource.tables.get("sys_attachment").getFirst());
        oversizedType.put("content_type", "text/" + "a".repeat(251));
        replace(h, job, "sys_attachment", oversizedType);
        assertEquals(400, h.json("POST", path + "/apply", map(), auth("admin")).statusCode());
        for (String field : List.of("table_sys_id", "content_type")) {
            var attachment = copy(MigrationSource.tables.get("sys_attachment").getFirst());
            attachment.put(field, "bad");
            replace(h, job, "sys_attachment", attachment);
            assertEquals(400, h.json("POST", path + "/apply", map(), auth("admin")).statusCode());
        }
        replace(h, job, "sys_attachment", MigrationSource.tables.get("sys_attachment").getFirst());
        try (var db = h.store.open()) {
            assertEquals(0, db.count("SELECT COUNT(*) FROM os_record"));
            assertEquals(1, db.count("SELECT COUNT(*) FROM os_user"));
            assertEquals(0, db.count("SELECT COUNT(*) FROM os_transfer_link"));
        }
        // A local identifier collision cannot be adopted silently.
        try (var db = h.store.open()) {
            var item = Service.create(db, "incident", map("short_description", "Local"), "admin");
            db.update("UPDATE os_record SET sys_id=? WHERE sys_id=?", key(9), item.get("sys_id"));
            db.connection.commit();
        }
        assertEquals(409, h.json("POST", path + "/apply", map(), auth("admin")).statusCode());
        try (var db = h.store.open()) {
            db.update("DELETE FROM os_record");
            db.connection.commit();
        }
        result(h.json("POST", path + "/apply", map(), auth("admin")), 200);
        // Missing source items never delete destination data or allow cutover.
        MigrationSource.tables.put("incident", List.of());
        String next =
                result(h.json("POST", path + "/sync", map(), auth("admin")), 200)
                        .get("job_id")
                        .toString();
        fetch(h, next);
        // Remove attachment metadata too: an absent parent is a validation failure, never an
        // orphan.
        assertEquals(
                400,
                h.json("POST", API + "/" + next + "/apply", map(), auth("admin")).statusCode());
        try (var db = h.store.open()) {
            db.update(
                    "DELETE FROM os_transfer_item WHERE job_id=? AND source_table='sys_attachment'",
                    next);
            db.connection.commit();
        }
        var report = result(h.json("POST", API + "/" + next + "/apply", map(), auth("admin")), 200);
        assertEquals(2, objects(report.get("missing_source_items")).size());
        assertEquals(
                400,
                h.json(
                                "POST",
                                API + "/" + next + "/cutover",
                                map(
                                        "source_frozen",
                                        true,
                                        "completeness_reviewed",
                                        true,
                                        "permissions_reviewed",
                                        true,
                                        "automation_reviewed",
                                        true),
                                auth("admin"))
                        .statusCode());
        // Retargeting a previously imported ID requires a separate reviewed migration.
        seed();
        var changedPlan = plan();
        object(object(changedPlan.get("tables")).get("incident")).put("target", "problem");
        String retarget = staged(h, changedPlan);
        assertEquals(
                400,
                h.json("POST", API + "/" + retarget + "/apply", map(), auth("admin")).statusCode());
        // Empty selected groups still use a restrictive source membership query.
        MigrationSource.reset();
        String empty =
                result(h.json("POST", API, plan(), auth("admin")), 201).get("job_id").toString();
        fetch(h, empty);
        assertTrue(MigrationSource.queries.stream().anyMatch(q -> q.contains("sys_idISEMPTY")));
        MigrationSource.reset();
    }
}
