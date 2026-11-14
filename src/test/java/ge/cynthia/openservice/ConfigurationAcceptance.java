package ge.cynthia.openservice;

import static ge.cynthia.openservice.Values.*;
import static ge.cynthia.openservice.WorkflowSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.*;

final class ConfigurationAcceptance {
    final WorkflowSupport h;
    final String table = "u_test_" + id();
    static final String API = "/api/openservice/configuration";

    ConfigurationAcceptance(WorkflowSupport h) {
        this.h = h;
    }

    void cleanup() throws Exception {
        try (var db = h.store.open()) {
            db.update("DELETE FROM os_record WHERE \"table\" LIKE ?", table + "%");
            db.update("DELETE FROM os_schema WHERE name LIKE ?", table + "%");
            db.connection.commit();
        }
    }

    static Map<String, Object> definition() {
        return map(
                "version",
                0,
                "label",
                "Reusable service cases",
                "prefix",
                "CASE",
                "fields",
                map(
                        "short_description",
                        "Summary",
                        "amount",
                        "Amount",
                        "enabled",
                        "Enabled",
                        "quantity",
                        "Quantity",
                        "date",
                        "Date",
                        "when",
                        "Timestamp",
                        "category",
                        "Category"),
                "states",
                map(),
                "flow",
                map(),
                "field_rules",
                map(
                        "short_description",
                        map("type", "string", "required", true, "max_length", 50),
                        "amount",
                        map("type", "decimal"),
                        "enabled",
                        map("type", "boolean"),
                        "quantity",
                        map("type", "integer"),
                        "date",
                        map("type", "date"),
                        "when",
                        map("type", "datetime"),
                        "category",
                        map("type", "choice", "choices", List.of("service", "access"))));
    }

    void exampleAndAuthorization() throws Exception {
        String source =
                java.nio.file.Files.readString(
                        java.nio.file.Path.of("examples/service-operations.configuration.json"));
        var document =
                object(JSON.readValue(source.replace("u_service_", table + "_"), Object.class));
        assertEquals(200, install(document, false).statusCode());
        var asset = create(table + "_asset", map("short_description", "Shared service"), "admin");
        var dependent =
                create(
                        table + "_asset",
                        map(
                                "short_description",
                                "Dependent service",
                                "depends_on",
                                asset.get("sys_id")),
                        "admin");
        assertStatus(
                409,
                "DELETE",
                "/api/now/table/" + table + "_asset/" + asset.get("sys_id"),
                null,
                "admin");
        var ticket =
                create(
                        table + "_case",
                        map(
                                "short_description",
                                "General service request",
                                "asset",
                                dependent.get("sys_id"),
                                "estimate",
                                "1.25"),
                        "admin");
        String path = "/api/now/table/" + table + "_case/" + ticket.get("sys_id");
        for (String state : List.of("working", "review"))
            assertStatus(200, "PATCH", path, map("state", state), "admin");
        assertStatus(400, "PATCH", path, map("state", "resolved"), "admin");
        assertStatus(
                200, "PATCH", path, map("state", "resolved", "resolution", "Recovered"), "admin");
        var article =
                create(
                        table + "_article",
                        map("short_description", "Reusable instructions"),
                        "admin");
        path = "/api/now/table/" + table + "_article/" + article.get("sys_id");
        assertStatus(200, "PATCH", path, map("state", "review"), "admin");
        assertStatus(400, "PATCH", path, map("state", "published"), "admin");
        assertStatus(
                200,
                "PATCH",
                path,
                map("state", "published", "text", "Verified procedure"),
                "admin");
        h.account("QueuedWriter", "agent");
        try (var db = h.store.open()) {
            var user = db.one("SELECT * FROM os_user WHERE name='QueuedWriter'");
            Authentication.requireCurrent(db, user);
            db.update("UPDATE os_user SET auth_revision=auth_revision+1 WHERE name='QueuedWriter'");
            assertEquals(
                    401,
                    assertThrows(Failure.class, () -> Authentication.requireCurrent(db, user))
                            .status);
            db.connection.rollback();
        }
        if (h.store.postgres) {
            String csrf = h.login("QueuedWriter");
            String record = h.add("incident").get("sys_id").toString();
            try (var executor = Executors.newVirtualThreadPerTaskExecutor();
                    var gate = h.store.open()) {
                gate.all("SELECT pg_advisory_xact_lock(18439,4)");
                var request =
                        executor.submit(() ->
                                h.form(
                                        "/record/incident/" + record,
                                        map("csrf", csrf, "short_description", "must not save"),
                                        null));
                boolean waiting = false;
                for (int i = 0; i < 500; i++) {
                    gate.all("SELECT pg_stat_clear_snapshot()");
                    if (gate.count(
                                    "SELECT COUNT(*) FROM pg_stat_activity WHERE query='SELECT"
                                        + " pg_advisory_xact_lock(18439,4)' AND"
                                        + " wait_event_type='Lock'")
                            > 0) {
                        waiting = true;
                        break;
                    }
                    Thread.sleep(10);
                }
                gate.update(
                        "UPDATE os_user SET auth_revision=auth_revision+1 WHERE"
                            + " name='QueuedWriter'");
                gate.connection.commit();
                assertTrue(waiting, "Mutation must wait for the shared write lock");
                assertEquals(401, request.get(15, TimeUnit.SECONDS).statusCode());
            }
        }
    }

    static Map<String, Object> bundle(String table, Map<String, Object> definition) {
        return map("format", Configuration.FORMAT, "tables", map(table, definition));
    }

    HttpResponse<byte[]> install(Object document, boolean dry) throws Exception {
        return h.json("POST", API + "?dry_run=" + dry, document, auth("admin"));
    }

    Map<String, Object> result(HttpResponse<byte[]> response, int status) {
        assertEquals(status, response.statusCode(), html(response));
        return object(parsed(response).get("result"));
    }

    Map<String, Object> create(String target, Map<String, Object> data, String user)
            throws Exception {
        return result(h.json("POST", "/api/now/table/" + target, data, auth(user)), 201);
    }

    void assertStatus(int status, String method, String path, Object data, String user)
            throws Exception {
        var response = h.json(method, path, data, auth(user));
        assertEquals(status, response.statusCode(), html(response));
    }

    void packagesAndRecords() throws Exception {
        var definition = definition();
        var document = bundle(table, definition);
        for (Object invalid :
                List.of(
                        map(),
                        map("format", "other", "tables", map()),
                        map("format", Configuration.FORMAT, "tables", map())))
            assertEquals(400, install(invalid, false).statusCode());
        var tooMany = new LinkedHashMap<String, Object>();
        for (int i = 0; i < 51; i++) tooMany.put("u_t" + i, definition());
        assertEquals(
                400,
                install(map("format", Configuration.FORMAT, "tables", tooMany), false)
                        .statusCode());
        assertEquals(true, result(install(document, true), 200).get("dry_run"));
        assertEquals(404, h.get("/workspace/" + table, auth("admin")).statusCode());
        assertEquals(false, result(install(document, false), 200).get("dry_run"));
        assertEquals(409, install(document, false).statusCode());
        assertEquals(200, h.get("/workspace/" + table, auth("admin")).statusCode());
        var payload =
                map(
                        "short_description",
                        "Typed case",
                        "quantity",
                        "42",
                        "amount",
                        "-13.50",
                        "enabled",
                        "true",
                        "date",
                        "2026-11-14",
                        "when",
                        "2026-11-14T10:15:30Z",
                        "category",
                        "service");
        var row = create(table, payload, "admin");
        String path = "/api/now/table/" + table + "/" + row.get("sys_id");
        assertEquals("42", row.get("quantity"));
        assertTrue(
                html(h.get("/record/" + table + "/" + row.get("sys_id"), auth("admin")))
                        .contains("decimal"));
        assertEquals(
                200,
                h.get(
                                "/api/now/stats/"
                                        + table
                                        + "?sysparm_count=true&sysparm_group_by=category",
                                auth("admin"))
                        .statusCode());
        for (var invalid :
                List.of(
                        map("quantity", "9223372036854775808"),
                        map("quantity", "1.1"),
                        map("amount", "NaN"),
                        map("enabled", "yes"),
                        map("date", "2026-02-30"),
                        map("when", "yesterday"),
                        map("category", "unknown"),
                        map("short_description", "x".repeat(51)),
                        map("quantity", 42))) assertStatus(400, "PATCH", path, invalid, "admin");
        assertStatus(
                200,
                "PATCH",
                path,
                map("enabled", "false", "quantity", "-9223372036854775808"),
                "admin");
        assertStatus(200, "PATCH", path, map("quantity", "", "amount", ""), "admin");
        assertEquals(
                "2", object(parsed(h.get(path, auth("admin"))).get("result")).get("sys_mod_count"));
        String imported = id();
        assertStatus(
                400,
                "POST",
                "/api/openservice/import/" + table + "?dry_run=false",
                List.of(
                        map(
                                "sys_id",
                                imported,
                                "short_description",
                                "bad import",
                                "quantity",
                                "no")),
                "admin");
        assertStatus(
                400,
                "POST",
                "/api/openservice/import/" + table,
                List.of(map("sys_id", imported, "short_description", "")),
                "admin");
        assertStatus(
                200,
                "POST",
                "/api/openservice/import/" + table + "?dry_run=false",
                List.of(map("sys_id", imported, "short_description", "Imported", "quantity", 12)),
                "admin");
        assertEquals(
                "12",
                object(
                                parsed(
                                                h.get(
                                                        "/api/now/table/" + table + "/" + imported,
                                                        auth("admin")))
                                        .get("result"))
                        .get("quantity"));
        // Tightening validation is rejected atomically when persisted data would violate it.
        definition.put("version", 1);
        object(object(definition.get("field_rules")).get("quantity")).put("required", true);
        assertEquals(400, install(document, false).statusCode());
        object(object(definition.get("field_rules")).get("quantity")).remove("required");
        object(object(definition.get("field_rules")).get("short_description")).put("max_length", 2);
        assertEquals(400, install(document, false).statusCode());
        assertEquals(
                1,
                object(object(parsed(h.get(API, auth("admin"))).get("tables")).get(table))
                        .get("version"));
        object(object(definition.get("field_rules")).get("short_description"))
                .put("max_length", 60);
        object(definition.get("fields")).put("external_key", "External key");
        assertEquals(200, install(document, false).statusCode());
        try (var db = h.store.open()) {
            assertEquals(2, Service.schema(db, table).get("version"));
            assertEquals(2, db.count("SELECT COUNT(*) FROM os_record WHERE \"table\"=?", table));
            assertEquals(
                    2,
                    db.count(
                            "SELECT COUNT(*) FROM os_security_event WHERE"
                                    + " action='configuration.installed'"));
        }
        try (var reopened = new Store(h.environment).open()) {
            assertEquals(2, Service.schema(reopened, table).get("version"));
            assertEquals(
                    "12",
                    object(Service.record(reopened, table, imported).get("data")).get("quantity"));
        }
        // Existing schemas are exported with a base revision, without changing storage.
        var exported = parsed(h.get(API, auth("admin")));
        assertEquals(0, object(object(exported.get("tables")).get("incident")).get("version"));
        object(definition.get("fields")).remove("enabled");
        definition.put("version", 2);
        assertEquals(400, install(document, false).statusCode());
    }

    void relationshipsAndWorkflows() throws Exception {
        h.account("ConfigAgent", "agent");
        h.account("ConfigReader", "reader");
        String user =
                object(parsed(h.get("/api/openservice/me", auth("ConfigAgent"))).get("result"))
                        .get("user_id")
                        .toString();
        String reader =
                object(parsed(h.get("/api/openservice/me", auth("ConfigReader"))).get("result"))
                        .get("user_id")
                        .toString();
        String group = h.add("sys_user_group").get("sys_id").toString();
        String asset = h.add("cmdb_ci").get("sys_id").toString();
        var definition = definition();
        object(definition.get("fields"))
                .putAll(
                        map(
                                "state",
                                "State",
                                "resolution",
                                "Resolution",
                                "asset",
                                "Asset",
                                "owner",
                                "Owner",
                                "team",
                                "Team"));
        definition.put("states", map("new", "New", "review", "Review", "approved", "Approved"));
        definition.put(
                "flow",
                map(
                        "new",
                        List.of("review"),
                        "review",
                        List.of("approved"),
                        "approved",
                        List.of()));
        definition.put(
                "transition_rules",
                map(
                        "approved",
                        map("roles", List.of("admin"), "required_fields", List.of("resolution"))));
        object(definition.get("field_rules"))
                .putAll(
                        map(
                                "asset",
                                map("type", "reference", "target", "cmdb_ci"),
                                "owner",
                                map("type", "account"),
                                "team",
                                map("type", "reference", "target", "sys_user_group")));
        definition.put("assignment", map("user_field", "owner", "group_field", "team"));
        assertEquals(200, install(bundle(table, definition), false).statusCode());
        String records = "/api/now/table/" + table;
        create(table, map("short_description", "Unassigned intake"), "admin");
        for (var invalid :
                List.of(
                        map("owner", "missing"),
                        map("asset", group),
                        map("asset", "missing"),
                        map("owner", reader, "team", group),
                        map("owner", user, "team", group))) {
            invalid.put("short_description", "Invalid relationship");
            assertStatus(400, "POST", records, invalid, "admin");
        }
        assertStatus(
                200,
                "PUT",
                "/api/openservice/groups/" + group + "/members",
                map("user_id", user),
                "admin");
        var row =
                create(
                        table,
                        map(
                                "short_description",
                                "Assigned case",
                                "owner",
                                user,
                                "team",
                                group,
                                "asset",
                                asset),
                        "ConfigAgent");
        String path = records + "/" + row.get("sys_id");
        assertStatus(409, "DELETE", "/api/now/table/cmdb_ci/" + asset, null, "admin");
        assertStatus(409, "DELETE", "/api/now/table/sys_user_group/" + group, null, "admin");
        assertStatus(200, "PATCH", path, map("state", "review"), "ConfigAgent");
        assertStatus(
                403, "PATCH", path, map("state", "approved", "resolution", "Ready"), "ConfigAgent");
        assertStatus(400, "PATCH", path, map("state", "approved"), "admin");
        assertStatus(200, "PATCH", path, map("state", "approved", "resolution", "Ready"), "admin");
        assertStatus(400, "PATCH", path, map("resolution", ""), "admin");
        assertStatus(
                200,
                "PATCH",
                "/api/openservice/access/cmdb_ci/" + asset,
                map("visibility", "private", "owner_id", reader),
                "admin");
        assertStatus(
                400,
                "POST",
                records,
                map("short_description", "Hidden reference", "asset", asset),
                "ConfigAgent");
        assertStatus(204, "DELETE", path, null, "admin");
        assertStatus(204, "DELETE", "/api/now/table/cmdb_ci/" + asset, null, "admin");
        assertStatus(204, "DELETE", "/api/now/table/sys_user_group/" + group, null, "admin");
        // A package may contain references to a table declared later in the same package.
        String source = table + "s", target = table + "t";
        var left = definition();
        var right = definition();
        object(left.get("fields")).put("parent", "Parent");
        object(left.get("field_rules")).put("parent", map("type", "reference", "target", target));
        assertEquals(
                200,
                install(
                                map(
                                        "format",
                                        Configuration.FORMAT,
                                        "tables",
                                        map(source, left, target, right)),
                                false)
                        .statusCode());
        var parent = create(target, map("short_description", "Dependency"), "admin");
        create(
                source,
                map("short_description", "Dependent", "parent", parent.get("sys_id")),
                "admin");
    }

    void permissionsAndConflicts() throws Exception {
        h.account("PackageAgent", "agent");
        h.account("PackageReader", "reader");
        var document = bundle(table, definition());
        for (String user : List.of("PackageAgent", "PackageReader")) {
            assertEquals(403, h.get(API, auth(user)).statusCode());
            assertStatus(403, "POST", API, document, user);
            assertEquals(403, h.get("/admin/configuration", auth(user)).statusCode());
        }
        assertEquals(401, h.get(API, null).statusCode());
        assertStatus(405, "DELETE", API, document, "admin");
        assertStatus(400, "GET", API + "?x=1", null, "admin");
        for (String query : List.of("?x=1", "?dry_run=bad", "?dry_run=true&dry_run=false"))
            assertStatus(400, "POST", API + query, document, "admin");
        assertEquals(200, h.json("POST", API, document, auth("admin")).statusCode());
        assertEquals(404, h.get("/workspace/" + table, auth("admin")).statusCode());
        h.login("admin");
        assertEquals(403, h.json("POST", API, document, null).statusCode());
        var page = h.get("/admin/configuration", null);
        assertEquals(200, page.statusCode());
        for (var form :
                List.of(
                        map("operation", "unknown", "document", "{}"),
                        map("operation", "preview", "document", "{"))) {
            form.put("csrf", csrf(page));
            assertEquals(400, h.form("/admin/configuration", form, null).statusCode());
        }
        assertTrue(
                html(h.form(
                                "/admin/configuration",
                                map(
                                        "csrf",
                                        csrf(page),
                                        "operation",
                                        "preview",
                                        "document",
                                        JSON.writeValueAsString(document)),
                                null))
                        .contains("Preview passed"));
        // Two matching-version installs race: exactly one succeeds.
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(() -> install(document, false).statusCode());
            var b = executor.submit(() -> install(document, false).statusCode());
            assertEquals(
                    Set.of(200, 409),
                    Set.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS)));
        }
        var other = definition();
        object(other.get("field_rules"))
                .put("quantity", map("type", "reference", "target", "missing"));
        assertEquals(400, install(bundle(table + "bad", other), false).statusCode());
        assertEquals(404, h.get("/workspace/" + table + "bad", auth("admin")).statusCode());
        var badNew = definition();
        badNew.put("version", 1);
        assertEquals(409, install(bundle(table + "new", badNew), false).statusCode());
        assertEquals(400, install(bundle("invalid_table", definition()), false).statusCode());
        // A valid first table cannot survive an invalid second table in the package.
        assertEquals(
                409,
                install(
                                map(
                                        "format",
                                        Configuration.FORMAT,
                                        "tables",
                                        map(table + "first", definition(), table, definition())),
                                false)
                        .statusCode());
        assertEquals(404, h.get("/workspace/" + table + "first", auth("admin")).statusCode());
    }
}
