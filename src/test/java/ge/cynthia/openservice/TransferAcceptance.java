package ge.cynthia.openservice;

import static ge.cynthia.openservice.MigrationSource.key;
import static ge.cynthia.openservice.Values.*;
import static ge.cynthia.openservice.WorkflowSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class TransferAcceptance {
    static final String API = "/api/openservice/migrations";

    static Map<String, Object> mapping(String target, Object fields) {
        return map("target", target, "fields", fields, "values", map());
    }

    static Map<String, Object> plan() {
        return map(
                "source",
                MigrationSource.origin(),
                "tables",
                map(
                        "sys_user_group",
                        mapping("sys_user_group", map("short_description", "name")),
                        "incident",
                        mapping(
                                "incident",
                                map(
                                        "short_description",
                                        "short_description",
                                        "state",
                                        "state",
                                        "priority",
                                        "priority"))),
                "identities",
                true,
                "roles",
                map("itil", "agent"),
                "history",
                true,
                "attachments",
                true);
    }

    static Map<String, Object> result(HttpResponse<byte[]> response, int status) throws Exception {
        assertEquals(
                status, response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
        return object(object(JSON.readValue(response.body(), Object.class)).get("result"));
    }

    static HttpResponse<byte[]> step(WorkflowSupport h, String job, String token) throws Exception {
        return h.client.send(
                HttpRequest.newBuilder(
                                URI.create(
                                        "http://localhost:"
                                                + h.environment.getProperty("local.server.port")
                                                + API
                                                + "/"
                                                + job
                                                + "/step"))
                        .header("Authorization", auth("admin"))
                        .header("Content-Type", "application/json")
                        .header("X-Source-Token", token)
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    static Map<String, Object> fetch(WorkflowSupport h, String job) throws Exception {
        Map<String, Object> state;
        int pages = 0;
        do {
            state = result(step(h, job, MigrationSource.TOKEN), 200);
            assertTrue(++pages < 50);
        } while (object(state.get("detail")).get("status").equals("fetching"));
        return state;
    }

    static void seed() {
        MigrationSource.reset();
        MigrationSource.tables.put(
                "sys_user",
                List.of(
                        map("sys_id", key(1), "user_name", "ImportedAgent", "active", "true"),
                        map(
                                "sys_id",
                                key(2),
                                "user_name",
                                "email@example.test",
                                "active",
                                "false")));
        MigrationSource.tables.put(
                "sys_user_role",
                List.of(
                        map("sys_id", key(3), "name", "itil"),
                        map("sys_id", key(4), "name", "unsupported_custom_role")));
        MigrationSource.tables.put(
                "sys_user_has_role",
                List.of(
                        map("sys_id", key(5), "user", map("value", key(1)), "role", key(3)),
                        map("sys_id", key(6), "user", key(1), "role", key(4))));
        MigrationSource.tables.put(
                "sys_user_group", List.of(map("sys_id", key(7), "name", "Migrated operations")));
        MigrationSource.tables.put(
                "sys_user_grmember",
                List.of(map("sys_id", key(8), "user", key(1), "group", key(7))));
        MigrationSource.tables.put(
                "incident",
                List.of(
                        map(
                                "sys_id",
                                key(9),
                                "short_description",
                                "Source incident",
                                "state",
                                "1",
                                "priority",
                                "3",
                                "sys_created_on",
                                "2026-01-01 00:00:00",
                                "sys_created_by",
                                "source.author",
                                "number",
                                "INC900")));
        MigrationSource.tables.put(
                "sys_journal_field",
                List.of(
                        map(
                                "sys_id",
                                key(10),
                                "name",
                                "incident",
                                "element_id",
                                key(9),
                                "element",
                                "work_notes",
                                "value",
                                "Internal source note",
                                "sys_created_by",
                                "source.author",
                                "sys_created_on",
                                "2026-01-01 00:00:00")));
        MigrationSource.tables.put(
                "sys_audit",
                List.of(
                        map(
                                "sys_id",
                                key(11),
                                "tablename",
                                "incident",
                                "documentkey",
                                key(9),
                                "fieldname",
                                "priority",
                                "oldvalue",
                                "4",
                                "newvalue",
                                "3")));
        MigrationSource.tables.put(
                "sys_attachment",
                List.of(
                        map(
                                "sys_id",
                                key(12),
                                "table_name",
                                "incident",
                                "table_sys_id",
                                key(9),
                                "file_name",
                                "proof.txt",
                                "content_type",
                                "text/plain",
                                "size_bytes",
                                "5")));
        MigrationSource.files.put(key(12), "proof".getBytes(StandardCharsets.UTF_8));
    }

    static void run(WorkflowSupport h) throws Exception {
        seed();
        h.account("reader", "reader");
        h.account("agent", "agent");
        for (String who : List.of("reader", "agent")) {
            assertEquals(403, h.get(API, auth(who)).statusCode());
            assertEquals(403, h.get("/admin/migrations", auth(who)).statusCode());
            assertEquals(403, h.json("POST", API, plan(), auth(who)).statusCode());
        }
        assertEquals(200, h.get("/admin/migrations", auth("admin")).statusCode());
        assertEquals(200, h.get("/static/migrations.js", auth("admin")).statusCode());
        assertEquals(400, h.get(API + "?unexpected=1", auth("admin")).statusCode());
        var job = result(h.json("POST", API, plan(), auth("admin")), 201);
        String id = job.get("job_id").toString(), path = API + "/" + id;
        assertEquals(409, h.json("POST", path + "/apply", map(), auth("admin")).statusCode());
        assertEquals(400, step(h, id, "").statusCode());
        assertEquals(502, step(h, id, "wrong-token").statusCode());
        job = result(h.get(path, auth("admin")), 200);
        assertEquals("", object(job.get("detail")).get("cursor"));
        result(
                step(h, id, MigrationSource.TOKEN),
                200); // Persist one page, then resume using a new HTTP client.
        h.client = HttpClient.newHttpClient();
        fetch(h, id);
        assertEquals(409, step(h, id, MigrationSource.TOKEN).statusCode());
        var preview = result(h.json("POST", path + "/preview", map(), auth("admin")), 200);
        assertEquals(
                map("records", 2, "accounts", 2, "memberships", 1, "attachments", 1),
                preview.get("changed"));
        assertEquals(2, preview.get("history_rows"));
        assertEquals(List.of("unsupported_custom_role"), preview.get("unmapped_source_roles"));
        try (var db = h.store.open()) {
            assertEquals(0, db.count("SELECT COUNT(*) FROM os_record"));
            assertEquals(0, db.count("SELECT COUNT(*) FROM os_transfer_link"));
            assertEquals(3, db.count("SELECT COUNT(*) FROM os_user"));
        }
        String stale =
                result(h.json("POST", API, plan(), auth("admin")), 201).get("job_id").toString();
        fetch(h, stale);
        result(h.json("POST", path + "/apply", map(), auth("admin")), 200);
        assertEquals(
                409,
                h.json("POST", API + "/" + stale + "/apply", map(), auth("admin")).statusCode());
        try (var db = h.store.open()) {
            var user = db.one("SELECT name,role,active FROM os_user WHERE user_id=?", key(1));
            assertEquals("agent", user.get("role"));
            assertEquals(0, ((Number) user.get("active")).intValue());
            assertEquals(
                    "sn" + key(2),
                    db.one("SELECT name FROM os_user WHERE user_id=?", key(2)).get("name"));
            assertEquals(
                    1,
                    db.count(
                            "SELECT COUNT(*) FROM os_group_member WHERE group_id=? AND user_id=?",
                            key(7),
                            key(1)));
            assertEquals("private", RecordAccess.status(db, key(9)).get("visibility"));
            assertEquals(
                    map(),
                    db.one("SELECT original FROM os_record WHERE sys_id=?", key(9))
                            .get("original"));
            String stored =
                    JSON.writeValueAsString(
                            db.all("SELECT definition,detail FROM os_transfer_job"));
            assertFalse(stored.contains(MigrationSource.TOKEN));
        }
        assertEquals(404, h.get("/api/now/table/incident/" + key(9), auth("reader")).statusCode());
        assertEquals(
                "proof",
                new String(
                        h.get("/api/now/attachment/" + key(12) + "/file", auth("admin")).body(),
                        StandardCharsets.UTF_8));
        assertEquals(
                404,
                h.get("/api/now/attachment/" + key(12) + "/file", auth("reader")).statusCode());
        assertTrue(
                new String(
                                h.get(path + "/archive?table=sys_journal_field", auth("admin"))
                                        .body(),
                                StandardCharsets.UTF_8)
                        .contains("Internal source note"));
        assertEquals(
                403, h.get(path + "/archive?table=sys_journal_field", auth("agent")).statusCode());
        assertEquals(400, h.get(path + "/archive?table=secret", auth("admin")).statusCode());
        assertEquals(
                400, h.get(path + "/archive?table=incident&after=bad", auth("admin")).statusCode());
        assertEquals(
                400, h.get(path + "/archive?table=incident&extra=1", auth("admin")).statusCode());
        assertEquals(
                400,
                h.json("POST", path + "/preview", map("extra", 1), auth("admin")).statusCode());
        assertEquals(404, h.get(API + "/" + key(999), auth("admin")).statusCode());
        assertEquals(400, h.json("POST", path + "/cutover", map(), auth("admin")).statusCode());
        var confirmations =
                map(
                        "source_frozen",
                        true,
                        "completeness_reviewed",
                        true,
                        "permissions_reviewed",
                        true,
                        "automation_reviewed",
                        true);
        assertEquals(
                400, h.json("POST", path + "/cutover", confirmations, auth("admin")).statusCode());
        String next =
                result(h.json("POST", path + "/sync", map(), auth("admin")), 200)
                        .get("job_id")
                        .toString();
        fetch(h, next);
        var unchanged =
                result(h.json("POST", API + "/" + next + "/apply", map(), auth("admin")), 200);
        assertEquals(
                map("records", 0, "accounts", 0, "memberships", 0, "attachments", 0),
                unchanged.get("changed"));
        String latest =
                result(h.json("POST", path + "/sync", map(), auth("admin")), 200)
                        .get("job_id")
                        .toString();
        fetch(h, latest);
        result(h.json("POST", API + "/" + latest + "/apply", map(), auth("admin")), 200);
        assertEquals(
                409,
                h.json("POST", API + "/" + next + "/cutover", confirmations, auth("admin"))
                        .statusCode());
        next = latest;
        result(h.json("POST", API + "/" + next + "/cutover", confirmations, auth("admin")), 200);
        assertEquals(
                "cutover",
                object(result(h.get(API + "/" + next, auth("admin")), 200).get("detail"))
                        .get("status"));
        // A changed source snapshot updates correlated records, accounts and binary content.
        MigrationSource.tables
                .get("incident")
                .getFirst()
                .put("short_description", "Source updated");
        MigrationSource.tables.get("sys_user").getFirst().put("user_name", "RenamedImported");
        MigrationSource.files.put(key(12), "other".getBytes(StandardCharsets.UTF_8));
        String changed =
                result(h.json("POST", path + "/sync", map(), auth("admin")), 200)
                        .get("job_id")
                        .toString();
        fetch(h, changed);
        result(h.json("POST", API + "/" + changed + "/apply", map(), auth("admin")), 200);
        assertTrue(
                new String(
                                h.get("/api/now/table/incident/" + key(9), auth("admin")).body(),
                                StandardCharsets.UTF_8)
                        .contains("Source updated"));
        assertEquals(
                "other",
                new String(
                        h.get("/api/now/attachment/" + key(12) + "/file", auth("admin")).body(),
                        StandardCharsets.UTF_8));
        // Local destination edits must neither be overwritten by sync nor pass cutover
        // verification.
        try (var db = h.store.open()) {
            db.update("UPDATE os_record SET revision=revision+1 WHERE sys_id=?", key(9));
            db.connection.commit();
        }
        assertEquals(
                409,
                h.json("POST", API + "/" + changed + "/cutover", confirmations, auth("admin"))
                        .statusCode());
        String conflict =
                result(h.json("POST", path + "/sync", map(), auth("admin")), 200)
                        .get("job_id")
                        .toString();
        fetch(h, conflict);
        assertEquals(
                409,
                h.json("POST", API + "/" + conflict + "/apply", map(), auth("admin")).statusCode());
        assertTrue(MigrationSource.queries.stream().anyMatch(q -> q.contains("sys_id>")));
        assertTrue(MigrationSource.queries.stream().anyMatch(q -> q.contains("groupIN" + key(7))));
        assertTrue(
                MigrationSource.queries.stream()
                        .allMatch(q -> q.contains("sysparm_display_value=false")));
        MigrationSource.reset();
    }
}
