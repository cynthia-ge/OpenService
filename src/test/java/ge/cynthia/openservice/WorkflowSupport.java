package ge.cynthia.openservice;

import static ge.cynthia.openservice.Values.*;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

abstract class WorkflowSupport {
    static final String PASSWORD = "test-only-password-long";
    @Autowired Store store;
    @Autowired Environment environment;
    HttpClient client;

    @BeforeEach
    void reset() throws Exception {
        client =
                HttpClient.newBuilder()
                        .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                        .build();
        try (var db = store.open()) {
            for (String table : List.of("os_attachment", "os_event", "os_record"))
                db.update("DELETE FROM " + table);
            db.update("DELETE FROM os_user WHERE name<>?", "admin");
            db.connection.commit();
        }
    }

    @Test
    void oversizedRequestIsRejectedBeforeMutation() throws Exception {
        var response =
                request(
                        "POST",
                        "/api/now/table/incident",
                        new byte[6 * 1024 * 1024 + 1],
                        "application/json",
                        auth("admin"));
        assertEquals(413, response.statusCode());
        try (var db = store.open()) {
            assertEquals(
                    0L,
                    ((Number) db.one("SELECT COUNT(*) AS n FROM os_record").get("n")).longValue());
        }
    }

    @Test
    void jsonMediaTypesAreParsedExactly() throws Exception {
        byte[] body = JSON.writeValueAsBytes(map("short_description", "Media type validation"));
        assertEquals(
                415,
                request(
                                "POST",
                                "/api/now/table/incident",
                                body,
                                "application/json-invalid",
                                auth("admin"))
                        .statusCode());
        assertEquals(
                201,
                request(
                                "POST",
                                "/api/now/table/incident",
                                body,
                                "application/vnd.openservice+json; charset=utf-8",
                                auth("admin"))
                        .statusCode());
    }

    @Test
    void repeatedTableQueryParametersAreRejected() throws Exception {
        assertEquals(
                400,
                get("/api/now/table/incident?sysparm_limit=1&sysparm_limit=2", auth("admin"))
                        .statusCode());
        var created =
                object(
                        JSON.readValue(
                                json(
                                                "POST",
                                                "/api/now/table/incident",
                                                map("short_description", "Projection"),
                                                auth("admin"))
                                        .body(),
                                Object.class));
        String id = object(created.get("result")).get("sys_id").toString();
        assertEquals(
                400,
                get(
                                "/api/now/table/incident/"
                                        + id
                                        + "?sysparm_fields=sys_id&sysparm_fields=number",
                                auth("admin"))
                        .statusCode());
    }

    @AfterEach
    void close() {
        client.close();
    }

    String base() {
        return "http://localhost:" + environment.getProperty("local.server.port");
    }

    static String auth(String name) {
        return "Basic "
                + Base64.getEncoder()
                        .encodeToString((name + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
    }

    HttpResponse<byte[]> request(
            String method, String path, byte[] body, String type, String authorization)
            throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base() + path));
        if (type != null) request.header("Content-Type", type);
        if (authorization != null) request.header("Authorization", authorization);
        return client.send(
                request.method(
                                method,
                                HttpRequest.BodyPublishers.ofByteArray(
                                        body == null ? new byte[0] : body))
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    HttpResponse<byte[]> get(String path, String authorization) throws Exception {
        return request("GET", path, null, null, authorization);
    }

    HttpResponse<byte[]> json(String method, String path, Object value, String authorization)
            throws Exception {
        return request(
                method, path, JSON.writeValueAsBytes(value), "application/json", authorization);
    }

    HttpResponse<byte[]> form(String path, Map<String, Object> values, String authorization)
            throws Exception {
        String body =
                String.join(
                        "&",
                        values.entrySet().stream()
                                .map(e ->
                                        URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                                                + "="
                                                + URLEncoder.encode(
                                                        e.getValue().toString(),
                                                        StandardCharsets.UTF_8))
                                .toList());
        return request(
                "POST",
                path,
                body.getBytes(StandardCharsets.UTF_8),
                "application/x-www-form-urlencoded",
                authorization);
    }

    HttpResponse<byte[]> upload(String path, String filename, byte[] payload, String authorization)
            throws Exception {
        var body = new ByteArrayOutputStream();
        body.write(
                ("--OpenServiceTestBoundary\r\n"
                                + "Content-Disposition: form-data; name=\"file\"; filename=\""
                                + filename
                                + "\"\r\nContent-Type: application/octet-stream\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
        body.write(payload);
        body.write("\r\n--OpenServiceTestBoundary--\r\n".getBytes(StandardCharsets.UTF_8));
        return request(
                "POST",
                path,
                body.toByteArray(),
                "multipart/form-data; boundary=OpenServiceTestBoundary",
                authorization);
    }

    static String html(HttpResponse<byte[]> response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    static Map<String, Object> parsed(HttpResponse<byte[]> response) {
        return object(JSON.readValue(response.body(), Object.class));
    }

    static String csrf(HttpResponse<byte[]> response) {
        var match = Pattern.compile("name=\"csrf\"[^>]*value=\"([^\"]+)\"").matcher(html(response));
        assertTrue(match.find(), html(response));
        return match.group(1);
    }

    String login(String name) throws Exception {
        String token = csrf(get("/login", null));
        assertEquals(
                302,
                form("/login", map("csrf", token, "username", name, "password", PASSWORD), null)
                        .statusCode());
        var home = get("/", null);
        assertEquals(200, home.statusCode(), html(home));
        return csrf(home);
    }

    Map<String, Object> add(String table) throws Exception {
        var response =
                json(
                        "POST",
                        "/api/now/table/" + table,
                        map("short_description", "Synthetic evaluation"),
                        auth("admin"));
        assertEquals(201, response.statusCode(), html(response));
        return object(parsed(response).get("result"));
    }

    void account(String name, String role) throws Exception {
        var response =
                json(
                        "POST",
                        "/api/openservice/users",
                        map("name", name, "role", role, "password", PASSWORD),
                        auth("admin"));
        assertEquals(201, response.statusCode(), html(response));
    }

    @Test
    void sessionLoginCsrfAndAccountOnboarding() throws Exception {
        String token = csrf(get("/login", null));
        for (String invalid : List.of("", "不正"))
            assertEquals(
                    403,
                    form(
                                    "/login",
                                    map("csrf", invalid, "username", "admin", "password", PASSWORD),
                                    null)
                            .statusCode());
        for (var credentials :
                List.of(
                        map("username", "missing", "password", PASSWORD),
                        map("username", "admin", "password", "wrong"))) {
            credentials.put("csrf", token);
            assertEquals(401, form("/login", credentials, null).statusCode());
        }
        String current = login("admin");
        assertNotEquals(token, current);
        assertEquals(
                403,
                form(
                                "/record/incident/new",
                                map("csrf", token, "short_description", "Rejected"),
                                null)
                        .statusCode());
        assertEquals(200, get("/admin/accounts", null).statusCode());
        for (var fields :
                List.of(
                        map(
                                "name",
                                "<script>alert(1)</script>",
                                "role",
                                "agent",
                                "password",
                                PASSWORD),
                        map("name", "admin", "role", "agent", "password", PASSWORD),
                        map("name", "Incomplete", "role", "root", "password", PASSWORD),
                        map("name", "Incomplete", "role", "reader", "password", ""))) {
            fields.put("csrf", current);
            var response = form("/admin/accounts", fields, null);
            assertEquals(400, response.statusCode(), html(response));
            assertTrue(html(response).contains("role=\"alert\""));
            assertFalse(html(response).contains(PASSWORD));
            assertFalse(html(response).contains("<script>"));
        }
        assertEquals(
                403,
                form(
                                "/admin/accounts",
                                map("name", "Rejected", "role", "admin", "password", PASSWORD),
                                null)
                        .statusCode());
        for (String role : List.of("agent", "reader")) {
            var response =
                    form(
                            "/admin/accounts",
                            map("csrf", current, "name", role, "role", role, "password", PASSWORD),
                            null);
            assertEquals(302, response.statusCode());
            assertTrue(
                    html(get(response.headers().firstValue("Location").orElseThrow(), null))
                            .contains(role));
        }
        assertEquals(302, form("/logout", map("csrf", current), null).statusCode());
        assertEquals(302, get("/", null).statusCode());
        for (String role : List.of("agent", "reader")) {
            current = login(role);
            assertFalse(html(get("/", null)).contains("href=\"/admin/accounts\""));
            assertEquals(403, get("/admin/accounts", null).statusCode());
            assertEquals(
                    403,
                    form(
                                    "/admin/accounts",
                                    map(
                                            "csrf",
                                            current,
                                            "name",
                                            "Blocked",
                                            "role",
                                            "admin",
                                            "password",
                                            PASSWORD),
                                    null)
                            .statusCode());
            assertEquals(302, form("/logout", map("csrf", current), null).statusCode());
        }
    }

    @Test
    void formsNotesAndReadOnlyWorkspace() throws Exception {
        String token = login("admin");
        assertEquals(
                403,
                form("/record/incident/new", map("short_description", "No csrf"), null)
                        .statusCode());
        var created =
                form(
                        "/record/incident/new",
                        map(
                                "csrf",
                                token,
                                "short_description",
                                "UI incident",
                                "priority",
                                "2",
                                "state",
                                "1"),
                        null);
        assertEquals(302, created.statusCode(), html(created));
        String location = created.headers().firstValue("Location").orElseThrow();
        assertEquals(200, get(location, null).statusCode());
        assertEquals(
                302,
                form(
                                location,
                                map(
                                        "csrf",
                                        token,
                                        "short_description",
                                        "UI updated",
                                        "revision",
                                        "0",
                                        "state",
                                        "2"),
                                null)
                        .statusCode());
        assertEquals(
                302,
                form(location + "/note", map("csrf", token, "body", "Internal update"), null)
                        .statusCode());
        assertTrue(html(get(location, null)).contains("Internal update"));
        for (String note : List.of("", "x".repeat(65537)))
            assertEquals(
                    400,
                    form(location + "/note", map("csrf", token, "body", note), null).statusCode());
        assertEquals(
                404,
                form("/record/incident/new/note", map("csrf", token, "body", "No record"), null)
                        .statusCode());
        account("reader", "reader");
        var readonly = get(location, auth("reader"));
        assertEquals(200, readonly.statusCode());
        assertTrue(html(readonly).contains("readonly"));
        assertFalse(html(readonly).contains("Save changes"));
        assertEquals(200, get("/audit", null).statusCode());
        assertEquals(404, get("/missing", auth("admin")).statusCode());
    }

    @Test
    void attachmentMetadataBinaryIsolationAndDeletion() throws Exception {
        account("agent", "agent");
        account("reader", "reader");
        var incident = add("incident");
        var problem = add("problem");
        String upload = "/record/incident/" + incident.get("sys_id") + "/attachment";
        assertEquals(400, form(upload, map(), auth("admin")).statusCode());
        assertEquals(400, upload(upload, "", new byte[] {1}, auth("admin")).statusCode());
        assertEquals(
                400,
                upload(upload, "large.bin", new byte[5 * 1024 * 1024 + 1], auth("admin"))
                        .statusCode());
        byte[] content = new byte[] {0, (byte) 255, 3};
        assertEquals(302, upload(upload, "../evidence.bin", content, auth("admin")).statusCode());
        assertEquals(
                302,
                upload(
                                "/record/problem/" + problem.get("sys_id") + "/attachment",
                                "???",
                                new byte[0],
                                auth("admin"))
                        .statusCode());
        var listing = objects(parsed(get("/api/now/attachment", auth("admin"))).get("result"));
        assertEquals(2, listing.size());
        assertEquals(
                listing.stream().map(row -> row.get("sys_id")).sorted().toList(),
                listing.stream().map(row -> row.get("sys_id")).toList());
        var file =
                listing.stream()
                        .filter(row -> row.get("file_name").equals("evidence.bin"))
                        .findFirst()
                        .orElseThrow();
        String path = "/api/now/attachment/" + file.get("sys_id");
        assertEquals("3", file.get("size_bytes"));
        assertEquals(base() + path + "/file", file.get("download_link"));
        for (String role : List.of("admin", "agent", "reader")) {
            assertEquals(file, parsed(get(path, auth(role))).get("result"));
            var response = get(path + "/file", auth(role));
            assertArrayEquals(content, response.body());
            assertEquals(
                    "nosniff",
                    response.headers().firstValue("X-Content-Type-Options").orElseThrow());
            assertTrue(
                    response.headers()
                            .firstValue("X-Attachment-Metadata")
                            .orElseThrow()
                            .contains("evidence.bin"));
        }
        assertArrayEquals(content, get("/attachment/" + file.get("sys_id"), auth("admin")).body());
        assertEquals(404, get("/attachment/missing", auth("admin")).statusCode());
        assertEquals(
                200, get("/record/incident/" + incident.get("sys_id"), auth("admin")).statusCode());
        try (var db = store.open()) {
            db.update(
                    "INSERT INTO os_attachment(sys_id,record_id,name,content) VALUES(?,?,?,?)",
                    "f".repeat(32),
                    "missing",
                    "orphan",
                    new byte[] {1});
            db.connection.commit();
        }
        assertEquals(404, get("/api/now/attachment/" + "f".repeat(32), auth("admin")).statusCode());
        assertEquals(
                2, objects(parsed(get("/api/now/attachment", auth("admin"))).get("result")).size());
        assertEquals(
                List.of(file),
                parsed(
                                get(
                                        "/api/now/attachment?sysparm_query=table_name%3Dincident%5Etable_sys_id%3D"
                                                + incident.get("sys_id"),
                                        auth("admin")))
                        .get("result"));
        assertEquals(
                List.of(file),
                parsed(
                                get(
                                        "/api/now/attachment?sysparm_query=file_name%3Devidence.bin",
                                        auth("admin")))
                        .get("result"));
        assertEquals(
                List.of(file),
                parsed(
                                get(
                                        "/api/now/attachment?sysparm_query=sys_id%3D"
                                                + file.get("sys_id"),
                                        auth("admin")))
                        .get("result"));
        assertEquals(
                List.of(listing.get(1)),
                parsed(get("/api/now/attachment?sysparm_limit=1&sysparm_offset=1", auth("admin")))
                        .get("result"));
        assertEquals(
                List.of(),
                parsed(get("/api/now/attachment?sysparm_offset=2147483647", auth("admin")))
                        .get("result"));
        for (String suffix : List.of("", "/file")) {
            assertEquals(401, get(path + suffix, null).statusCode());
            assertEquals(400, get(path + suffix + "?unsupported=1", auth("admin")).statusCode());
        }
        assertEquals(404, get("/api/now/attachment/missing/file", auth("admin")).statusCode());
        assertEquals(
                404, get("/api/now/attachment/missing/unsupported", auth("admin")).statusCode());
        assertEquals(
                204,
                json(
                                "DELETE",
                                "/api/now/table/incident/" + incident.get("sys_id"),
                                map(),
                                auth("admin"))
                        .statusCode());
        assertEquals(404, get(path, auth("admin")).statusCode());
        assertEquals(404, get("/attachment/" + file.get("sys_id"), auth("admin")).statusCode());
    }

    @Test
    void accountPaginationAndAllRecordStatistics() throws Exception {
        try (var db = store.open()) {
            String hash =
                    (String)
                            db.one("SELECT password FROM os_user WHERE name=?", "admin")
                                    .get("password");
            for (int i = 0; i < 30; i++)
                db.update(
                        "INSERT INTO os_user(name,password,role) VALUES(?,?,?)",
                        String.format(Locale.ROOT, "evaluator%02d", i),
                        hash,
                        "reader");
            String sql =
                    "INSERT INTO"
                        + " os_record(sys_id,\"table\",data,original,created,updated,creator,updater,revision)"
                        + " VALUES(?,? ,"
                            + db.jsonParameter()
                            + ","
                            + db.jsonParameter()
                            + ",?,?,?,?,?)";
            try (var batch = db.statement(sql)) {
                for (int i = 0; i < 10001; i++) {
                    Object[] values = {
                        id(),
                        "incident",
                        JSON.writeValueAsString(map("priority", "1")),
                        "{}",
                        now(),
                        now(),
                        "admin",
                        "admin",
                        0
                    };
                    for (int j = 0; j < values.length; j++) batch.setObject(j + 1, values[j]);
                    batch.addBatch();
                }
                batch.executeBatch();
            }
            db.connection.commit();
        }
        String first = html(get("/admin/accounts", auth("admin"))),
                second = html(get("/admin/accounts?page=2", auth("admin")));
        assertTrue(first.contains("evaluator23") && !first.contains("evaluator24"));
        assertTrue(
                second.contains("evaluator24")
                        && second.contains("evaluator29")
                        && !second.contains("evaluator23"));
        assertTrue(first.contains("Page 1 of 2"));
        assertTrue(second.contains("Previous") && !second.contains("Next →"));
        var added =
                form(
                        "/admin/accounts?page=2",
                        map("name", "zznew", "password", PASSWORD, "role", "reader"),
                        auth("admin"));
        assertEquals(302, added.statusCode());
        assertTrue(added.headers().firstValue("Location").orElseThrow().endsWith("?page=2"));
        assertEquals(
                10000,
                objects(parsed(get("/api/now/table/incident", auth("admin"))).get("result"))
                        .size());
        assertEquals(
                map("result", map("stats", map("count", "10001"))),
                parsed(get("/api/now/stats/incident?sysparm_count=true", auth("admin"))));
    }

    String binaryPath(Object id, String name) {
        return "/api/now/attachment/file?table_name=incident&table_sys_id="
                + id
                + "&file_name="
                + java.net.URLEncoder.encode(name, StandardCharsets.UTF_8);
    }

    @Test
    void binaryAttachmentUploadPersistsBytesAndMetadata() throws Exception {
        account("agent", "agent");
        account("reader", "reader");
        String id = add("incident").get("sys_id").toString();
        byte[] bytes = {0, 1, (byte) 255, 3};
        String path = binaryPath(id, "../résumé.bin");
        var response = request("POST", path, bytes, "application/x-evidence", auth("agent"));
        assertEquals(201, response.statusCode(), html(response));
        var file = object(parsed(response).get("result"));
        assertEquals("resume.bin", file.get("file_name"));
        assertEquals("4", file.get("size_bytes"));
        assertEquals("application/x-evidence", file.get("content_type"));
        String location = response.headers().firstValue("Location").orElseThrow();
        assertEquals(base() + "/api/now/attachment/" + file.get("sys_id"), location);
        assertEquals(
                file, parsed(get(URI.create(location).getPath(), auth("reader"))).get("result"));
        for (String download :
                List.of(location + "/file", base() + "/attachment/" + file.get("sys_id"))) {
            var body = get(URI.create(download).getPath(), auth("reader"));
            assertArrayEquals(bytes, body.body());
            assertEquals(
                    "application/x-evidence",
                    body.headers().firstValue("Content-Type").orElseThrow());
        }
        assertEquals(
                201,
                request(
                                "POST",
                                binaryPath(id, "empty.bin"),
                                new byte[0],
                                "application/octet-stream",
                                auth("admin"))
                        .statusCode());
        assertEquals(
                201,
                request(
                                "POST",
                                binaryPath(id, "limit.bin"),
                                new byte[5 * 1024 * 1024],
                                "application/octet-stream",
                                auth("admin"))
                        .statusCode());
        assertEquals(
                413,
                request(
                                "POST",
                                binaryPath(id, "too-large.bin"),
                                new byte[5 * 1024 * 1024 + 1],
                                "application/octet-stream",
                                auth("admin"))
                        .statusCode());
        assertEquals(
                403,
                request("POST", path, bytes, "application/octet-stream", auth("reader"))
                        .statusCode());
        assertEquals(
                401, request("POST", path, bytes, "application/octet-stream", null).statusCode());
        login("admin");
        assertEquals(
                403, request("POST", path, bytes, "application/octet-stream", null).statusCode());
        try (var db = store.open()) {
            assertEquals(3, db.count("SELECT COUNT(*) FROM os_attachment"));
            assertEquals(3, db.count("SELECT COUNT(*) FROM os_event WHERE action='attachment'"));
            assertEquals(
                    "0",
                    Service.serialize(Service.record(db, "incident", id)).get("sys_mod_count"));
        }
    }

    @Test
    void binaryAttachmentValidationIsAtomic() throws Exception {
        String id = add("incident").get("sys_id").toString();
        String path = binaryPath(id, "evidence.txt");
        for (String bad :
                List.of(
                        "/api/now/attachment/file",
                        path + "&extra=x",
                        path + "&file_name=duplicate",
                        path.replace("file_name=evidence.txt", "file_name="),
                        path.replace("table_name=incident", "table_name="),
                        path.replace("table_sys_id=" + id, "table_sys_id=")))
            assertEquals(
                    400,
                    request("POST", bad, new byte[] {1}, "text/plain", auth("admin")).statusCode(),
                    bad);
        for (String bad :
                List.of(
                        path.replace("table_name=incident", "table_name=missing"),
                        binaryPath("missing", "evidence.txt"),
                        path.replace("table_name=incident", "table_name=problem")))
            assertEquals(
                    404,
                    request("POST", bad, new byte[] {1}, "text/plain", auth("admin")).statusCode());
        for (String type : Arrays.asList(null, "bad", "*/*", "text/" + "a".repeat(256)))
            assertEquals(
                    415,
                    request("POST", path, new byte[] {1}, type, auth("admin")).statusCode(),
                    type);
        assertEquals(
                415, upload(path, "multipart.txt", new byte[] {1}, auth("admin")).statusCode());
        assertEquals(405, get(path, auth("admin")).statusCode());
        try (var db = store.open()) {
            assertEquals(0, db.count("SELECT COUNT(*) FROM os_attachment"));
            assertEquals(0, db.count("SELECT COUNT(*) FROM os_event WHERE action='attachment'"));
        }
    }

    @Test
    void attachmentSchemaUpgradePreservesLegacyBytes() throws Exception {
        String parent = add("incident").get("sys_id").toString();
        try (var db = store.open()) {
            db.update("ALTER TABLE os_attachment DROP COLUMN content_type");
            db.update(
                    "INSERT INTO os_attachment(sys_id,record_id,name,content) VALUES(?,?,?,?)",
                    "legacy-attachment",
                    parent,
                    "legacy.txt",
                    new byte[] {1, 2, 3});
            db.connection.commit();
        }
        new Store(environment);
        new Store(environment);
        var response = get("/api/now/attachment/legacy-attachment/file", auth("admin"));
        assertEquals(200, response.statusCode());
        assertArrayEquals(new byte[] {1, 2, 3}, response.body());
        assertEquals(
                "application/octet-stream",
                response.headers().firstValue("Content-Type").orElseThrow());
    }

    @Test
    void binaryUploadKeepsFormEncodedBytesAndAcceptsSessionCsrfHeader() throws Exception {
        String id = add("incident").get("sys_id").toString();
        byte[] bytes = "literal=value&second=two+words".getBytes(StandardCharsets.UTF_8);
        String token = login("admin");
        var response =
                client.send(
                        HttpRequest.newBuilder(URI.create(base() + binaryPath(id, "encoded.txt")))
                                .header("Content-Type", "application/x-www-form-urlencoded")
                                .header("X-CSRF-Token", token)
                                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                                .build(),
                        HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(201, response.statusCode(), html(response));
        var file = object(parsed(response).get("result"));
        assertArrayEquals(
                bytes, get("/api/now/attachment/" + file.get("sys_id") + "/file", null).body());
    }

    @Test
    void attachmentDeletionIsAuthorizedAuditedAndIsolated() throws Exception {
        account("agent", "agent");
        account("reader", "reader");
        String id = add("incident").get("sys_id").toString();
        var uploaded =
                request(
                        "POST",
                        binaryPath(id, "remove.txt"),
                        new byte[] {1, 2},
                        "text/plain",
                        auth("admin"));
        String file = object(parsed(uploaded).get("result")).get("sys_id").toString(),
                path = "/api/now/attachment/" + file;
        request("POST", binaryPath(id, "keep.txt"), new byte[] {3}, "text/plain", auth("admin"));
        assertEquals(401, json("DELETE", path, map(), null).statusCode());
        for (String role : List.of("agent", "reader"))
            assertEquals(403, json("DELETE", path, map(), auth(role)).statusCode());
        assertEquals(405, json("DELETE", path + "/file", map(), auth("admin")).statusCode());
        assertEquals(405, json("DELETE", "/api/now/attachment", map(), auth("admin")).statusCode());
        assertEquals(400, json("DELETE", path + "?extra=1", map(), auth("admin")).statusCode());
        String token = login("admin");
        assertEquals(403, json("DELETE", path, map(), null).statusCode());
        var deleted =
                client.send(
                        HttpRequest.newBuilder(URI.create(base() + path))
                                .header("X-CSRF-Token", token)
                                .DELETE()
                                .build(),
                        HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(204, deleted.statusCode(), html(deleted));
        assertEquals(0, deleted.body().length);
        assertEquals(404, json("DELETE", path, map(), auth("admin")).statusCode());
        assertEquals(404, get(path, auth("admin")).statusCode());
        assertEquals(404, get(path + "/file", auth("admin")).statusCode());
        assertEquals(404, get("/attachment/" + file, auth("admin")).statusCode());
        assertEquals(
                1, objects(parsed(get("/api/now/attachment", auth("admin"))).get("result")).size());
        try (var db = store.open()) {
            assertEquals(
                    "0",
                    Service.serialize(Service.record(db, "incident", id)).get("sys_mod_count"));
            var event = db.one("SELECT * FROM os_event WHERE action='attachment_deleted'");
            assertNotNull(event);
            assertEquals("admin", event.get("actor"));
            assertEquals(file, object(event.get("detail")).get("sys_id"));
            assertEquals("remove.txt", object(event.get("detail")).get("name"));
            assertEquals(id, event.get("record_id"));
            assertEquals(1, db.count("SELECT COUNT(*) FROM os_attachment"));
        }
    }

    byte[] multipart(
            List<String> fields, List<String> names, String filename, byte[] bytes, String type)
            throws Exception {
        var body = new ByteArrayOutputStream();
        for (String field : fields) {
            var pair = field.split("=", 2);
            body.write(
                    ("--OpenServiceTestBoundary\r\nContent-Disposition: form-data; name=\""
                                    + pair[0]
                                    + "\"\r\n\r\n"
                                    + pair[1]
                                    + "\r\n")
                            .getBytes(StandardCharsets.UTF_8));
        }
        for (String name : names) {
            body.write(
                    ("--OpenServiceTestBoundary\r\nContent-Disposition: form-data; name=\""
                                    + name
                                    + "\"; filename=\""
                                    + filename
                                    + "\"\r\n"
                                    + (type == null ? "" : "Content-Type: " + type + "\r\n")
                                    + "\r\n")
                            .getBytes(StandardCharsets.UTF_8));
            body.write(bytes);
            body.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        body.write("--OpenServiceTestBoundary--\r\n".getBytes(StandardCharsets.UTF_8));
        return body.toByteArray();
    }

    HttpResponse<byte[]> multipartUpload(
            String suffix,
            List<String> fields,
            List<String> names,
            String filename,
            byte[] bytes,
            String type,
            String auth)
            throws Exception {
        return request(
                "POST",
                "/api/now/attachment/upload" + suffix,
                multipart(fields, names, filename, bytes, type),
                "multipart/form-data; boundary=OpenServiceTestBoundary",
                auth);
    }

    @Test
    void multipartAttachmentUploadUsesSharedPersistence() throws Exception {
        account("agent", "agent");
        account("reader", "reader");
        String id = add("incident").get("sys_id").toString();
        var fields = List.of("table_name=incident", "table_sys_id=" + id);
        byte[] bytes = {0, 9, (byte) 255};
        var response =
                multipartUpload(
                        "",
                        fields,
                        List.of("uploadFile"),
                        "evidence.txt",
                        bytes,
                        "text/plain; charset=utf-8",
                        auth("agent"));
        assertEquals(201, response.statusCode(), html(response));
        var file = object(parsed(response).get("result"));
        assertEquals("evidence.txt", file.get("file_name"));
        assertEquals("text/plain; charset=utf-8", file.get("content_type"));
        assertEquals("3", file.get("size_bytes"));
        assertEquals(
                base() + "/api/now/attachment/" + file.get("sys_id"),
                response.headers().firstValue("Location").orElseThrow());
        assertArrayEquals(
                bytes,
                get("/api/now/attachment/" + file.get("sys_id") + "/file", auth("reader")).body());
        assertEquals(
                201,
                multipartUpload(
                                "",
                                fields,
                                List.of("uploadFile"),
                                "empty",
                                new byte[0],
                                "application/octet-stream",
                                auth("admin"))
                        .statusCode());
        assertEquals(
                201,
                multipartUpload(
                                "",
                                fields,
                                List.of("uploadFile"),
                                "limit",
                                new byte[5 * 1024 * 1024],
                                "application/octet-stream",
                                auth("admin"))
                        .statusCode());
        assertEquals(
                413,
                multipartUpload(
                                "",
                                fields,
                                List.of("uploadFile"),
                                "large",
                                new byte[5 * 1024 * 1024 + 1],
                                "application/octet-stream",
                                auth("admin"))
                        .statusCode());
        assertEquals(
                403,
                multipartUpload(
                                "",
                                fields,
                                List.of("uploadFile"),
                                "denied",
                                bytes,
                                "text/plain",
                                auth("reader"))
                        .statusCode());
        assertEquals(
                401,
                multipartUpload(
                                "",
                                fields,
                                List.of("uploadFile"),
                                "denied",
                                bytes,
                                "text/plain",
                                null)
                        .statusCode());
        String token = login("admin");
        assertEquals(
                403,
                multipartUpload(
                                "",
                                fields,
                                List.of("uploadFile"),
                                "denied",
                                bytes,
                                "text/plain",
                                null)
                        .statusCode());
        var session =
                client.send(
                        HttpRequest.newBuilder(URI.create(base() + "/api/now/attachment/upload"))
                                .header("X-CSRF-Token", token)
                                .header(
                                        "Content-Type",
                                        "multipart/form-data; boundary=OpenServiceTestBoundary")
                                .POST(
                                        HttpRequest.BodyPublishers.ofByteArray(
                                                multipart(
                                                        fields,
                                                        List.of("uploadFile"),
                                                        "session",
                                                        bytes,
                                                        "text/plain")))
                                .build(),
                        HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(201, session.statusCode(), html(session));
        try (var db = store.open()) {
            assertEquals(4, db.count("SELECT COUNT(*) FROM os_attachment"));
            assertEquals(4, db.count("SELECT COUNT(*) FROM os_event WHERE action='attachment'"));
        }
    }

    @Test
    void multipartAttachmentRejectsAmbiguousOrMissingParts() throws Exception {
        String id = add("incident").get("sys_id").toString();
        var fields = List.of("table_name=incident", "table_sys_id=" + id);
        byte[] bytes = {1};
        assertEquals(
                415, json("POST", "/api/now/attachment/upload", map(), auth("admin")).statusCode());
        assertEquals(405, get("/api/now/attachment/upload", auth("admin")).statusCode());
        for (var names :
                List.of(
                        List.<String>of(),
                        List.of("wrong"),
                        List.of("uploadFile", "uploadFile"),
                        List.of("uploadFile", "extra")))
            assertEquals(
                    400,
                    multipartUpload(
                                    "",
                                    fields,
                                    names,
                                    "file.txt",
                                    bytes,
                                    "text/plain",
                                    auth("admin"))
                            .statusCode());
        for (var bad :
                List.of(
                        List.<String>of(),
                        List.of("table_name=incident"),
                        List.of("table_name=", "table_sys_id=" + id),
                        List.of("table_name=incident", "table_sys_id=" + id, "extra=value"),
                        List.of("table_name=incident", "table_sys_id=" + id, "table_name=problem")))
            assertEquals(
                    400,
                    multipartUpload(
                                    "",
                                    bad,
                                    List.of("uploadFile"),
                                    "file.txt",
                                    bytes,
                                    "text/plain",
                                    auth("admin"))
                            .statusCode());
        assertEquals(
                400,
                multipartUpload(
                                "?extra=1",
                                fields,
                                List.of("uploadFile"),
                                "file.txt",
                                bytes,
                                "text/plain",
                                auth("admin"))
                        .statusCode());
        assertEquals(
                400,
                multipartUpload(
                                "",
                                fields,
                                List.of("uploadFile"),
                                "",
                                bytes,
                                "text/plain",
                                auth("admin"))
                        .statusCode());
        assertEquals(
                415,
                multipartUpload(
                                "",
                                fields,
                                List.of("uploadFile"),
                                "file.txt",
                                bytes,
                                null,
                                auth("admin"))
                        .statusCode());
        assertEquals(
                404,
                multipartUpload(
                                "",
                                List.of("table_name=problem", "table_sys_id=" + id),
                                List.of("uploadFile"),
                                "file.txt",
                                bytes,
                                "text/plain",
                                auth("admin"))
                        .statusCode());
        try (var db = store.open()) {
            assertEquals(0, db.count("SELECT COUNT(*) FROM os_attachment"));
            assertEquals(0, db.count("SELECT COUNT(*) FROM os_event WHERE action='attachment'"));
        }
    }

    @Test
    void versionedTableRoutesShareRecordsPermissionsAndRevisions() throws Exception {
        account("agent", "agent");
        account("reader", "reader");
        for (String version : List.of("v1", "v2")) {
            String path = "/api/now/" + version + "/table/incident";
            assertEquals(version.equals("v1") ? 404 : 200, get(path, auth("admin")).statusCode());

            assertEquals(401, get(path, null).statusCode());
            assertEquals(
                    403,
                    json("POST", path, map("short_description", "Denied"), auth("reader"))
                            .statusCode());
            var created =
                    json(
                            "POST",
                            path,
                            map("short_description", "Versioned " + version),
                            auth("agent"));
            assertEquals(201, created.statusCode(), html(created));
            var record = object(parsed(created).get("result"));
            String resource = path + "/" + record.get("sys_id");
            assertEquals(base() + resource, created.headers().firstValue("Location").orElseThrow());
            assertEquals(
                    record,
                    parsed(get("/api/now/table/incident/" + record.get("sys_id"), auth("reader")))
                            .get("result"));
            var read = get(resource + "?sysparm_fields=sys_id,short_description", auth("reader"));
            assertEquals(200, read.statusCode());
            assertEquals("\"0\"", read.headers().firstValue("ETag").orElseThrow());
            assertEquals(
                    Set.of("sys_id", "short_description"),
                    object(parsed(read).get("result")).keySet());
            var list = get(path + "?sysparm_limit=1", auth("reader"));
            assertEquals(200, list.statusCode());
            assertTrue(list.headers().firstValue("Link").orElseThrow().contains(path + "?"));
            assertEquals(
                    200,
                    json("PATCH", resource, map("short_description", "Patched"), auth("agent"))
                            .statusCode());
            var stale =
                    client.send(
                            HttpRequest.newBuilder(URI.create(base() + resource))
                                    .header("Authorization", auth("agent"))
                                    .header("Content-Type", "application/json")
                                    .header("If-Match", "\"0\"")
                                    .method(
                                            "PATCH",
                                            HttpRequest.BodyPublishers.ofString(
                                                    "{\"short_description\":\"Stale\"}"))
                                    .build(),
                            HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(409, stale.statusCode());
            assertEquals(
                    200,
                    json("PUT", resource, map("short_description", "Replaced"), auth("agent"))
                            .statusCode());
            assertEquals(403, json("DELETE", resource, map(), auth("agent")).statusCode());
            assertEquals(400, get(path + "?unsupported=1", auth("admin")).statusCode());
            assertEquals(
                    version.equals("v1") ? 404 : 200,
                    get(path + "?sysparm_query=sys_id%3Dmissing", auth("admin")).statusCode());
            assertEquals(
                    version.equals("v1") ? 404 : 200,
                    get(path + "?sysparm_offset=2147483647", auth("admin")).statusCode());

            assertEquals(
                    400,
                    get(path + "?sysparm_limit=1&sysparm_limit=2", auth("admin")).statusCode());
            assertEquals(204, json("DELETE", resource, map(), auth("admin")).statusCode());
            assertEquals(404, get(resource, auth("admin")).statusCode());
        }
        for (String version : List.of("v0", "v3", "v99", "V1"))
            assertEquals(
                    404,
                    get("/api/now/" + version + "/table/incident", auth("admin")).statusCode());
    }
}
