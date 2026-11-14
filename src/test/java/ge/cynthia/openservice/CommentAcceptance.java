package ge.cynthia.openservice;

import static ge.cynthia.openservice.Values.*;
import static ge.cynthia.openservice.WorkflowSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

final class CommentAcceptance {
    static void run(WorkflowSupport h) throws Exception {
        h.account("reader", "reader");
        h.account("agent", "agent");
        String id = h.add("incident").get("sys_id").toString();
        String path = "/api/openservice/comments/incident/" + id;
        assertEquals(List.of(), parsed(h.get(path, auth("reader"))).get("result"));
        for (String role : List.of("reader", "agent", "admin")) {
            var response = h.json("POST", path, map("body", "  From " + role + "  "), auth(role));
            assertEquals(201, response.statusCode(), html(response));
            var row = object(parsed(response).get("result"));
            assertEquals(role, row.get("actor"));
            assertEquals("From " + role, object(row.get("detail")).get("body"));
            assertNotNull(row.get("created"));
            assertNotNull(row.get("entry_id"));
        }
        for (Object invalid :
                List.of(
                        map(),
                        map("body", 1),
                        map("body", "ok", "actor", "admin"),
                        map("body", " "),
                        map("body", "x".repeat(65537))))
            assertEquals(400, h.json("POST", path, invalid, auth("reader")).statusCode());
        assertEquals(
                201,
                h.json("POST", path, map("body", "😀".repeat(65536)), auth("reader")).statusCode());
        assertEquals(400, h.get(path + "?unknown=1", auth("reader")).statusCode());
        assertEquals(400, h.get(path + "?q=1&q=2", auth("reader")).statusCode());
        assertEquals(405, h.json("PATCH", path, map("body", "edit"), auth("reader")).statusCode());
        assertEquals(404, h.get(path.replace("incident", "problem"), auth("reader")).statusCode());
        assertEquals(
                404,
                h.json(
                                "POST",
                                "/api/openservice/comments/incident/" + id(),
                                map("body", "missing"),
                                auth("reader"))
                        .statusCode());
        assertEquals(
                403,
                h.json(
                                "PATCH",
                                "/api/now/table/incident/" + id,
                                map("short_description", "denied"),
                                auth("reader"))
                        .statusCode());
        String token = h.login("reader");
        assertEquals(403, h.json("POST", path, map("body", "no csrf"), null).statusCode());
        assertEquals(
                302,
                h.form(
                                "/record/incident/" + id + "/comment",
                                map("csrf", token, "body", "Browser form"),
                                null)
                        .statusCode());
        assertTrue(html(h.get("/record/incident/" + id, null)).contains("Browser form"));
        assertTrue(html(h.get("/audit", null)).contains("Browser form"));
        try (var db = h.store.open()) {
            var item = Service.record(db, "incident", id);
            Service.audit(db, item, "admin", "note", map("body", "INTERNAL_SECRET"));
            for (int i = 0; i < 101; i++)
                Service.audit(db, item, "admin", "comment", map("body", "page-" + i));
            db.connection.commit();
        }
        var rows = objects(parsed(h.get(path, auth("reader"))).get("result"));
        assertEquals(100, rows.size());
        assertEquals("page-100", object(rows.getFirst().get("detail")).get("body"));
        assertEquals("page-1", object(rows.getLast().get("detail")).get("body"));
        assertFalse(html(h.get(path, auth("admin"))).contains("INTERNAL_SECRET"));
        try (var db = h.store.open()) {
            RecordAccess.change(
                    db,
                    Service.record(db, "incident", id),
                    RecordAccess.owner(db, "admin"),
                    map("visibility", "private", "owner_id", RecordAccess.owner(db, "agent")));
            db.connection.commit();
        }
        assertEquals(404, h.get(path, auth("reader")).statusCode());
        assertEquals(404, h.json("POST", path, map("body", "hidden"), auth("reader")).statusCode());
        assertEquals(
                404,
                h.form(
                                "/record/incident/" + id + "/comment",
                                map("csrf", token, "body", "hidden"),
                                null)
                        .statusCode());
        assertEquals(200, h.get(path, auth("agent")).statusCode());
    }
}
