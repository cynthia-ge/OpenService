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
            for (String table :
                    List.of(
                            "os_attachment",
                            "os_event",
                            "os_record",
                            "os_security_event",
                            "os_invitation",
                            "os_recovery",
                            "os_record_access",
                            "os_group_member",
                            "os_auth_attempt",
                            "os_auth_event")) db.update("DELETE FROM " + table);
            db.update("DELETE FROM os_user WHERE name<>?", "admin");
            db.connection.commit();
        }
    }

    @Test
    void typedAnalyticsUsesAllAuthorizedRows() throws Exception {
        var acceptance = new ConfigurationAcceptance(this);
        try {
            acceptance.analytics();
        } finally {
            acceptance.cleanup();
        }
    }

    @Test
    void reusableServiceOperationsExampleAndQueuedWriteAuthorization() throws Exception {
        var acceptance = new ConfigurationAcceptance(this);
        try {
            acceptance.exampleAndAuthorization();
        } finally {
            acceptance.cleanup();
        }
    }

    @Test
    void reusableConfigurationPackagesAndTypedRecords() throws Exception {
        var acceptance = new ConfigurationAcceptance(this);
        try {
            acceptance.packagesAndRecords();
        } finally {
            acceptance.cleanup();
        }
    }

    @Test
    void configuredRelationshipsAssignmentsAndStatePolicies() throws Exception {
        var acceptance = new ConfigurationAcceptance(this);
        try {
            acceptance.relationshipsAndWorkflows();
        } finally {
            acceptance.cleanup();
        }
    }

    @Test
    void configurationPermissionsPreviewAndConflicts() throws Exception {
        var acceptance = new ConfigurationAcceptance(this);
        try {
            acceptance.permissionsAndConflicts();
        } finally {
            acceptance.cleanup();
        }
    }

    @Test
    void bulkMembershipRemovalCountsOnlyRowsItActuallyDeletes() throws Exception {
        account("ConcurrentMember", "reader");
        String user =
                object(parsed(get("/api/openservice/me", auth("ConcurrentMember"))).get("result"))
                        .get("user_id")
                        .toString();
        String group = add("sys_user_group").get("sys_id").toString();
        assertEquals(
                200,
                json(
                                "PUT",
                                "/api/openservice/groups/" + group + "/members",
                                map("user_id", user),
                                auth("admin"))
                        .statusCode());
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.concurrent.Future<HttpResponse<byte[]>> pending;
            try (var deletion = store.open()) {
                // This is the membership cleanup performed by group-record deletion.
                assertEquals(
                        1, deletion.update("DELETE FROM os_group_member WHERE group_id=?", group));
                pending =
                        executor.submit(() ->
                                json(
                                        "POST",
                                        "/api/openservice/users/" + user + "/memberships/remove",
                                        map(),
                                        auth("admin")));
                try {
                    if (deletion.postgres) {
                        boolean blocked = false;
                        for (int i = 0; i < 500; i++) {
                            if (deletion.count(
                                            "SELECT COUNT(*) FROM pg_stat_activity WHERE query LIKE"
                                                + " 'DELETE FROM os_group_member WHERE user_id=%'"
                                                + " AND wait_event_type='Lock'")
                                    > 0) {
                                blocked = true;
                                break;
                            }
                            deletion.all("SELECT pg_stat_clear_snapshot()");
                            Thread.sleep(10);
                        }
                        assertTrue(
                                blocked,
                                "Bulk deletion must overlap the uncommitted group cleanup");
                    }
                } finally {
                    deletion.connection.commit();
                }
            }
            var response = pending.get(15, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(200, response.statusCode());
            assertEquals(0, object(parsed(response).get("result")).get("removed_count"));
        }
        try (var db = store.open()) {
            assertEquals(
                    0,
                    db.count(
                            "SELECT COUNT(*) FROM os_security_event WHERE"
                                    + " action='account.memberships_removed'"));
        }
    }

    @Test
    void privateHandoverRollsBackAndSerializesConcurrentRequests() throws Exception {
        account("ConcurrentOld", "reader");
        account("ConcurrentNew", "reader");
        String old, next, actor, record;
        try (var db = store.open()) {
            old = RecordAccess.owner(db, "ConcurrentOld");
            next = RecordAccess.owner(db, "ConcurrentNew");
            actor = RecordAccess.owner(db, "admin");
            var item =
                    Service.create(
                            db,
                            "incident",
                            map("short_description", "Concurrent private record"),
                            "admin");
            record = item.get("sys_id").toString();
            RecordAccess.change(db, item, actor, map("visibility", "private", "owner_id", old));
            db.connection.commit();
        }
        try (var db = store.open()) {
            PrivateRecords.transfer(db, actor, old, map("new_owner_id", next));
        }
        try (var db = store.open()) {
            assertEquals(old, RecordAccess.status(db, record).get("owner_id"));
            assertEquals(
                    0,
                    db.count(
                            "SELECT COUNT(*) FROM os_security_event WHERE"
                                    + " action='account.private_records_transferred'"));
        }
        String api = "/api/openservice/users/" + old + "/private-records/transfer";
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var a =
                    executor.submit(
                            () -> json("POST", api, map("new_owner_id", next), auth("admin")));
            var b =
                    executor.submit(
                            () -> json("POST", api, map("new_owner_id", next), auth("admin")));
            var first = a.get();
            var second = b.get();
            assertEquals(200, first.statusCode());
            assertEquals(200, second.statusCode());
            assertEquals(
                    1,
                    ((Number) object(parsed(first).get("result")).get("transferred_count"))
                                    .intValue()
                            + ((Number)
                                            object(parsed(second).get("result"))
                                                    .get("transferred_count"))
                                    .intValue());
        }
        try (var db = store.open()) {
            assertEquals(
                    1,
                    db.count(
                            "SELECT COUNT(*) FROM os_security_event WHERE"
                                    + " action='account.private_records_transferred'"));
            assertEquals(next, RecordAccess.status(db, record).get("owner_id"));
        }
    }

    @Test
    void privateRecordHandoverPreservesHistoryAndEnforcesTheNewOwner() throws Exception {
        account("OldOwner", "reader");
        account("NewOwner", "reader");
        account("OtherOwner", "agent");
        String old =
                object(parsed(get("/api/openservice/me", auth("OldOwner"))).get("result"))
                        .get("user_id")
                        .toString();
        String next =
                object(parsed(get("/api/openservice/me", auth("NewOwner"))).get("result"))
                        .get("user_id")
                        .toString();
        String other =
                object(parsed(get("/api/openservice/me", auth("OtherOwner"))).get("result"))
                        .get("user_id")
                        .toString();
        String api = "/api/openservice/users/" + old + "/private-records/transfer";
        assertEquals(200, json("POST", api, map("new_owner_id", next), auth("admin")).statusCode());
        String first = add("incident").get("sys_id").toString(),
                second = add("problem").get("sys_id").toString(),
                deleted = add("incident").get("sys_id").toString();
        String unrelated = add("incident").get("sys_id").toString(),
                workspace = add("incident").get("sys_id").toString();
        String group = add("sys_user_group").get("sys_id").toString(),
                groupRecord = add("incident").get("sys_id").toString();
        for (String id : List.of(first, second, deleted, unrelated)) {
            String table = id.equals(second) ? "problem" : "incident";
            assertEquals(
                    200,
                    json(
                                    "PATCH",
                                    "/api/openservice/access/" + table + "/" + id,
                                    map(
                                            "visibility",
                                            "private",
                                            "owner_id",
                                            id.equals(unrelated) ? other : old),
                                    auth("admin"))
                            .statusCode());
        }
        assertEquals(
                200,
                json(
                                "PATCH",
                                "/api/openservice/access/incident/" + groupRecord,
                                map("visibility", "group", "group_id", group),
                                auth("admin"))
                        .statusCode());
        assertEquals(
                204,
                json("DELETE", "/api/now/table/incident/" + deleted, map(), auth("admin"))
                        .statusCode());
        assertEquals(
                302,
                upload(
                                "/record/incident/" + first + "/attachment",
                                "handover.bin",
                                new byte[] {8, 7, 6},
                                auth("admin"))
                        .statusCode());
        String file =
                objects(parsed(get("/api/now/attachment", auth("admin"))).get("result"))
                        .getFirst()
                        .get("sys_id")
                        .toString();
        assertEquals(401, json("POST", api, map("new_owner_id", next), null).statusCode());
        for (String actor : List.of("OldOwner", "OtherOwner"))
            assertEquals(
                    403, json("POST", api, map("new_owner_id", next), auth(actor)).statusCode());
        assertEquals(405, get(api, auth("admin")).statusCode());
        assertEquals(
                400,
                json("POST", api + "?x=1", map("new_owner_id", next), auth("admin")).statusCode());
        assertEquals(
                404,
                json(
                                "POST",
                                api.replace(old, "0".repeat(32)),
                                map("new_owner_id", next),
                                auth("admin"))
                        .statusCode());
        for (Object bad :
                List.of(
                        map(),
                        map("new_owner_id", 3),
                        map("new_owner_id", ""),
                        map("new_owner_id", "\u0000"),
                        map("new_owner_id", "0".repeat(32)),
                        map("new_owner_id", old),
                        map("new_owner_id", next, "extra", true)))
            assertEquals(400, json("POST", api, bad, auth("admin")).statusCode());
        assertEquals(
                200,
                json(
                                "PATCH",
                                "/api/openservice/users/" + next + "/status",
                                map("active", false),
                                auth("admin"))
                        .statusCode());
        assertEquals(400, json("POST", api, map("new_owner_id", next), auth("admin")).statusCode());
        assertEquals(
                200,
                json(
                                "PATCH",
                                "/api/openservice/users/" + next + "/status",
                                map("active", true),
                                auth("admin"))
                        .statusCode());
        // The handover succeeds even after the departing account has been deactivated.
        assertEquals(
                200,
                json(
                                "PATCH",
                                "/api/openservice/users/" + old + "/status",
                                map("active", false),
                                auth("admin"))
                        .statusCode());
        var before = parsed(get("/api/now/table/incident/" + first, auth("admin"))).get("result");
        var result =
                object(
                        parsed(json("POST", api, map("new_owner_id", next), auth("admin")))
                                .get("result"));
        assertEquals(2, result.get("transferred_count"));
        assertEquals(Set.of(first, second), new HashSet<>((List<String>) result.get("record_ids")));
        assertEquals(next, result.get("new_owner_id"));
        assertEquals(
                before,
                parsed(get("/api/now/table/incident/" + first, auth("admin"))).get("result"));
        assertEquals(
                200,
                json(
                                "PATCH",
                                "/api/openservice/users/" + old + "/status",
                                map("active", true),
                                auth("admin"))
                        .statusCode());
        for (String id : List.of(first, second)) {
            String table = id.equals(second) ? "problem" : "incident";
            assertEquals(
                    404, get("/api/now/table/" + table + "/" + id, auth("OldOwner")).statusCode());
            assertEquals(
                    200, get("/api/now/table/" + table + "/" + id, auth("NewOwner")).statusCode());
        }
        assertEquals(404, get("/attachment/" + file, auth("OldOwner")).statusCode());
        assertArrayEquals(
                new byte[] {8, 7, 6}, get("/attachment/" + file, auth("NewOwner")).body());
        assertEquals(
                200, get("/api/now/table/incident/" + unrelated, auth("OtherOwner")).statusCode());
        assertEquals(
                404, get("/api/now/table/incident/" + unrelated, auth("NewOwner")).statusCode());
        assertEquals(
                200, get("/api/now/table/incident/" + workspace, auth("OldOwner")).statusCode());
        assertEquals(
                404, get("/api/now/table/incident/" + groupRecord, auth("NewOwner")).statusCode());
        new Store(environment);
        assertEquals(
                0,
                object(
                                parsed(json("POST", api, map("new_owner_id", next), auth("admin")))
                                        .get("result"))
                        .get("transferred_count"));
        try (var db = store.open()) {
            assertEquals(old, RecordAccess.status(db, deleted).get("owner_id"));
            var events =
                    db.all(
                            "SELECT * FROM os_security_event WHERE"
                                    + " action='account.private_records_transferred'");
            assertEquals(1, events.size());
            assertEquals(old, events.getFirst().get("target_id"));
            assertEquals(RecordAccess.owner(db, "admin"), events.getFirst().get("actor_id"));
            assertEquals(result, events.getFirst().get("detail"));
        }
        String page = "/admin/accounts/" + next + "/private-records/transfer";
        String csrf = login("admin");
        assertEquals(403, form(page, map("new_owner", "OldOwner"), null).statusCode());
        for (String invalid : List.of("", "bad name", "\u0000", "MissingPerson"))
            assertEquals(
                    400, form(page, map("csrf", csrf, "new_owner", invalid), null).statusCode());
        assertEquals(
                302, form(page, map("csrf", csrf, "new_owner", "OldOwner"), null).statusCode());
        assertEquals(200, get("/api/now/table/incident/" + first, auth("OldOwner")).statusCode());
        assertEquals(404, get("/api/now/table/incident/" + first, auth("NewOwner")).statusCode());
    }

    @Test
    void accountMembershipRemovalIsAtomicAuditedAndEffectiveForExistingSessions() throws Exception {
        account("DepartingMember", "reader");
        account("RetainedMember", "agent");
        String user =
                object(parsed(get("/api/openservice/me", auth("DepartingMember"))).get("result"))
                        .get("user_id")
                        .toString();
        String other =
                object(parsed(get("/api/openservice/me", auth("RetainedMember"))).get("result"))
                        .get("user_id")
                        .toString();
        String api = "/api/openservice/users/" + user + "/memberships", remove = api + "/remove";
        String page = "/admin/accounts/" + user;
        assertEquals(200, get(api, auth("admin")).statusCode());
        assertEquals(List.of(), parsed(get(api, auth("admin"))).get("result"));
        var groups = new ArrayList<String>();
        for (int i = 0; i < 2; i++) {
            String group = add("sys_user_group").get("sys_id").toString();
            groups.add(group);
            for (String member : List.of(user, other))
                assertEquals(
                        200,
                        json(
                                        "PUT",
                                        "/api/openservice/groups/" + group + "/members",
                                        map("user_id", member),
                                        auth("admin"))
                                .statusCode());
        }
        String record = add("incident").get("sys_id").toString();
        assertEquals(
                200,
                json(
                                "PATCH",
                                "/api/openservice/access/incident/" + record,
                                map("visibility", "group", "group_id", groups.getFirst()),
                                auth("admin"))
                        .statusCode());
        var memberships = objects(parsed(get(api, auth("admin"))).get("result"));
        assertEquals(2, memberships.size());
        assertEquals(Set.of("group_id", "name"), memberships.getFirst().keySet());
        assertEquals("Synthetic evaluation", memberships.getFirst().get("name"));
        assertTrue(html(get(page, auth("admin"))).contains(groups.getFirst()));
        assertEquals(401, get(api, null).statusCode());
        for (String actor : List.of("DepartingMember", "RetainedMember")) {
            assertEquals(403, get(api, auth(actor)).statusCode());
            assertEquals(403, json("POST", remove, map(), auth(actor)).statusCode());
        }
        assertEquals(405, json("POST", api, map(), auth("admin")).statusCode());
        assertEquals(405, get(remove, auth("admin")).statusCode());
        assertEquals(400, get(api + "?x=1", auth("admin")).statusCode());
        assertEquals(400, json("POST", remove + "?x=1", map(), auth("admin")).statusCode());
        assertEquals(400, json("POST", remove, map("extra", true), auth("admin")).statusCode());
        assertEquals(404, get(api.replace(user, "0".repeat(32)), auth("admin")).statusCode());
        assertEquals(
                404,
                json("POST", remove.replace(user, "0".repeat(32)), map(), auth("admin"))
                        .statusCode());
        String adminId;
        try (var db = store.open()) {
            adminId = RecordAccess.owner(db, "admin");
            GroupMembers.removeForUser(db, adminId, user);
            // Closing without commit must restore memberships and its audit event together.
        }
        assertEquals(2, objects(parsed(get(api, auth("admin"))).get("result")).size());
        login("DepartingMember");
        assertEquals(200, get("/api/now/table/incident/" + record, null).statusCode());
        var result = object(parsed(json("POST", remove, map(), auth("admin"))).get("result"));
        assertEquals(2, result.get("removed_count"));
        assertEquals(new TreeSet<>(groups), new TreeSet<>((List<String>) result.get("group_ids")));
        assertEquals(404, get("/api/now/table/incident/" + record, null).statusCode());
        assertEquals(200, get("/api/openservice/me", null).statusCode());
        assertEquals(
                200, get("/api/now/table/incident/" + record, auth("RetainedMember")).statusCode());
        assertEquals(
                0,
                object(parsed(json("POST", remove, map(), auth("admin"))).get("result"))
                        .get("removed_count"));
        try (var db = store.open()) {
            var events =
                    db.all(
                            "SELECT * FROM os_security_event WHERE"
                                    + " action='account.memberships_removed'");
            assertEquals(1, events.size());
            assertEquals(adminId, events.getFirst().get("actor_id"));
            assertEquals(user, events.getFirst().get("target_id"));
            assertEquals(2, object(events.getFirst().get("detail")).get("removed_count"));
            assertEquals("reader", Service.account(db, user).get("role"));
            assertEquals(true, Service.account(db, user).get("active"));
        }
        // Clean up memberships on an inactive account through the real administrator form.
        assertEquals(
                200,
                json(
                                "PUT",
                                "/api/openservice/groups/" + groups.getFirst() + "/members",
                                map("user_id", user),
                                auth("admin"))
                        .statusCode());
        assertEquals(
                200,
                json(
                                "PATCH",
                                "/api/openservice/users/" + user + "/status",
                                map("active", false),
                                auth("admin"))
                        .statusCode());
        String csrf = login("admin");
        assertEquals(403, form(page + "/memberships/remove", map(), null).statusCode());
        assertEquals(302, form(page + "/memberships/remove", map("csrf", csrf), null).statusCode());
        assertEquals(
                200,
                json(
                                "PATCH",
                                "/api/openservice/users/" + user + "/status",
                                map("active", true),
                                auth("admin"))
                        .statusCode());
        new Store(environment);
        assertEquals(List.of(), parsed(get(api, null)).get("result"));
        assertEquals(
                404,
                get("/api/now/table/incident/" + record, auth("DepartingMember")).statusCode());
    }

    @Test
    void invitationAvailabilityReflectsIssuerRevocationAndUsernameConflicts() throws Exception {
        String api = "/api/openservice/invitations";
        account("AvailabilityIssuer", "admin");
        String issuer =
                object(parsed(get("/api/openservice/me", auth("AvailabilityIssuer"))).get("result"))
                        .get("user_id")
                        .toString();
        for (String operation : List.of("revision", "role", "active", "missing")) {
            if (operation.equals("role"))
                assertEquals(
                        200,
                        json(
                                        "PATCH",
                                        "/api/openservice/users/" + issuer + "/role",
                                        map("role", "admin"),
                                        auth("admin"))
                                .statusCode());
            var invite =
                    object(
                            parsed(
                                            json(
                                                    "POST",
                                                    api,
                                                    map(
                                                            "name",
                                                            "Availability" + operation,
                                                            "role",
                                                            "reader"),
                                                    auth("AvailabilityIssuer")))
                                    .get("result"));
            String id = invite.get("invite_id").toString();
            assertEquals("pending", invite.get("state"));
            if (operation.equals("revision"))
                assertEquals(
                        200,
                        json(
                                        "POST",
                                        "/api/openservice/me/sessions/revoke",
                                        map(),
                                        auth("AvailabilityIssuer"))
                                .statusCode());
            if (operation.equals("role"))
                assertEquals(
                        200,
                        json(
                                        "PATCH",
                                        "/api/openservice/users/" + issuer + "/role",
                                        map("role", "reader"),
                                        auth("admin"))
                                .statusCode());
            if (operation.equals("active"))
                assertEquals(
                        200,
                        json(
                                        "PATCH",
                                        "/api/openservice/users/" + issuer + "/status",
                                        map("active", false),
                                        auth("admin"))
                                .statusCode());
            if (operation.equals("missing")) {
                try (var db = store.open()) {
                    db.update("DELETE FROM os_user WHERE user_id=?", issuer);
                    db.connection.commit();
                }
            }
            var shown =
                    objects(parsed(get(api, auth("admin"))).get("result")).stream()
                            .filter(e -> e.get("invite_id").equals(id))
                            .findFirst()
                            .orElseThrow();
            assertEquals("invalidated", shown.get("state"));
            assertEquals(400, get(invite.get("accept_path").toString(), null).statusCode());
            new Store(environment);
            assertEquals(
                    "invalidated",
                    objects(parsed(get(api, auth("admin"))).get("result")).getFirst().get("state"));
            if (operation.equals("active"))
                assertEquals(
                        200,
                        json(
                                        "PATCH",
                                        "/api/openservice/users/" + issuer + "/status",
                                        map("active", true),
                                        auth("admin"))
                                .statusCode());
            if (operation.equals("role"))
                assertEquals(
                        200,
                        json(
                                        "PATCH",
                                        "/api/openservice/users/" + issuer + "/role",
                                        map("role", "admin"),
                                        auth("admin"))
                                .statusCode());
            if (operation.equals("revision")) {
                var replacement =
                        object(
                                parsed(
                                                json(
                                                        "POST",
                                                        api,
                                                        map(
                                                                "name",
                                                                "Availabilityrevision",
                                                                "role",
                                                                "reader"),
                                                        auth("admin")))
                                        .get("result"));
                assertEquals("pending", replacement.get("state"));
                assertEquals(
                        200, get(replacement.get("accept_path").toString(), null).statusCode());
            }
        }
        var collision =
                object(
                        parsed(
                                        json(
                                                "POST",
                                                api,
                                                map("name", "AlreadyCreated", "role", "reader"),
                                                auth("admin")))
                                .get("result"));
        account("AlreadyCreated", "reader");
        assertEquals(
                "unavailable",
                objects(parsed(get(api, auth("admin"))).get("result")).getFirst().get("state"));
        assertEquals(400, get(collision.get("accept_path").toString(), null).statusCode());
        String csrf = login("admin");
        String page = html(get("/admin/invitations", null));
        assertTrue(page.contains("invalidated"));
        assertTrue(page.contains("unavailable"));
        assertEquals(
                302,
                form(
                                "/admin/invitations/" + collision.get("invite_id") + "/revoke",
                                map("csrf", csrf),
                                null)
                        .statusCode());
        assertEquals(
                "revoked", objects(parsed(get(api, null)).get("result")).getFirst().get("state"));
        try (var db = store.open()) {
            db.update("UPDATE os_invitation SET expires_at=0 WHERE name='Availabilitymissing'");
            db.connection.commit();
        }
        assertEquals(
                "expired",
                objects(parsed(get(api, null)).get("result")).stream()
                        .filter(e -> e.get("name").equals("Availabilitymissing"))
                        .findFirst()
                        .orElseThrow()
                        .get("state"));
    }

    @Test
    void journalHistoryPagesAndDownloadsPreserveAccessAndStableBoundaries() throws Exception {
        account("JournalDenied", "reader");
        login("admin");
        for (String kind : List.of("security-events", "authentication-events")) {
            boolean authentication = kind.equals("authentication-events");
            String table = authentication ? "os_auth_event" : "os_security_event",
                    api = "/api/openservice/" + kind,
                    ui = "/admin/" + kind;
            List<String> expected;
            try (var db = store.open()) {
                db.update("DELETE FROM " + table);
                String admin =
                        db.one("SELECT user_id FROM os_user WHERE name='admin'")
                                .get("user_id")
                                .toString();
                for (int i = 0; i < 205; i++) {
                    if (authentication)
                        AuthenticationEvents.record(
                                db,
                                "admin",
                                Passwords.tokenHash("history" + i),
                                "browser",
                                "failed");
                    else
                        SecurityEvents.record(db, admin, admin, "account.fixture", map("index", i));
                }
                expected =
                        db.all("SELECT event_id FROM " + table + " ORDER BY sequence DESC").stream()
                                .map(e -> e.get("event_id").toString())
                                .toList();
                db.connection.commit();
            }
            var first = parsed(get(api, null));
            var firstRows = objects(first.get("result"));
            assertEquals(100, firstRows.size());
            assertEquals(
                    expected.subList(0, 100),
                    firstRows.stream().map(e -> e.get("event_id").toString()).toList());
            String before = first.get("next_before").toString();
            assertFalse(before.isEmpty());
            assertFalse(firstRows.getFirst().containsKey("sequence"));
            try (var db = store.open()) {
                String admin =
                        db.one("SELECT user_id FROM os_user WHERE name='admin'")
                                .get("user_id")
                                .toString();
                if (authentication)
                    AuthenticationEvents.record(
                            db, "admin", Passwords.tokenHash("newer"), "browser", "succeeded");
                else SecurityEvents.record(db, admin, admin, "account.newer", map());
                db.connection.commit();
            }
            var second = parsed(get(api + "?before=" + before, null));
            assertEquals(
                    expected.subList(100, 200),
                    objects(second.get("result")).stream()
                            .map(e -> e.get("event_id").toString())
                            .toList());
            var last = parsed(get(api + "?before=" + second.get("next_before"), null));
            assertEquals(
                    expected.subList(200, 205),
                    objects(last.get("result")).stream()
                            .map(e -> e.get("event_id").toString())
                            .toList());
            assertEquals("", last.get("next_before"));
            assertEquals(List.of(), parsed(get(api + "?before=1", null)).get("result"));
            var download = get(api + "?before=" + before + "&download=true", null);
            assertEquals(second, parsed(download));
            assertEquals(
                    "attachment; filename=\"" + kind + ".json\"",
                    download.headers().firstValue("Content-Disposition").orElseThrow());
            assertTrue(
                    download.headers()
                            .firstValue("Content-Type")
                            .orElseThrow()
                            .contains("application/json"));
            assertEquals(second, parsed(get(ui + "?before=" + before + "&download=true", null)));
            assertEquals(200, get(ui + "?before=" + before, null).statusCode());
            assertEquals(200, get(ui + "?download=false", null).statusCode());
            for (String bad :
                    List.of(
                            "?before=",
                            "?before=0",
                            "?before=-1",
                            "?before=9223372036854775808",
                            "?before=" + "9".repeat(20),
                            "?download=1",
                            "?x=1",
                            "?before=1&before=2")) {
                assertEquals(400, get(api + bad, null).statusCode(), bad);
                assertEquals(400, get(ui + bad, null).statusCode(), bad);
            }
            assertEquals(403, get(api + "?download=true", auth("JournalDenied")).statusCode());
            try (var db = store.open()) {
                db.update(
                        "DELETE FROM "
                                + table
                                + " WHERE sequence NOT IN (SELECT sequence FROM "
                                + table
                                + " ORDER BY sequence DESC LIMIT 100)");
                db.connection.commit();
            }
            var exact = parsed(get(api, null));
            assertEquals(100, objects(exact.get("result")).size());
            assertEquals("", exact.get("next_before"));
        }
    }

    @Test
    void accountDirectorySearchFiltersAndPaginationStayRestrictedAndConsistent() throws Exception {
        String api = "/api/openservice/users";
        assertEquals(401, get(api, null).statusCode());
        account("DirectoryAgent", "agent");
        account("DirectoryReader", "reader");
        for (String name : List.of("DirectoryAgent", "DirectoryReader")) {
            assertEquals(403, get(api + "?q=admin", auth(name)).statusCode());
            assertEquals(403, get("/admin/accounts?q=admin", auth(name)).statusCode());
        }
        try (var db = store.open()) {
            String password =
                    db.one("SELECT password FROM os_user WHERE name='admin'")
                            .get("password")
                            .toString();
            for (int i = 0; i < 30; i++)
                db.update(
                        "INSERT INTO os_user(name,password,role,user_id,active) VALUES(?,?,?,?,?)",
                        String.format(Locale.ROOT, "SearchPerson%02d", i),
                        password,
                        i == 1 ? "agent" : "reader",
                        id(),
                        i == 0 ? 0 : 1);
            db.connection.commit();
        }
        String query = "?q=searchperson&role=reader&status=active";
        var first = parsed(get(api + query, auth("admin")));
        assertEquals(28, ((Number) first.get("total")).intValue());
        assertEquals(2, ((Number) first.get("pages")).intValue());
        var rows = objects(first.get("result"));
        assertEquals(25, rows.size());
        assertEquals("SearchPerson02", rows.getFirst().get("name"));
        assertEquals(Set.of("user_id", "name", "role", "active"), rows.getFirst().keySet());
        var second = objects(parsed(get(api + query + "&page=2", auth("admin"))).get("result"));
        assertEquals(3, second.size());
        assertEquals("SearchPerson27", second.getFirst().get("name"));
        assertEquals(
                1,
                objects(
                                parsed(get(api + "?q=SearchPerson&status=inactive", auth("admin")))
                                        .get("result"))
                        .size());
        assertEquals(
                "SearchPerson01",
                objects(
                                parsed(get(api + "?q=SearchPerson&role=agent", auth("admin")))
                                        .get("result"))
                        .getFirst()
                        .get("name"));
        for (String suffix :
                List.of("?q=%25", "?q=_", "?q=%27%20OR%201%3D1", "?q=" + "x".repeat(80)))
            assertEquals(List.of(), parsed(get(api + suffix, auth("admin"))).get("result"));
        for (String suffix :
                List.of(
                        "?q=" + "x".repeat(81),
                        "?q=%00",
                        "?role=owner",
                        "?status=disabled",
                        "?page=0",
                        "?page=1000000",
                        "?q=SearchPerson&page=3",
                        "?x=1",
                        "?q=a&q=b")) {
            assertEquals(400, get(api + suffix, auth("admin")).statusCode(), suffix);
            assertEquals(400, get("/admin/accounts" + suffix, auth("admin")).statusCode(), suffix);
        }
        assertTrue(
                html(get("/admin/accounts?q=nomatchingperson", auth("admin")))
                        .contains("No accounts match these filters."));
        String page = html(get("/admin/accounts" + query, auth("admin")));
        assertTrue(page.contains("Page 1 of 2"));
        assertTrue(page.contains("q=searchperson&amp;role=reader&amp;status=active"));
        String csrf = login("admin");
        var created =
                form(
                        "/admin/accounts" + query,
                        map(
                                "name",
                                "SearchPersonAdded",
                                "role",
                                "reader",
                                "password",
                                PASSWORD,
                                "csrf",
                                csrf),
                        null);
        assertEquals(302, created.statusCode());
        assertTrue(
                created.headers().firstValue("Location").orElseThrow().contains("q=searchperson"));
        assertEquals(
                29, ((Number) parsed(get(api + query, auth("admin"))).get("total")).intValue());
        assertEquals(
                400,
                form(
                                "/admin/accounts" + query,
                                map(
                                        "name",
                                        "bad_name",
                                        "role",
                                        "reader",
                                        "password",
                                        PASSWORD,
                                        "csrf",
                                        csrf),
                                null)
                        .statusCode());
        assertEquals(405, json("PATCH", api, map(), auth("admin")).statusCode());
    }

    @Test
    void authenticationEventsAreDurableRestrictedSanitizedAndBounded() throws Exception {
        String api = "/api/openservice/authentication-events";
        assertEquals(401, get(api, null).statusCode());
        account("AuthJournal", "agent");
        account("JournalReader", "reader");
        login("admin");
        try (var db = store.open()) {
            db.update("DELETE FROM os_auth_event");
            db.connection.commit();
        }
        assertEquals(List.of(), parsed(get(api, null)).get("result"));
        assertTrue(
                html(get("/admin/authentication-events", null))
                        .contains("No authentication events recorded yet."));
        assertEquals(400, get(api + "?x=1", null).statusCode());
        assertEquals(405, json("POST", api, map(), auth("admin")).statusCode());
        for (String name : List.of("AuthJournal", "JournalReader")) {
            assertEquals(403, get(api, auth(name)).statusCode());
            assertEquals(403, get("/admin/authentication-events", auth(name)).statusCode());
        }
        String raw = "Unknown\n<script>injected</script>", secret = "do-not-log-this-password";
        for (String name : List.of("AuthJournal", raw, "Invalid" + (char) 0 + "Name")) {
            String bad =
                    "Basic "
                            + Base64.getEncoder()
                                    .encodeToString(
                                            (name + ":" + secret).getBytes(StandardCharsets.UTF_8));
            assertEquals(401, get("/api/openservice/me", bad).statusCode());
        }
        String hash = Passwords.tokenHash("AuthJournal");
        try (var db = store.open()) {
            db.update("UPDATE os_auth_attempt SET failures=5 WHERE identity_hash=?", hash);
            db.connection.commit();
        }
        assertEquals(429, get("/api/openservice/me", auth("AuthJournal")).statusCode());
        try (var db = store.open()) {
            db.update("DELETE FROM os_auth_attempt WHERE identity_hash=?", hash);
            db.connection.commit();
        }
        assertEquals(
                400,
                json("POST", "/api/now/table/incident", map(), auth("AuthJournal")).statusCode());
        login("AuthJournal");
        new Store(environment);
        var events = objects(parsed(get(api, auth("admin"))).get("result"));
        assertTrue(
                events.stream()
                        .allMatch(e ->
                                e.keySet()
                                        .equals(
                                                Set.of(
                                                        "event_id",
                                                        "occurred_at",
                                                        "user_id",
                                                        "user_name",
                                                        "identity_hash",
                                                        "mechanism",
                                                        "outcome"))));
        var own = events.stream().filter(e -> e.get("user_name").equals("AuthJournal")).toList();
        assertTrue(own.stream().anyMatch(e -> e.get("outcome").equals("failed")));
        assertTrue(own.stream().anyMatch(e -> e.get("outcome").equals("limited")));
        assertTrue(
                own.stream()
                        .anyMatch(e ->
                                e.get("outcome").equals("succeeded")
                                        && e.get("mechanism").equals("basic")));
        assertTrue(
                own.stream()
                        .anyMatch(e ->
                                e.get("outcome").equals("succeeded")
                                        && e.get("mechanism").equals("browser")));
        String unknownHash = Passwords.tokenHash(raw);
        var unknown =
                events.stream()
                        .filter(e -> e.get("identity_hash").equals(unknownHash))
                        .findFirst()
                        .orElseThrow();
        assertEquals("", unknown.get("user_name"));
        assertEquals("", unknown.get("user_id"));
        assertEquals(
                2,
                events.stream()
                        .filter(e ->
                                e.get("user_name").equals("") && e.get("outcome").equals("failed"))
                        .count());
        String serialized = JSON.writeValueAsString(events);
        for (String excluded : List.of(raw, secret, PASSWORD, "Basic ", "csrf", "token"))
            assertFalse(serialized.contains(excluded));
        try (var db = store.open()) {
            db.update("DELETE FROM os_auth_event");
            try (var insert =
                    db.statement(
                            "INSERT INTO"
                                + " os_auth_event(event_id,occurred_at,user_id,user_name,identity_hash,mechanism,outcome)"
                                + " VALUES(?,?,?,?,?,?,?)")) {
                for (int i = 0; i < 10000; i++) {
                    insert.setString(1, String.format(Locale.ROOT, "%032x", i));
                    insert.setString(2, now());
                    insert.setString(3, "");
                    insert.setString(4, "");
                    insert.setString(5, hash);
                    insert.setString(6, "basic");
                    insert.setString(7, "failed");
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            db.connection.commit();
        }
        login("admin");
        events = objects(parsed(get(api, null)).get("result"));
        assertEquals(100, events.size());
        assertEquals("succeeded", events.getFirst().get("outcome"));
        try (var db = store.open()) {
            assertEquals(10000, db.count("SELECT COUNT(*) FROM os_auth_event"));
            assertEquals(
                    0,
                    db.count(
                            "SELECT COUNT(*) FROM os_auth_event WHERE event_id=?", "0".repeat(32)));
        }
        assertEquals(200, get("/admin/authentication-events", null).statusCode());
    }

    @Test
    void authenticationThrottleIsDurableSharedBoundedAndAutomaticallyExpires() throws Exception {
        account("ThrottledUser", "agent");
        String csrf = login("ThrottledUser"),
                key = Passwords.tokenHash("ThrottledUser"),
                api = "/api/openservice/me";
        String bad =
                "Basic "
                        + Base64.getEncoder()
                                .encodeToString(
                                        "ThrottledUser:incorrect".getBytes(StandardCharsets.UTF_8));
        assertEquals(
                403,
                form("/login", map("username", "ThrottledUser", "password", "incorrect"), null)
                        .statusCode());
        for (int i = 0; i < 3; i++) assertEquals(401, get(api, bad).statusCode());
        for (int i = 0; i < 2; i++)
            assertEquals(
                    401,
                    form(
                                    "/login",
                                    map(
                                            "username",
                                            "ThrottledUser",
                                            "password",
                                            "incorrect",
                                            "csrf",
                                            csrf),
                                    null)
                            .statusCode());
        long expires;
        try (var db = store.open()) {
            var row = db.one("SELECT * FROM os_auth_attempt WHERE identity_hash=?", key);
            assertEquals(5, ((Number) row.get("failures")).intValue());
            expires = ((Number) row.get("expires_at")).longValue();
            assertFalse(row.toString().contains("ThrottledUser"));
        }
        new Store(environment);
        var denied = get(api, auth("ThrottledUser"));
        assertEquals(429, denied.statusCode());
        assertEquals("300", denied.headers().firstValue("Retry-After").orElseThrow());
        assertEquals(
                429,
                form(
                                "/login",
                                map(
                                        "username",
                                        "ThrottledUser",
                                        "password",
                                        PASSWORD,
                                        "csrf",
                                        csrf),
                                null)
                        .statusCode());
        assertEquals(200, get(api, null).statusCode());
        assertEquals(200, get(api, auth("admin")).statusCode());
        try (var db = store.open()) {
            assertEquals(
                    expires,
                    ((Number)
                                    db.one(
                                                    "SELECT expires_at FROM os_auth_attempt WHERE"
                                                            + " identity_hash=?",
                                                    key)
                                            .get("expires_at"))
                            .longValue());
            db.update("UPDATE os_auth_attempt SET expires_at=0 WHERE identity_hash=?", key);
            db.connection.commit();
        }
        assertEquals(200, get(api, auth("ThrottledUser")).statusCode());
        assertEquals(401, get(api, bad).statusCode());
        assertEquals(200, get(api, auth("ThrottledUser")).statusCode());
        try (var db = store.open()) {
            assertEquals(
                    0, db.count("SELECT COUNT(*) FROM os_auth_attempt WHERE identity_hash=?", key));
            db.update(
                    "INSERT INTO os_auth_attempt VALUES(?,4,?)",
                    key,
                    System.currentTimeMillis() + 300000L);
            db.connection.commit();
        }
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var results = new ArrayList<java.util.concurrent.Future<Integer>>();
            for (int i = 0; i < 6; i++)
                results.add(executor.submit(() -> get(api, bad).statusCode()));
            var statuses = new ArrayList<Integer>();
            for (var result : results) statuses.add(result.get());
            assertEquals(1, Collections.frequency(statuses, 401));
            assertEquals(5, Collections.frequency(statuses, 429));
        }
        String unknown =
                "Basic "
                        + Base64.getEncoder()
                                .encodeToString(
                                        "NoSuchAccount:incorrect".getBytes(StandardCharsets.UTF_8));
        for (int i = 0; i < 5; i++) assertEquals(401, get(api, unknown).statusCode());
        assertEquals(429, get(api, unknown).statusCode());
        String tooLong =
                "Basic "
                        + Base64.getEncoder()
                                .encodeToString(
                                        ("LongAttempt:" + "x".repeat(1025))
                                                .getBytes(StandardCharsets.UTF_8));
        assertEquals(401, get(api, tooLong).statusCode());
        try (var db = store.open()) {
            db.update("DELETE FROM os_auth_attempt");
            try (var insert = db.statement("INSERT INTO os_auth_attempt VALUES(?,1,?)")) {
                for (int i = 0; i < 4096; i++) {
                    insert.setString(1, String.format(Locale.ROOT, "%064x", i));
                    insert.setLong(2, System.currentTimeMillis() + 300000L);
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            db.connection.commit();
        }
        assertEquals(200, get(api, auth("admin")).statusCode());
        assertEquals(429, get(api, unknown).statusCode());
        try (var db = store.open()) {
            assertEquals(4096, db.count("SELECT COUNT(*) FROM os_auth_attempt"));
            db.update("UPDATE os_auth_attempt SET expires_at=0");
            db.connection.commit();
        }
        assertEquals(200, get(api, auth("ThrottledUser")).statusCode());
        try (var db = store.open()) {
            assertEquals(0, db.count("SELECT COUNT(*) FROM os_auth_attempt"));
        }
    }

    @Test
    void groupVisibilityEnforcesMembershipAcrossEverySurfaceAndRevokesExistingSessions()
            throws Exception {
        account("TeamMember", "agent");
        account("OtherTeam", "agent");
        account("TeamReader", "reader");
        String member =
                object(parsed(get("/api/openservice/me", auth("TeamMember"))).get("result"))
                        .get("user_id")
                        .toString();
        String reader =
                object(parsed(get("/api/openservice/me", auth("TeamReader"))).get("result"))
                        .get("user_id")
                        .toString();
        String group = add("sys_user_group").get("sys_id").toString(),
                id = add("incident").get("sys_id").toString();
        String members = "/api/openservice/groups/" + group + "/members",
                access = "/api/openservice/access/incident/" + id,
                api = "/api/now/table/incident/" + id,
                page = "/record/incident/" + id;
        assertEquals(
                200,
                json("PATCH", api, map("short_description", "TeamConfidential"), auth("admin"))
                        .statusCode());
        assertEquals(
                302,
                upload(page + "/attachment", "team-secret.bin", new byte[] {3, 1, 4}, auth("admin"))
                        .statusCode());
        String file =
                objects(parsed(get("/api/now/attachment", auth("admin"))).get("result"))
                        .getFirst()
                        .get("sys_id")
                        .toString();
        for (Object bad :
                List.of(
                        map("visibility", "group", "group_id", id),
                        map("visibility", "group", "group_id", ""),
                        map("visibility", "group", "group_id", null),
                        map("visibility", "private")))
            assertEquals(400, json("PATCH", access, bad, auth("admin")).statusCode());
        var policy =
                object(
                        parsed(
                                        json(
                                                "PATCH",
                                                access,
                                                map("visibility", "group", "group_id", group),
                                                auth("admin")))
                                .get("result"));
        assertEquals(group, policy.get("group_id"));
        assertEquals("", policy.get("owner_id"));
        assertEquals(404, get(api, auth("TeamMember")).statusCode());
        assertEquals(200, json("PUT", members, map("user_id", member), auth("admin")).statusCode());
        assertEquals(200, json("PUT", members, map("user_id", reader), auth("admin")).statusCode());
        new Store(environment);
        for (String path :
                List.of(
                        api,
                        page,
                        "/attachment/" + file,
                        "/api/now/attachment/" + file,
                        "/api/now/attachment/" + file + "/file")) {
            assertEquals(200, get(path, auth("TeamMember")).statusCode(), path);
            assertEquals(404, get(path, auth("OtherTeam")).statusCode(), path);
            assertEquals(200, get(path, auth("TeamReader")).statusCode(), path);
        }
        assertEquals(
                403,
                json("PATCH", api, map("description", "bad"), auth("TeamReader")).statusCode());
        assertEquals(
                200,
                json("PATCH", api, map("description", "Team updated"), auth("TeamMember"))
                        .statusCode());
        assertEquals(
                "1",
                get("/api/now/table/incident", auth("TeamMember"))
                        .headers()
                        .firstValue("X-Total-Count")
                        .orElseThrow());
        login("TeamMember");
        assertEquals(200, get(page, null).statusCode());
        assertEquals(
                200, json("DELETE", members + "/" + member, map(), auth("admin")).statusCode());
        assertEquals(404, get(page, null).statusCode());
        for (String name : List.of("TeamMember", "OtherTeam")) {
            for (String path :
                    List.of(
                            "/",
                            "/workspace/incident",
                            "/audit",
                            "/export/incident",
                            "/export/incident?source=true",
                            "/api/now/table/incident",
                            "/api/now/attachment")) {
                var response = get(path, auth(name));
                assertEquals(200, response.statusCode(), path);
                assertFalse(html(response).contains("TeamConfidential"), path);
                assertFalse(html(response).contains(file), path);
            }
            assertEquals(
                    map("result", map("stats", map("count", "0"))),
                    parsed(get("/api/now/stats/incident?sysparm_count=true", auth(name))));
            assertEquals(
                    404, json("PATCH", api, map("description", "denied"), auth(name)).statusCode());
            assertEquals(404, form(page + "/note", map("body", "denied"), auth(name)).statusCode());
            assertEquals(
                    404,
                    upload(page + "/attachment", "denied.bin", new byte[] {1}, auth(name))
                            .statusCode());
        }
        assertEquals(
                204,
                json("DELETE", "/api/now/table/sys_user_group/" + group, map(), auth("admin"))
                        .statusCode());
        assertEquals(404, get(api, auth("TeamReader")).statusCode());
        assertEquals(200, get(api, auth("admin")).statusCode());
        assertEquals(
                200,
                json("PATCH", access, map("visibility", "workspace", "owner_id", ""), auth("admin"))
                        .statusCode());
        assertEquals(200, get(api, auth("OtherTeam")).statusCode());
    }

    @Test
    void administratorManagesPersistentGroupMembershipWithoutRoleEscalation() throws Exception {
        account("GroupAgent", "agent");
        account("GroupReader", "reader");
        String user =
                object(parsed(get("/api/openservice/me", auth("GroupReader"))).get("result"))
                        .get("user_id")
                        .toString();
        String group = add("sys_user_group").get("sys_id").toString(),
                api = "/api/openservice/groups/" + group + "/members",
                page = "/record/sys_user_group/" + group;
        assertEquals(401, get(api, null).statusCode());
        for (String actor : List.of("GroupReader", "GroupAgent")) {
            assertEquals(403, get(api, auth(actor)).statusCode());
            assertEquals(403, json("PUT", api, map("user_id", user), auth(actor)).statusCode());
            assertFalse(html(get(page, auth(actor))).contains("Add member"));
        }
        assertEquals(List.of(), parsed(get(api, auth("admin"))).get("result"));
        assertEquals(405, json("POST", api, map(), auth("admin")).statusCode());
        assertEquals(400, get(api + "?x=1", auth("admin")).statusCode());
        assertEquals(
                404,
                get(api.replace(group, add("incident").get("sys_id").toString()), auth("admin"))
                        .statusCode());
        for (Object bad : List.of(map(), map("user_id", 1), map("user_id", "missing")))
            assertEquals(400, json("PUT", api, bad, auth("admin")).statusCode());
        assertEquals(200, json("PUT", api, map("user_id", user), auth("admin")).statusCode());
        assertEquals(200, json("PUT", api, map("user_id", user), auth("admin")).statusCode());
        new Store(environment);
        var members = objects(parsed(get(api, auth("admin"))).get("result"));
        assertEquals(1, members.size());
        assertEquals(Set.of("user_id", "name", "role", "active"), members.getFirst().keySet());
        assertEquals("reader", members.getFirst().get("role"));
        assertEquals(
                200,
                json(
                                "PATCH",
                                "/api/openservice/users/" + user + "/status",
                                map("active", false),
                                auth("admin"))
                        .statusCode());
        assertEquals(400, json("PUT", api, map("user_id", user), auth("admin")).statusCode());
        assertEquals(
                0,
                ((Number)
                                objects(parsed(get(api, auth("admin"))).get("result"))
                                        .getFirst()
                                        .get("active"))
                        .intValue());
        assertEquals(200, json("DELETE", api + "/" + user, map(), auth("admin")).statusCode());
        assertEquals(200, json("DELETE", api + "/" + user, map(), auth("admin")).statusCode());
        assertEquals(
                200,
                json(
                                "PATCH",
                                "/api/openservice/users/" + user + "/status",
                                map("active", true),
                                auth("admin"))
                        .statusCode());
        String csrf = login("admin"), form = "/group-members/" + group;
        assertEquals(
                403,
                form(form, map("operation", "add", "username", "GroupReader"), null).statusCode());
        assertEquals(400, form(form, map("operation", "bad", "csrf", csrf), null).statusCode());
        assertEquals(
                400,
                form(form, map("operation", "add", "username", "missing", "csrf", csrf), null)
                        .statusCode());
        assertEquals(
                302,
                form(form, map("operation", "add", "username", "GroupReader", "csrf", csrf), null)
                        .statusCode());
        assertTrue(html(get(page, null)).contains("Remove GroupReader"));
        assertEquals(
                302,
                form(form, map("operation", "remove", "user_id", user, "csrf", csrf), null)
                        .statusCode());
        assertEquals(List.of(), parsed(get(api, auth("admin"))).get("result"));
        try (var db = store.open()) {
            assertEquals(
                    2,
                    db.count(
                            "SELECT COUNT(*) FROM os_event WHERE record_id=? AND"
                                    + " action='member_added'",
                            group));
            assertEquals(
                    2,
                    db.count(
                            "SELECT COUNT(*) FROM os_event WHERE record_id=? AND"
                                    + " action='member_removed'",
                            group));
        }
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var first =
                    executor.submit(() ->
                            json("PUT", api, map("user_id", user), auth("admin")).statusCode());
            var second =
                    executor.submit(() ->
                            json("PUT", api, map("user_id", user), auth("admin")).statusCode());
            assertEquals(200, first.get());
            assertEquals(200, second.get());
        }
        assertEquals(1, objects(parsed(get(api, auth("admin"))).get("result")).size());
        assertEquals(
                204,
                json("DELETE", "/api/now/table/sys_user_group/" + group, map(), auth("admin"))
                        .statusCode());
        try (var db = store.open()) {
            assertEquals(
                    0, db.count("SELECT COUNT(*) FROM os_group_member WHERE group_id=?", group));
        }
        assertEquals(404, json("PUT", api, map("user_id", user), auth("admin")).statusCode());
    }

    @Test
    void privateRecordsEnforceVisibilityAcrossEveryReadAndMutationSurface() throws Exception {
        account("PrivateOwner", "agent");
        account("Outsider", "agent");
        account("PrivateReader", "reader");
        String owner =
                object(parsed(get("/api/openservice/me", auth("PrivateOwner"))).get("result"))
                        .get("user_id")
                        .toString();
        var record =
                object(
                        parsed(
                                        json(
                                                "POST",
                                                "/api/now/table/incident",
                                                map("short_description", "ConfidentialNeedle"),
                                                auth("admin")))
                                .get("result"));
        String id = record.get("sys_id").toString(),
                api = "/api/now/table/incident/" + id,
                page = "/record/incident/" + id,
                access = "/api/openservice/access/incident/" + id;
        assertEquals(
                302,
                upload(
                                page + "/attachment",
                                "private-evidence.bin",
                                new byte[] {1, 2, 3},
                                auth("admin"))
                        .statusCode());
        String file =
                objects(parsed(get("/api/now/attachment", auth("admin"))).get("result"))
                        .get(0)
                        .get("sys_id")
                        .toString();
        assertEquals(
                403,
                json(
                                "PATCH",
                                access,
                                map("visibility", "private", "owner_id", owner),
                                auth("Outsider"))
                        .statusCode());
        assertEquals(
                403,
                json(
                                "PATCH",
                                access,
                                map("visibility", "private", "owner_id", owner),
                                auth("PrivateReader"))
                        .statusCode());
        for (Object bad :
                List.of(
                        map(),
                        map("visibility", null, "owner_id", owner),
                        map("visibility", "bad", "owner_id", owner),
                        map("visibility", "private", "owner_id", 1),
                        map("visibility", "private", "owner_id", "missing"),
                        map("visibility", "workspace", "owner_id", owner)))
            assertEquals(400, json("PATCH", access, bad, auth("admin")).statusCode());
        assertEquals(405, get(access, auth("admin")).statusCode());
        assertEquals(
                400,
                json(
                                "PATCH",
                                access + "?x=1",
                                map("visibility", "private", "owner_id", owner),
                                auth("admin"))
                        .statusCode());
        assertEquals(
                200,
                json(
                                "PATCH",
                                access,
                                map("visibility", "private", "owner_id", owner),
                                auth("admin"))
                        .statusCode());
        new Store(environment);
        for (String name : List.of("Outsider", "PrivateReader")) {
            for (String path :
                    List.of(
                            api,
                            page,
                            "/attachment/" + file,
                            "/api/now/attachment/" + file,
                            "/api/now/attachment/" + file + "/file"))
                assertEquals(404, get(path, auth(name)).statusCode(), path);
            for (String path :
                    List.of(
                            "/",
                            "/workspace/incident",
                            "/audit",
                            "/export/incident",
                            "/export/incident?source=true",
                            "/api/now/table/incident",
                            "/api/now/table/incident?sysparm_no_count=true",
                            "/api/now/attachment",
                            "/api/now/attachment?sysparm_query=file_name=private-evidence.bin")) {
                var result = get(path, auth(name));
                assertEquals(200, result.statusCode(), path);
                assertFalse(html(result).contains("ConfidentialNeedle"), path);
                assertFalse(html(result).contains(file), path);
            }
            assertEquals(
                    "0",
                    get("/api/now/table/incident", auth(name))
                            .headers()
                            .firstValue("X-Total-Count")
                            .orElseThrow());
            assertEquals(
                    map("result", map("stats", map("count", "0"))),
                    parsed(get("/api/now/stats/incident?sysparm_count=true", auth(name))));
        }
        assertEquals(
                404,
                json("PATCH", api, map("short_description", "stolen"), auth("Outsider"))
                        .statusCode());
        assertEquals(
                404, form(page + "/note", map("body", "stolen"), auth("Outsider")).statusCode());
        assertEquals(
                404,
                upload(page + "/attachment", "evil.bin", new byte[] {1}, auth("Outsider"))
                        .statusCode());
        assertEquals(200, get(api, auth("PrivateOwner")).statusCode());
        assertEquals(200, get("/attachment/" + file, auth("PrivateOwner")).statusCode());
        assertEquals(
                200,
                get("/api/now/attachment/" + file + "/file", auth("PrivateOwner")).statusCode());
        assertEquals(
                1,
                objects(parsed(get("/api/now/attachment", auth("PrivateOwner"))).get("result"))
                        .size());
        assertEquals(
                200,
                json("PATCH", api, map("description", "owner update"), auth("PrivateOwner"))
                        .statusCode());
        String csrf = login("admin");
        assertEquals(
                403,
                form("/record-access/incident/" + id, map("visibility", "workspace"), null)
                        .statusCode());
        assertEquals(
                400,
                form(
                                "/record-access/incident/" + id,
                                map("visibility", "private", "owner", "missing", "csrf", csrf),
                                null)
                        .statusCode());
        assertEquals(
                302,
                form(
                                "/record-access/incident/" + id,
                                map("visibility", "workspace", "csrf", csrf),
                                null)
                        .statusCode());
        assertEquals(200, get(api, auth("Outsider")).statusCode());
        assertEquals(
                302,
                form(
                                "/record-access/incident/" + id,
                                map("visibility", "private", "owner", "PrivateOwner", "csrf", csrf),
                                null)
                        .statusCode());
        assertEquals(204, json("DELETE", api, map(), auth("admin")).statusCode());
        assertFalse(html(get("/audit", auth("Outsider"))).contains("ConfidentialNeedle"));
        assertTrue(html(get("/audit", auth("PrivateOwner"))).contains("ConfidentialNeedle"));
    }

    @Test
    void assistedRecoveryRequiresReauthenticationAndNeverRestoresRevokedAccess() throws Exception {
        account("RecoverUser", "reader");
        account("RecoveryAgent", "agent");
        account("RecoveryAdmin", "admin");
        String target =
                object(parsed(get("/api/openservice/me", auth("RecoverUser"))).get("result"))
                        .get("user_id")
                        .toString();
        String api = "/api/openservice/users/" + target + "/recovery",
                page = "/admin/accounts/" + target;
        var confirm = map("current_password", PASSWORD);
        assertEquals(403, json("POST", api, confirm, auth("RecoverUser")).statusCode());
        assertEquals(403, json("POST", api, confirm, auth("RecoveryAgent")).statusCode());
        for (Object bad :
                List.of(
                        List.of(),
                        map(),
                        map("current_password", 1),
                        map("current_password", "x".repeat(1025)),
                        map("current_password", "wrong"),
                        map("current_password", PASSWORD, "extra", 1)))
            assertEquals(400, json("POST", api, bad, auth("admin")).statusCode());
        assertEquals(405, get(api, auth("admin")).statusCode());
        assertEquals(400, json("POST", api + "?x=1", confirm, auth("admin")).statusCode());
        assertEquals(
                404,
                json(
                                "POST",
                                "/api/openservice/users/" + "f".repeat(32) + "/recovery",
                                confirm,
                                auth("admin"))
                        .statusCode());
        login("RecoverUser");
        var issued = json("POST", api, confirm, auth("admin"));
        assertEquals(201, issued.statusCode());
        String previous = object(parsed(issued).get("result")).get("reset_path").toString();
        String path =
                object(parsed(json("POST", api, confirm, auth("admin"))).get("result"))
                        .get("reset_path")
                        .toString();
        assertNotEquals(path, previous);
        assertEquals(400, get(previous, null).statusCode());
        assertEquals(200, get("/api/openservice/me", null).statusCode());
        assertEquals(200, get("/api/openservice/me", auth("RecoverUser")).statusCode());
        String token = path.substring(9);
        try (var db = store.open()) {
            var row = db.one("SELECT * FROM os_recovery WHERE user_id=?", target);
            assertEquals(Passwords.tokenHash(token), row.get("token_hash"));
            assertFalse(row.toString().contains(token));
        }
        var first = client;
        String next = "recovered-user-password";
        try (var second =
                HttpClient.newBuilder()
                        .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                        .build()) {
            client = second;
            login("RecoverUser");
            var resetPage = get(path, null);
            assertEquals(200, resetPage.statusCode());
            assertEquals(
                    "no-referrer", resetPage.headers().firstValue("Referrer-Policy").orElseThrow());
            String csrf = csrf(resetPage);
            assertEquals(403, form(path, map("password", next), null).statusCode());
            for (String bad : List.of("short", "x".repeat(1025), PASSWORD))
                assertEquals(
                        400, form(path, map("csrf", csrf, "password", bad), null).statusCode());
            assertEquals(400, get(path + "?redirect=bad", null).statusCode());
            assertEquals(405, json("PUT", path, map(), null).statusCode());
            new Store(environment);
            assertEquals(302, form(path, map("csrf", csrf, "password", next), null).statusCode());
            assertEquals(401, get("/api/openservice/me", null).statusCode());
            client = first;
            assertEquals(401, get("/api/openservice/me", null).statusCode());
        } finally {
            client = first;
        }
        assertEquals(400, get(path, null).statusCode());
        assertEquals(401, get("/api/openservice/me", auth("RecoverUser")).statusCode());
        String newAuth =
                "Basic "
                        + Base64.getEncoder()
                                .encodeToString(
                                        ("RecoverUser:" + next).getBytes(StandardCharsets.UTF_8));
        var identity = object(parsed(get("/api/openservice/me", newAuth)).get("result"));
        assertEquals(target, identity.get("user_id"));
        assertEquals("reader", identity.get("role"));
        var events =
                objects(
                        parsed(get("/api/openservice/security-events", auth("admin")))
                                .get("result"));
        assertEquals("account.password_recovered", events.get(0).get("action"));
        assertFalse(JSON.writeValueAsString(events).contains(token));
        assertFalse(JSON.writeValueAsString(events).contains(next));
        String expired =
                object(parsed(json("POST", api, confirm, auth("admin"))).get("result"))
                        .get("reset_path")
                        .toString();
        try (var db = store.open()) {
            db.update("UPDATE os_recovery SET expires_at=0 WHERE user_id=?", target);
            db.connection.commit();
        }
        assertEquals(400, get(expired, null).statusCode());
        String revoked =
                object(parsed(json("POST", api, confirm, auth("admin"))).get("result"))
                        .get("reset_path")
                        .toString();
        assertEquals(403, json("POST", api + "/revoke", map(), auth("RecoveryAgent")).statusCode());
        assertEquals(
                400, json("POST", api + "/revoke", map("extra", 1), auth("admin")).statusCode());
        assertEquals(405, get(api + "/revoke", auth("admin")).statusCode());
        assertEquals(200, json("POST", api + "/revoke", map(), auth("admin")).statusCode());
        assertEquals(200, json("POST", api + "/revoke", map(), auth("admin")).statusCode());
        assertEquals(400, get(revoked, null).statusCode());
        assertEquals(
                404,
                json(
                                "POST",
                                "/api/openservice/users/" + "f".repeat(32) + "/recovery/revoke",
                                map(),
                                auth("admin"))
                        .statusCode());
        String disabled =
                object(parsed(json("POST", api, confirm, auth("admin"))).get("result"))
                        .get("reset_path")
                        .toString();
        assertEquals(
                200,
                json(
                                "PATCH",
                                "/api/openservice/users/" + target + "/status",
                                map("active", false),
                                auth("admin"))
                        .statusCode());
        assertEquals(409, json("POST", api, confirm, auth("admin")).statusCode());
        assertEquals(400, get(disabled, null).statusCode());
        assertEquals(
                200,
                json(
                                "PATCH",
                                "/api/openservice/users/" + target + "/status",
                                map("active", true),
                                auth("admin"))
                        .statusCode());
        assertEquals(400, get(disabled, null).statusCode());
        String issuer =
                object(parsed(get("/api/openservice/me", auth("RecoveryAdmin"))).get("result"))
                        .get("user_id")
                        .toString();
        String lostAuthority =
                object(parsed(json("POST", api, confirm, auth("RecoveryAdmin"))).get("result"))
                        .get("reset_path")
                        .toString();
        assertEquals(
                200,
                json(
                                "PATCH",
                                "/api/openservice/users/" + issuer + "/role",
                                map("role", "agent"),
                                auth("admin"))
                        .statusCode());
        assertEquals(400, get(lostAuthority, null).statusCode());
        assertEquals(
                200,
                json(
                                "PATCH",
                                "/api/openservice/users/" + issuer + "/role",
                                map("role", "admin"),
                                auth("admin"))
                        .statusCode());
        assertEquals(400, get(lostAuthority, null).statusCode());
        String changed =
                object(parsed(json("POST", api, confirm, auth("admin"))).get("result"))
                        .get("reset_path")
                        .toString();
        assertEquals(
                200,
                json(
                                "POST",
                                "/api/openservice/me/password",
                                map(
                                        "current_password",
                                        next,
                                        "new_password",
                                        "another-recovery-password"),
                                newAuth)
                        .statusCode());
        assertEquals(400, get(changed, null).statusCode());
        assertEquals(400, get("/recover/" + "x".repeat(32), null).statusCode());
        String csrf = login("admin");
        assertEquals(
                403,
                form(page, map("operation", "recovery", "current_password", PASSWORD), null)
                        .statusCode());
        var error =
                form(
                        page,
                        map(
                                "operation",
                                "recovery",
                                "current_password",
                                "do-not-echo-secret",
                                "csrf",
                                csrf),
                        null);
        assertEquals(400, error.statusCode());
        assertFalse(html(error).contains("do-not-echo-secret"));
        var ui =
                form(
                        page,
                        map("operation", "recovery", "current_password", PASSWORD, "csrf", csrf),
                        null);
        assertEquals(201, ui.statusCode());
        assertTrue(html(ui).contains("Password recovery link"));
        assertEquals(302, form(page + "/recovery/revoke", map("csrf", csrf), null).statusCode());
        assertFalse(html(get(page, null)).contains("Password recovery link"));
    }

    @Test
    void invitationsSupportRecipientPasswordsExpiryRevocationAndPrivateTokens() throws Exception {
        String api = "/api/openservice/invitations";
        assertTrue(objects(parsed(get(api, auth("admin"))).get("result")).isEmpty());
        account("InviteReader", "reader");
        account("InviteAgent", "agent");
        assertEquals(403, get(api, auth("InviteReader")).statusCode());
        assertEquals(403, get("/admin/invitations", auth("InviteAgent")).statusCode());
        assertEquals(
                403,
                json("POST", api, map("name", "No", "role", "reader"), auth("InviteAgent"))
                        .statusCode());
        for (Object bad :
                List.of(
                        List.of(),
                        map(),
                        map("name", 1, "role", "reader"),
                        map("name", "bad name", "role", "reader"),
                        map("name", "A", "role", "owner"),
                        map("name", "admin", "role", "reader")))
            assertEquals(400, json("POST", api, bad, auth("admin")).statusCode());
        assertEquals(400, get(api + "?extra=1", auth("admin")).statusCode());
        assertEquals(405, json("DELETE", api, map(), auth("admin")).statusCode());
        var response =
                json("POST", api, map("name", "InvitedUser", "role", "reader"), auth("admin"));
        assertEquals(201, response.statusCode());
        var issued = object(parsed(response).get("result"));
        String path = issued.get("accept_path").toString(),
                token = path.substring("/join/".length()),
                id = issued.get("invite_id").toString();
        assertEquals(32, token.length());
        assertEquals("pending", issued.get("state"));
        assertFalse(html(get(api, auth("admin"))).contains(token));
        try (var db = store.open()) {
            var row = db.one("SELECT * FROM os_invitation WHERE invite_id=?", id);
            assertEquals(Passwords.tokenHash(token), row.get("token_hash"));
            assertFalse(row.toString().contains(token));
        }
        var page = get(path, null);
        assertEquals(200, page.statusCode());
        assertEquals("no-referrer", page.headers().firstValue("Referrer-Policy").orElseThrow());
        assertEquals("no-store", page.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(html(page).contains("InvitedUser"));
        String csrf = csrf(page);
        assertEquals(403, form(path, map("password", PASSWORD), null).statusCode());
        assertEquals(400, form(path, map("password", "short", "csrf", csrf), null).statusCode());
        assertEquals(400, get(path + "?next=evil", null).statusCode());
        assertEquals(405, json("PUT", path, map(), null).statusCode());
        new Store(environment);
        assertEquals(302, form(path, map("password", PASSWORD, "csrf", csrf), null).statusCode());
        assertEquals(401, get("/api/openservice/me", null).statusCode());
        assertEquals(400, get(path, null).statusCode());
        assertEquals(
                409, json("POST", api + "/" + id + "/revoke", map(), auth("admin")).statusCode());
        login("InvitedUser");
        assertEquals(
                "reader",
                object(parsed(get("/api/openservice/me", null)).get("result")).get("role"));
        assertEquals(
                403,
                json(
                                "POST",
                                "/api/now/table/incident",
                                map("short_description", "denied"),
                                auth("InvitedUser"))
                        .statusCode());
        var events =
                objects(
                        parsed(get("/api/openservice/security-events", auth("admin")))
                                .get("result"));
        assertEquals("account.invitation_accepted", events.get(0).get("action"));
        assertFalse(JSON.writeValueAsString(events).contains(token));
        var first =
                object(
                        parsed(
                                        json(
                                                "POST",
                                                api,
                                                map("name", "ReplaceInvite", "role", "agent"),
                                                auth("admin")))
                                .get("result"));
        var replacement =
                object(
                        parsed(
                                        json(
                                                "POST",
                                                api,
                                                map("name", "ReplaceInvite", "role", "reader"),
                                                auth("admin")))
                                .get("result"));
        assertNotEquals(first.get("accept_path"), replacement.get("accept_path"));
        assertEquals(400, get(first.get("accept_path").toString(), null).statusCode());
        assertEquals(200, get(replacement.get("accept_path").toString(), null).statusCode());
        String revoke = api + "/" + replacement.get("invite_id") + "/revoke";
        assertEquals(403, json("POST", revoke, map(), auth("InviteReader")).statusCode());
        assertEquals(400, json("POST", revoke, map("extra", 1), auth("admin")).statusCode());
        assertEquals(405, get(revoke, auth("admin")).statusCode());
        assertEquals(200, json("POST", revoke, map(), auth("admin")).statusCode());
        assertEquals(200, json("POST", revoke, map(), auth("admin")).statusCode());
        assertEquals(400, get(replacement.get("accept_path").toString(), null).statusCode());
        assertEquals(
                404,
                json("POST", api + "/" + "f".repeat(32) + "/revoke", map(), auth("admin"))
                        .statusCode());
        var expired =
                object(
                        parsed(
                                        json(
                                                "POST",
                                                api,
                                                map("name", "ExpiredInvite", "role", "reader"),
                                                auth("admin")))
                                .get("result"));
        try (var db = store.open()) {
            db.update(
                    "UPDATE os_invitation SET expires_at=0 WHERE invite_id=?",
                    expired.get("invite_id"));
            db.connection.commit();
        }
        assertEquals(400, get(expired.get("accept_path").toString(), null).statusCode());
        assertEquals(
                "expired",
                objects(parsed(get(api, auth("admin"))).get("result")).get(0).get("state"));
        account("Inviter", "admin");
        var removed =
                object(
                        parsed(
                                        json(
                                                "POST",
                                                api,
                                                map("name", "RemovedIssuer", "role", "reader"),
                                                auth("Inviter")))
                                .get("result"));
        String inviter =
                object(parsed(get("/api/openservice/me", auth("Inviter"))).get("result"))
                        .get("user_id")
                        .toString();
        assertEquals(
                200,
                json(
                                "PATCH",
                                "/api/openservice/users/" + inviter + "/role",
                                map("role", "reader"),
                                auth("admin"))
                        .statusCode());
        assertEquals(400, get(removed.get("accept_path").toString(), null).statusCode());
        assertEquals(
                200,
                json(
                                "PATCH",
                                "/api/openservice/users/" + inviter + "/role",
                                map("role", "admin"),
                                auth("admin"))
                        .statusCode());
        assertEquals(400, get(removed.get("accept_path").toString(), null).statusCode());
        var inactiveIssuer =
                object(
                        parsed(
                                        json(
                                                "POST",
                                                api,
                                                map("name", "InactiveIssuer", "role", "reader"),
                                                auth("Inviter")))
                                .get("result"));
        assertEquals(
                200,
                json(
                                "PATCH",
                                "/api/openservice/users/" + inviter + "/status",
                                map("active", false),
                                auth("admin"))
                        .statusCode());
        assertEquals(400, get(inactiveIssuer.get("accept_path").toString(), null).statusCode());
        assertEquals(
                200,
                json(
                                "PATCH",
                                "/api/openservice/users/" + inviter + "/status",
                                map("active", true),
                                auth("admin"))
                        .statusCode());
        assertEquals(400, get(inactiveIssuer.get("accept_path").toString(), null).statusCode());
        assertEquals(400, get("/join/" + "x".repeat(32), null).statusCode());
        String adminCsrf = login("admin");
        assertEquals(403, form("/admin/invitations", map("name", "UiInvite"), null).statusCode());
        assertEquals(
                400,
                form("/admin/invitations", map("name", "bad name", "csrf", adminCsrf), null)
                        .statusCode());
        var created = form("/admin/invitations", map("name", "UiInvite", "csrf", adminCsrf), null);
        assertEquals(201, created.statusCode());
        assertTrue(html(created).contains("Invitation acceptance link"));
        var latest = objects(parsed(get(api, auth("admin"))).get("result")).get(0);
        assertEquals(
                302,
                form(
                                "/admin/invitations/" + latest.get("invite_id") + "/revoke",
                                map("csrf", adminCsrf),
                                null)
                        .statusCode());
        assertEquals(200, get("/admin/invitations", null).statusCode());
        try (var db = store.open()) {
            String actor =
                    db.one("SELECT user_id FROM os_user WHERE name='admin'")
                            .get("user_id")
                            .toString();
            for (int n = 0; n < 101; n++)
                Invitations.issue(db, actor, map("name", "BatchInvite" + n, "role", "reader"));
            db.connection.commit();
        }
        var bounded = objects(parsed(get(api, auth("admin"))).get("result"));
        assertEquals(100, bounded.size());
        assertEquals("BatchInvite100", bounded.get(0).get("name"));
        assertEquals("BatchInvite1", bounded.get(99).get("name"));
    }

    @Test
    void securityEventsRecordCommittedLifecycleChangesWithoutSecrets() throws Exception {
        String api = "/api/openservice/security-events";
        assertTrue(objects(parsed(get(api, auth("admin"))).get("result")).isEmpty());
        account("AuditReader", "reader");
        String target =
                object(parsed(get("/api/openservice/me", auth("AuditReader"))).get("result"))
                        .get("user_id")
                        .toString();
        String actor =
                object(parsed(get("/api/openservice/me", auth("admin"))).get("result"))
                        .get("user_id")
                        .toString();
        assertEquals(403, get(api, auth("AuditReader")).statusCode());
        assertEquals(403, get("/admin/security-events", auth("AuditReader")).statusCode());
        assertEquals(405, json("POST", api, map(), auth("admin")).statusCode());
        assertEquals(400, get(api + "?limit=1", auth("admin")).statusCode());
        assertEquals(400, get("/admin/security-events?x=1", auth("admin")).statusCode());
        json(
                "PATCH",
                "/api/openservice/users/" + target + "/role",
                map("role", "agent"),
                auth("admin"));
        json(
                "PATCH",
                "/api/openservice/users/" + target + "/role",
                map("role", "agent"),
                auth("admin"));
        json(
                "PATCH",
                "/api/openservice/users/" + target + "/status",
                map("active", false),
                auth("admin"));
        json(
                "PATCH",
                "/api/openservice/users/" + target + "/status",
                map("active", true),
                auth("admin"));
        json("POST", "/api/openservice/me/sessions/revoke", map(), auth("AuditReader"));
        json(
                "POST",
                "/api/openservice/me/password",
                map("current_password", PASSWORD, "new_password", "secret-not-in-events"),
                auth("AuditReader"));
        var events = objects(parsed(get(api, auth("admin"))).get("result"));
        assertEquals(6, events.size());
        assertEquals(
                List.of(
                        "account.password_changed",
                        "account.sessions_revoked",
                        "account.state_changed",
                        "account.state_changed",
                        "account.role_changed",
                        "account.created"),
                events.stream().map(event -> event.get("action")).toList());
        assertEquals(map("before", "reader", "after", "agent"), events.get(4).get("detail"));
        assertEquals(actor, events.get(5).get("actor_id"));
        assertEquals(target, events.get(5).get("target_id"));
        assertEquals("AuditReader", events.get(5).get("target_name"));
        assertEquals(
                Set.of(
                        "event_id",
                        "occurred_at",
                        "actor_id",
                        "actor_name",
                        "target_id",
                        "target_name",
                        "action",
                        "detail"),
                events.get(0).keySet());
        assertFalse(JSON.writeValueAsString(events).contains(PASSWORD));
        assertFalse(JSON.writeValueAsString(events).contains("secret-not-in-events"));
        assertFalse(JSON.writeValueAsString(events).contains("pbkdf2"));
        try (var db = store.open()) {
            Service.changeRole(db, actor, target, map("role", "admin"));
            db.connection.rollback();
            assertEquals(6, db.count("SELECT COUNT(*) FROM os_security_event"));
            assertEquals("agent", Service.account(db, target).get("role"));
        }
        assertEquals(
                400,
                json(
                                "PATCH",
                                "/api/openservice/users/" + target + "/role",
                                map("role", "bad"),
                                auth("admin"))
                        .statusCode());
        new Store(environment);
        assertEquals(events, objects(parsed(get(api, auth("admin"))).get("result")));
        try (var db = store.open()) {
            for (int n = 0; n < 101; n++)
                SecurityEvents.record(db, actor, target, "test.fixture", map("index", n));
            db.connection.commit();
        }
        events = objects(parsed(get(api, auth("admin"))).get("result"));
        assertEquals(100, events.size());
        assertEquals(100, object(events.get(0).get("detail")).get("index"));
        assertEquals(1, object(events.get(99).get("detail")).get("index"));
        assertEquals(200, get("/admin/security-events", auth("admin")).statusCode());
    }

    @Test
    void passwordChangesRequireCurrentSecretAndInvalidateAllOldAccess() throws Exception {
        String api = "/api/openservice/me/password", next = "changed-test-password";
        for (String role : List.of("reader", "agent", "admin")) {
            String name = "Password" + role;
            account(name, role);
            login(name);
            var first = client;
            try (var second =
                    HttpClient.newBuilder()
                            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                            .build()) {
                client = second;
                login(name);
                assertEquals(
                        200,
                        json(
                                        "POST",
                                        api,
                                        map("current_password", PASSWORD, "new_password", next),
                                        auth(name))
                                .statusCode());
                assertEquals(401, get("/api/openservice/me", null).statusCode());
                client = first;
                assertEquals(401, get("/api/openservice/me", null).statusCode());
            } finally {
                client = first;
            }
            assertEquals(401, get("/api/openservice/me", auth(name)).statusCode());
            String credential =
                    "Basic "
                            + Base64.getEncoder()
                                    .encodeToString(
                                            (name + ":" + next).getBytes(StandardCharsets.UTF_8));
            assertEquals(200, get("/api/openservice/me", credential).statusCode());
            new Store(environment);
            assertEquals(200, get("/api/openservice/me", credential).statusCode());
        }
        account("PasswordErrors", "reader");
        String credential = auth("PasswordErrors");
        for (Object bad :
                List.of(
                        List.of(),
                        map(),
                        map("current_password", 1, "new_password", next),
                        map("current_password", "x".repeat(1025), "new_password", next),
                        map("current_password", PASSWORD, "new_password", 1),
                        map("current_password", PASSWORD, "new_password", "x".repeat(11)),
                        map("current_password", PASSWORD, "new_password", "x".repeat(1025)),
                        map("current_password", "wrong", "new_password", next),
                        map("current_password", PASSWORD, "new_password", PASSWORD),
                        map(
                                "current_password",
                                PASSWORD,
                                "new_password",
                                next,
                                "user_id",
                                "other")))
            assertEquals(400, json("POST", api, bad, credential).statusCode());
        assertEquals(405, get(api, credential).statusCode());
        assertEquals(
                400,
                json(
                                "POST",
                                api + "?user=other",
                                map("current_password", PASSWORD, "new_password", next),
                                credential)
                        .statusCode());
        String token = login("PasswordErrors");
        assertEquals(
                403,
                form(
                                "/account/password",
                                map("current_password", PASSWORD, "new_password", next),
                                null)
                        .statusCode());
        var error =
                form(
                        "/account/password",
                        map(
                                "csrf",
                                token,
                                "current_password",
                                "wrong-secret-value",
                                "new_password",
                                next),
                        null);
        assertEquals(400, error.statusCode());
        assertTrue(html(error).contains("Current password is incorrect"));
        assertFalse(html(error).contains("wrong-secret-value"));
        assertFalse(html(error).contains(next));
        assertEquals(
                302,
                form(
                                "/account/password",
                                map(
                                        "csrf",
                                        token,
                                        "current_password",
                                        PASSWORD,
                                        "new_password",
                                        next),
                                null)
                        .statusCode());
        assertEquals(401, get("/api/openservice/me", null).statusCode());
        try (var db = store.open()) {
            assertEquals(
                    401,
                    assertThrows(
                                    Failure.class,
                                    () ->
                                            Service.changePassword(
                                                    db,
                                                    "missing",
                                                    map(
                                                            "current_password",
                                                            PASSWORD,
                                                            "new_password",
                                                            next)))
                            .status);
            String id =
                    db.one("SELECT user_id FROM os_user WHERE name='PasswordErrors'")
                            .get("user_id")
                            .toString();
            Service.changePassword(
                    db, id, map("current_password", next, "new_password", "x".repeat(12)));
            Service.changePassword(
                    db,
                    id,
                    map("current_password", "x".repeat(12), "new_password", "🌟".repeat(1024)));
            assertTrue(
                    Passwords.matches(
                            db.one("SELECT password FROM os_user WHERE user_id=?", id)
                                    .get("password")
                                    .toString(),
                            "🌟".repeat(1024)));
        }
    }

    @Test
    void roleChangesEnforcePermissionsRevokeSessionsAndPreserveAnAdministrator() throws Exception {
        account("RoleAgent", "agent");
        account("RoleReader", "reader");
        String id =
                object(parsed(get("/api/openservice/me", auth("RoleAgent"))).get("result"))
                        .get("user_id")
                        .toString();
        String api = "/api/openservice/users/" + id + "/role", page = "/admin/accounts/" + id;
        login("RoleAgent");
        assertEquals(403, json("PATCH", api, map("role", "admin"), auth("RoleAgent")).statusCode());
        assertEquals(
                403, json("PATCH", api, map("role", "admin"), auth("RoleReader")).statusCode());
        assertEquals(200, json("PATCH", api, map("role", "reader"), auth("admin")).statusCode());
        assertEquals(401, get("/api/openservice/me", null).statusCode());
        assertEquals(
                403,
                json(
                                "POST",
                                "/api/now/table/incident",
                                map("short_description", "blocked"),
                                auth("RoleAgent"))
                        .statusCode());
        new Store(environment);
        assertEquals(
                "reader",
                object(parsed(get("/api/openservice/me", auth("RoleAgent"))).get("result"))
                        .get("role"));
        login("RoleAgent");
        assertEquals(200, json("PATCH", api, map("role", "reader"), auth("admin")).statusCode());
        assertEquals(200, get("/api/openservice/me", null).statusCode());
        assertEquals(200, json("PATCH", api, map("role", "admin"), auth("admin")).statusCode());
        assertEquals(401, get("/api/openservice/me", null).statusCode());
        assertEquals(200, get("/admin/accounts", auth("RoleAgent")).statusCode());
        assertEquals(200, json("PATCH", api, map("role", "agent"), auth("admin")).statusCode());
        assertEquals(403, get("/admin/accounts", auth("RoleAgent")).statusCode());
        for (Object bad :
                List.of(
                        map(),
                        map("role", "owner"),
                        map("role", 4),
                        map("role", "reader", "active", true),
                        List.of()))
            assertEquals(400, json("PATCH", api, bad, auth("admin")).statusCode());
        assertEquals(
                400,
                json("PATCH", api + "?x=1", map("role", "reader"), auth("admin")).statusCode());
        assertEquals(405, get(api, auth("admin")).statusCode());
        assertEquals(
                404,
                json(
                                "PATCH",
                                "/api/openservice/users/" + "f".repeat(32) + "/role",
                                map("role", "reader"),
                                auth("admin"))
                        .statusCode());
        String admin =
                object(parsed(get("/api/openservice/me", auth("admin"))).get("result"))
                        .get("user_id")
                        .toString();
        assertEquals(
                409,
                json(
                                "PATCH",
                                "/api/openservice/users/" + admin + "/role",
                                map("role", "agent"),
                                auth("admin"))
                        .statusCode());
        assertEquals(200, json("PATCH", api, map("role", "admin"), auth("admin")).statusCode());
        assertEquals(
                200,
                json(
                                "PATCH",
                                "/api/openservice/users/" + id + "/status",
                                map("active", false),
                                auth("admin"))
                        .statusCode());
        assertEquals(200, json("PATCH", api, map("role", "reader"), auth("admin")).statusCode());
        assertEquals(401, get("/api/openservice/me", auth("RoleAgent")).statusCode());
        String token = login("admin");
        assertEquals(403, form(page, map("operation", "role", "role", "admin"), null).statusCode());
        assertEquals(400, form(page, map("operation", "other", "csrf", token), null).statusCode());
        assertEquals(
                400,
                form(page, map("operation", "role", "role", "bad", "csrf", token), null)
                        .statusCode());
        assertEquals(
                302,
                form(page, map("operation", "role", "role", "agent", "csrf", token), null)
                        .statusCode());
        assertTrue(html(get(page, null)).contains("Save account role"));
    }

    @Test
    void administratorsDeactivateAndReactivateWithoutRevivingSessions() throws Exception {
        account("OffboardAgent", "agent");
        account("OffboardReader", "reader");
        account("OtherAdmin", "admin");
        String id =
                object(parsed(get("/api/openservice/me", auth("OffboardAgent"))).get("result"))
                        .get("user_id")
                        .toString();
        String api = "/api/openservice/users/" + id + "/status", page = "/admin/accounts/" + id;
        assertEquals(403, get(page, auth("OffboardAgent")).statusCode());
        assertEquals(
                403, json("PATCH", api, map("active", false), auth("OffboardAgent")).statusCode());
        assertEquals(
                403, json("PATCH", api, map("active", false), auth("OffboardReader")).statusCode());
        login("OffboardAgent");
        var result = json("PATCH", api, map("active", false), auth("admin"));
        assertEquals(200, result.statusCode());
        assertEquals(
                Set.of("user_id", "name", "role", "active"),
                object(parsed(result).get("result")).keySet());
        assertEquals(false, object(parsed(result).get("result")).get("active"));
        assertEquals(401, get("/api/openservice/me", null).statusCode());
        assertEquals(401, get("/api/now/table/incident", auth("OffboardAgent")).statusCode());
        String csrf = csrf(get("/login", null));
        assertEquals(
                401,
                form(
                                "/login",
                                map(
                                        "csrf",
                                        csrf,
                                        "username",
                                        "OffboardAgent",
                                        "password",
                                        PASSWORD),
                                null)
                        .statusCode());
        assertEquals(200, json("PATCH", api, map("active", false), auth("admin")).statusCode());
        new Store(environment);
        assertEquals(401, get("/api/openservice/me", auth("OffboardAgent")).statusCode());
        assertEquals(200, json("PATCH", api, map("active", true), auth("admin")).statusCode());
        assertEquals(401, get("/api/openservice/me", null).statusCode());
        login("OffboardAgent");
        assertEquals(
                id, object(parsed(get("/api/openservice/me", null)).get("result")).get("user_id"));
        assertEquals(200, json("PATCH", api, map("active", true), auth("admin")).statusCode());
        for (Object bad :
                List.of(
                        map(),
                        map("active", "false"),
                        map("active", false, "role", "admin"),
                        List.of()))
            assertEquals(400, json("PATCH", api, bad, auth("admin")).statusCode());
        assertEquals(
                404,
                json(
                                "PATCH",
                                "/api/openservice/users/" + "f".repeat(32) + "/status",
                                map("active", false),
                                auth("admin"))
                        .statusCode());
        assertEquals(405, get(api, auth("admin")).statusCode());
        assertEquals(400, get(page + "?other=true", auth("admin")).statusCode());
        String other =
                object(parsed(get("/api/openservice/me", auth("OtherAdmin"))).get("result"))
                        .get("user_id")
                        .toString();
        String otherApi = "/api/openservice/users/" + other + "/status";
        assertEquals(
                200, json("PATCH", otherApi, map("active", false), auth("admin")).statusCode());
        String admin =
                object(parsed(get("/api/openservice/me", auth("admin"))).get("result"))
                        .get("user_id")
                        .toString();
        assertEquals(
                409,
                json(
                                "PATCH",
                                "/api/openservice/users/" + admin + "/status",
                                map("active", false),
                                auth("admin"))
                        .statusCode());
        assertEquals(
                409,
                form("/admin/accounts/" + admin, map("active", "false"), auth("admin"))
                        .statusCode());
        assertEquals(200, json("PATCH", otherApi, map("active", true), auth("admin")).statusCode());
        String token = login("admin");
        assertEquals(403, form(page, map("active", "false"), null).statusCode());
        assertEquals(400, form(page, map("active", "invalid", "csrf", token), null).statusCode());
        assertEquals(302, form(page, map("active", "false", "csrf", token), null).statusCode());
        assertEquals(200, get(page, null).statusCode());
    }

    @Test
    void revocationInvalidatesAllBrowserSessionsButPreservesCredentials() throws Exception {
        account("SessionReader", "reader");
        login("SessionReader");
        var first = client;
        try (var second =
                HttpClient.newBuilder()
                        .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                        .build()) {
            client = second;
            login("SessionReader");
            assertEquals(200, get("/account", null).statusCode());
            assertEquals(
                    200,
                    json(
                                    "POST",
                                    "/api/openservice/me/sessions/revoke",
                                    map(),
                                    auth("SessionReader"))
                            .statusCode());
            assertEquals(401, get("/api/openservice/me", null).statusCode());
            client = first;
            assertEquals(401, get("/api/openservice/me", null).statusCode());
            assertEquals(302, get("/account", null).statusCode());
            assertEquals(200, get("/api/openservice/me", auth("SessionReader")).statusCode());
            new Store(environment);
            assertEquals(401, get("/api/openservice/me", null).statusCode());
            String token = login("SessionReader");
            assertEquals(
                    403, form("/account/sessions/revoke", map("csrf", "bad"), null).statusCode());
            assertEquals(200, get("/api/openservice/me", null).statusCode());
            assertEquals(
                    302, form("/account/sessions/revoke", map("csrf", token), null).statusCode());
            assertEquals(401, get("/api/openservice/me", null).statusCode());
            login("SessionReader");
            assertEquals(200, get("/account", null).statusCode());
        } finally {
            client = first;
        }
        assertEquals(405, get("/api/openservice/me/sessions/revoke", auth("admin")).statusCode());
        assertEquals(
                400,
                json(
                                "POST",
                                "/api/openservice/me/sessions/revoke",
                                map("user_id", "other"),
                                auth("admin"))
                        .statusCode());
        assertEquals(
                400,
                json("POST", "/api/openservice/me/sessions/revoke?user=other", map(), auth("admin"))
                        .statusCode());
        assertEquals(400, get("/account?user=other", auth("admin")).statusCode());
        assertEquals(405, json("POST", "/account", map(), auth("admin")).statusCode());
    }

    @Test
    void stableCurrentIdentityIsPrivatePersistentAndSessionBound() throws Exception {
        assertEquals(401, get("/api/openservice/me", null).statusCode());
        try (var db = store.open()) {
            db.update(
                    "INSERT INTO os_user(name,password,role)"
                            + " VALUES('LegacyWriter','unusable','reader')");
            db.connection.commit();
        }
        assertEquals(
                401,
                get("/api/openservice/me", null).statusCode(),
                "A missing session must not match an unassigned identity");
        try (var db = store.open()) {
            db.update("DELETE FROM os_user WHERE name='LegacyWriter'");
            db.connection.commit();
        }
        account("IdentityAgent", "agent");
        account("IdentityReader", "reader");
        var ids = new HashSet<String>();
        for (String name : List.of("admin", "IdentityAgent", "IdentityReader")) {
            var response = get("/api/openservice/me", auth(name));
            assertEquals(200, response.statusCode());
            var user = object(parsed(response).get("result"));
            assertEquals(Set.of("user_id", "name", "role"), user.keySet());
            assertEquals(name, user.get("name"));
            assertTrue(user.get("user_id").toString().matches("[0-9a-f]{32}"));
            assertTrue(ids.add(user.get("user_id").toString()));
            login(name);
            assertEquals(user, object(parsed(get("/api/openservice/me", null)).get("result")));
            new Store(environment);
            assertEquals(
                    user, object(parsed(get("/api/openservice/me", auth(name))).get("result")));
            assertEquals(400, get("/api/openservice/me?name=admin", auth(name)).statusCode());
        }
        assertEquals(405, json("POST", "/api/openservice/me", map(), auth("admin")).statusCode());
        // An old session must not become a newly created account with the same login name.
        login("IdentityAgent");
        try (var db = store.open()) {
            db.update("DELETE FROM os_user WHERE name='IdentityAgent'");
            Service.createAccount(
                    db, map("name", "IdentityAgent", "password", PASSWORD, "role", "admin"));
            db.connection.commit();
        }
        assertEquals(401, get("/api/openservice/me", null).statusCode());
        assertEquals(200, get("/api/openservice/me", auth("IdentityAgent")).statusCode());
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
                        "INSERT INTO os_user(name,password,role,user_id) VALUES(?,?,?,?)",
                        String.format(Locale.ROOT, "evaluator%02d", i),
                        hash,
                        "reader",
                        id());
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
            db.update("DROP TABLE os_migration"); // Simulate the pre-migration installation.
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

    String encoded(String query) {
        return URLEncoder.encode(query, StandardCharsets.UTF_8);
    }

    List<Map<String, Object>> tableQuery(String query, String extra) throws Exception {
        var response =
                get(
                        "/api/now/table/incident?sysparm_query=" + encoded(query) + extra,
                        auth("admin"));
        assertEquals(200, response.statusCode(), html(response));
        return objects(parsed(response).get("result"));
    }

    void countQuery(String query, long count) throws Exception {
        var response =
                get(
                        "/api/now/stats/incident?sysparm_count=true&sysparm_query="
                                + encoded(query),
                        auth("admin"));
        assertEquals(200, response.statusCode(), html(response));
        assertEquals(
                Long.toString(count),
                object(object(parsed(response).get("result")).get("stats")).get("count"));
    }

    @Test
    void emptyPredicatesDistinguishMissingNullEmptyAndWhitespace() throws Exception {
        var ids = new ArrayList<String>();
        for (int i = 0; i < 5; i++) {
            var created =
                    json(
                            "POST",
                            "/api/now/table/incident",
                            map(
                                    "short_description",
                                    i + " empty query",
                                    "assigned_to",
                                    List.of("missing", "null", "", " ", "alex").get(i)),
                            auth("admin"));
            assertEquals(201, created.statusCode());
            ids.add(object(parsed(created).get("result")).get("sys_id").toString());
        }
        try (var db = store.open()) {
            var missing = Service.record(db, "incident", ids.get(0));
            object(missing.get("data")).remove("assigned_to");
            Service.save(db, missing);
            var nil = Service.record(db, "incident", ids.get(1));
            object(nil.get("data")).put("assigned_to", null);
            Service.save(db, nil);
            db.connection.commit();
        }
        assertEquals(
                ids.subList(0, 3),
                tableQuery("assigned_toISEMPTY^ORDERBYshort_description", "").stream()
                        .map(r -> r.get("sys_id"))
                        .toList());
        assertEquals(
                ids.subList(3, 5),
                tableQuery("assigned_toISNOTEMPTY^ORDERBYshort_description", "").stream()
                        .map(r -> r.get("sys_id"))
                        .toList());
        assertEquals(
                ids.get(1),
                tableQuery(
                                "assigned_toISEMPTY^priority=3^ORDERBYshort_description",
                                "&sysparm_limit=1&sysparm_offset=1")
                        .getFirst()
                        .get("sys_id"));
        countQuery("assigned_toISEMPTY", 3);
        countQuery("assigned_toISNOTEMPTY", 2);
        countQuery("assigned_toISEMPTY^assigned_toISNOTEMPTY", 0);
        for (String query :
                List.of(
                        "assigned_toISEMPTYvalue",
                        "assigned_toISNOTEMPTYvalue",
                        "missingISEMPTY",
                        "assigned_toISEMPTY^ORpriority=1"))
            for (String endpoint :
                    List.of(
                            "/api/now/table/incident?",
                            "/api/now/stats/incident?sysparm_count=true&"))
                assertEquals(
                        400,
                        get(endpoint + "sysparm_query=" + encoded(query), auth("admin"))
                                .statusCode(),
                        query);
    }

    List<String> textQueryFixtures() throws Exception {
        var ids = new ArrayList<String>();
        var values =
                List.of(
                        "alpha-tail",
                        "tail-middle",
                        "prefix%_\\end",
                        "prefixXX\\end",
                        "x' OR '1'='1",
                        "",
                        "prefix-é尾",
                        "placeholder");
        for (int i = 0; i < values.size(); i++) {
            var created =
                    json(
                            "POST",
                            "/api/now/table/incident",
                            map(
                                    "short_description",
                                    i + " text query",
                                    "assigned_to",
                                    values.get(i)),
                            auth("admin"));
            assertEquals(201, created.statusCode());
            ids.add(object(parsed(created).get("result")).get("sys_id").toString());
        }
        try (var db = store.open()) {
            var nil = Service.record(db, "incident", ids.get(7));
            object(nil.get("data")).put("assigned_to", null);
            Service.save(db, nil);
            db.connection.commit();
        }
        return ids;
    }

    @Test
    void endsWithMatchesLiteralSuffixesBeforePagingAndCounts() throws Exception {
        var ids = textQueryFixtures();
        for (var pair : Map.of("tail", 0, "%_\\end", 2, "x' OR '1'='1", 4, "é尾", 6).entrySet()) {
            String query = "assigned_toENDSWITH" + pair.getKey();
            assertEquals(
                    List.of(ids.get(pair.getValue())),
                    tableQuery(query, "").stream().map(r -> r.get("sys_id")).toList());
            countQuery(query, 1);
        }
        assertEquals(
                List.of(ids.get(0)),
                tableQuery(
                                "assigned_toENDSWITHtail^priority=3^ORDERBYshort_description",
                                "&sysparm_limit=1")
                        .stream()
                        .map(r -> r.get("sys_id"))
                        .toList());
        assertEquals(List.of(), tableQuery("assigned_toENDSWITHtail", "&sysparm_offset=1"));
        assertEquals(
                ids.subList(0, 7),
                tableQuery("assigned_toENDSWITH^ORDERBYshort_description", "").stream()
                        .map(r -> r.get("sys_id"))
                        .toList());
        countQuery("assigned_toENDSWITH", 7);
        assertEquals(
                ids.getFirst(),
                tableQuery("sys_idENDSWITH" + ids.getFirst(), "").getFirst().get("sys_id"));
        for (String query : List.of("unknownENDSWITHtail", "assigned_toENDWITHtail"))
            for (String endpoint :
                    List.of(
                            "/api/now/table/incident?",
                            "/api/now/stats/incident?sysparm_count=true&"))
                assertEquals(
                        400,
                        get(endpoint + "sysparm_query=" + encoded(query), auth("admin"))
                                .statusCode());
    }

    @Test
    void notLikeExcludesLiteralSubstringsWithoutMatchingNulls() throws Exception {
        var ids = textQueryFixtures();
        for (var entry :
                Map.of(
                                "tail",
                                List.of(0, 1),
                                "%_\\end",
                                List.of(2),
                                "x' OR '1'='1",
                                List.of(4),
                                "é",
                                List.of(6),
                                "absent",
                                List.<Integer>of())
                        .entrySet()) {
            var expected = new ArrayList<String>();
            for (int i = 0; i < 7; i++) if (!entry.getValue().contains(i)) expected.add(ids.get(i));
            String query = "assigned_toNOT LIKE" + entry.getKey() + "^ORDERBYshort_description";
            assertEquals(
                    expected, tableQuery(query, "").stream().map(r -> r.get("sys_id")).toList());
            countQuery(query, expected.size());
        }
        String combined = "assigned_toNOT LIKEtail^assigned_toISNOTEMPTY^ORDERBYshort_description";
        assertEquals(
                List.of(ids.get(3), ids.get(4)),
                tableQuery(combined, "&sysparm_limit=2&sysparm_offset=1").stream()
                        .map(r -> r.get("sys_id"))
                        .toList());
        countQuery(combined, 4);
        assertEquals(List.of(), tableQuery("assigned_toNOT LIKE", ""));
        countQuery("assigned_toNOT LIKE", 0);
        for (String query : List.of("unknownNOT LIKEtail", "assigned_toNOTLIKEtail"))
            for (String endpoint :
                    List.of(
                            "/api/now/table/incident?",
                            "/api/now/stats/incident?sysparm_count=true&"))
                assertEquals(
                        400,
                        get(endpoint + "sysparm_query=" + encoded(query), auth("admin"))
                                .statusCode());
    }

    @Test
    void paginationHeaderSuppressionPreservesRecordsAndCount() throws Exception {
        var ids =
                List.of(
                        add("incident").get("sys_id"),
                        add("incident").get("sys_id"),
                        add("incident").get("sys_id"));
        for (String prefix :
                List.of("/api/now/table/", "/api/now/v1/table/", "/api/now/v2/table/")) {
            String path =
                    prefix + "incident?sysparm_limit=1&sysparm_offset=1&sysparm_fields=sys_id";
            var suppressed = get(path + "&sysparm_suppress_pagination_header=true", auth("admin"));
            assertEquals(200, suppressed.statusCode(), html(suppressed));
            assertTrue(suppressed.headers().firstValue("Link").isEmpty());
            assertEquals("3", suppressed.headers().firstValue("X-Total-Count").orElseThrow());
            assertEquals(List.of(map("sys_id", ids.get(1))), parsed(suppressed).get("result"));
            var explicit = get(path + "&sysparm_suppress_pagination_header=false", auth("admin"));
            var implicit = get(path, auth("admin"));
            assertEquals(parsed(implicit), parsed(explicit));
            assertEquals(parsed(implicit), parsed(suppressed));
            for (String relation : List.of("first", "last", "prev", "next"))
                assertTrue(
                        explicit.headers()
                                .firstValue("Link")
                                .orElseThrow()
                                .contains("rel=\"" + relation + "\""));
            assertTrue(
                    explicit.headers()
                            .firstValue("Link")
                            .orElseThrow()
                            .contains("sysparm_suppress_pagination_header=false"));
        }
        String path = "/api/now/table/incident?sysparm_suppress_pagination_header=";
        for (String invalid : List.of("", "True", "1", "no", "%20true"))
            assertEquals(400, get(path + invalid, auth("admin")).statusCode());
        assertEquals(
                400,
                get(path + "true&sysparm_suppress_pagination_header=false", auth("admin"))
                        .statusCode());
        var empty = get(path + "true&sysparm_query=sys_id%3Dmissing", auth("admin"));
        assertEquals(200, empty.statusCode());
        assertEquals(List.of(), parsed(empty).get("result"));
        assertTrue(empty.headers().firstValue("Link").isEmpty());
        assertEquals("0", empty.headers().firstValue("X-Total-Count").orElseThrow());
        assertEquals(
                400,
                get(
                                "/api/now/table/incident/"
                                        + ids.get(0)
                                        + "?sysparm_suppress_pagination_header=true",
                                auth("admin"))
                        .statusCode());
    }

    @Test
    void noCountPaginationUsesLookaheadWithoutInventingTotals() throws Exception {
        var ids =
                List.of(
                        add("incident").get("sys_id"),
                        add("incident").get("sys_id"),
                        add("incident").get("sys_id"));
        for (String prefix :
                List.of("/api/now/table/", "/api/now/v1/table/", "/api/now/v2/table/")) {
            for (int offset = 0; offset < 3; offset++) {
                String path =
                        prefix
                                + "incident?sysparm_no_count=true&sysparm_limit=1&sysparm_offset="
                                + offset
                                + "&sysparm_fields=sys_id";
                var response = get(path, auth("admin"));
                assertEquals(200, response.statusCode(), html(response));
                assertEquals(
                        List.of(map("sys_id", ids.get(offset))), parsed(response).get("result"));
                assertTrue(response.headers().firstValue("X-Total-Count").isEmpty());
                String link = response.headers().firstValue("Link").orElseThrow();
                assertFalse(link.contains("rel=\"last\""));
                assertEquals(offset < 2, link.contains("rel=\"next\""));
                assertEquals(offset > 0, link.contains("rel=\"prev\""));
                if (offset < 2) {
                    var next = Pattern.compile("<([^>]+)>; rel=\"next\"").matcher(link);
                    assertTrue(next.find());
                    URI uri = URI.create(next.group(1));
                    assertTrue(uri.getQuery().contains("sysparm_no_count=true"));
                    assertEquals(
                            List.of(map("sys_id", ids.get(offset + 1))),
                            parsed(get(uri.getPath() + "?" + uri.getRawQuery(), auth("admin")))
                                    .get("result"));
                }
            }
        }
        var normal =
                get(
                        "/api/now/table/incident?sysparm_no_count=false&sysparm_limit=1",
                        auth("admin"));
        assertEquals("3", normal.headers().firstValue("X-Total-Count").orElseThrow());
        assertTrue(normal.headers().firstValue("Link").orElseThrow().contains("rel=\"last\""));
        var suppressed =
                get(
                        "/api/now/table/incident?sysparm_no_count=true&sysparm_suppress_pagination_header=true",
                        auth("admin"));
        assertEquals(3, objects(parsed(suppressed).get("result")).size());
        assertTrue(suppressed.headers().firstValue("Link").isEmpty());
        assertTrue(suppressed.headers().firstValue("X-Total-Count").isEmpty());
        for (String suffix :
                List.of("&sysparm_query=sys_id%3Dmissing", "&sysparm_offset=2147483647")) {
            var empty =
                    get("/api/now/table/incident?sysparm_no_count=true" + suffix, auth("admin"));
            assertEquals(200, empty.statusCode());
            assertEquals(List.of(), parsed(empty).get("result"));
            assertFalse(empty.headers().firstValue("Link").orElseThrow().contains("rel=\"next\""));
        }
        for (String invalid : List.of("", "True", "1", "false%20"))
            assertEquals(
                    400,
                    get("/api/now/table/incident?sysparm_no_count=" + invalid, auth("admin"))
                            .statusCode());
        assertEquals(
                400,
                get(
                                "/api/now/table/incident?sysparm_no_count=true&sysparm_no_count=false",
                                auth("admin"))
                        .statusCode());
        assertEquals(
                400,
                get(
                                "/api/now/table/incident/" + ids.get(0) + "?sysparm_no_count=true",
                                auth("admin"))
                        .statusCode());
    }

    List<String> issuedSql(java.sql.Connection connection) {
        return org.mockito.Mockito.mockingDetails(connection).getInvocations().stream()
                .filter(call -> call.getMethod().getName().equals("prepareStatement"))
                .map(call -> call.getArgument(0).toString())
                .toList();
    }

    @Test
    void noCountActuallySkipsSqlCountOnTheRealDatabase() throws Exception {
        add("incident");
        add("incident");
        try (var db = store.open()) {
            var connection = org.mockito.Mockito.spy(db.connection);
            var tracked = new Store.Db(connection, db.postgres);
            var page =
                    Service.page(
                            tracked,
                            "incident",
                            Map.of("sysparm_no_count", "true", "sysparm_limit", "1"),
                            "");
            assertEquals(1, page.rows().size());
            assertEquals(-1, page.total());
            assertTrue(
                    issuedSql(connection).stream()
                            .anyMatch(sql -> sql.startsWith("SELECT * FROM os_record")));
            assertFalse(
                    issuedSql(connection).stream()
                            .anyMatch(sql -> sql.toUpperCase(Locale.ROOT).contains("COUNT(")));
            org.mockito.Mockito.clearInvocations(connection);
            assertEquals(
                    2,
                    Service.page(tracked, "incident", Map.of("sysparm_no_count", "false"), "")
                            .total());
            assertTrue(
                    issuedSql(connection).stream()
                            .anyMatch(sql -> sql.toUpperCase(Locale.ROOT).contains("COUNT(")));
        }
    }

    List<Object> attachmentIds(String query, String extra) throws Exception {
        var response =
                get("/api/now/attachment?sysparm_query=" + encoded(query) + extra, auth("admin"));
        assertEquals(200, response.statusCode(), html(response));
        return objects(parsed(response).get("result")).stream()
                .map(row -> row.get("sys_id"))
                .toList();
    }

    @Test
    void attachmentOrderingIsStableFilteredAndAppliedBeforePaging() throws Exception {
        String incident = add("incident").get("sys_id").toString(),
                problem = add("problem").get("sys_id").toString();
        var files = new ArrayList<Map<String, Object>>();
        for (String[] item :
                List.of(
                        new String[] {incident, "incident", "zeta.txt"},
                        new String[] {incident, "incident", "beta.txt"},
                        new String[] {incident, "incident", "beta.txt"},
                        new String[] {problem, "problem", "middle.txt"})) {
            var response =
                    request(
                            "POST",
                            binaryPath(item[0], item[2])
                                    .replace("table_name=incident", "table_name=" + item[1]),
                            new byte[] {1},
                            "text/plain",
                            auth("admin"));
            assertEquals(201, response.statusCode());
            files.add(object(parsed(response).get("result")));
        }
        Object z = files.get(0).get("sys_id"), m = files.get(3).get("sys_id");
        var beta =
                List.of(files.get(1).get("sys_id"), files.get(2).get("sys_id")).stream()
                        .sorted(Comparator.comparing(Object::toString))
                        .toList();
        assertEquals(
                List.of(beta.get(0), beta.get(1), m, z), attachmentIds("ORDERBYfile_name", ""));
        assertEquals(
                List.of(z, m, beta.get(0), beta.get(1)), attachmentIds("ORDERBYDESCfile_name", ""));
        assertEquals(
                List.of(z, beta.get(0), beta.get(1), m),
                attachmentIds("ORDERBYtable_name^ORDERBYDESCfile_name", ""));
        assertEquals(
                List.of(m, beta.get(0), beta.get(1), z),
                attachmentIds("ORDERBYDESCtable_name^ORDERBYfile_name", ""));
        assertEquals(
                List.of(beta.get(1)),
                attachmentIds(
                        "ORDERBYfile_name^file_name=beta.txt",
                        "&sysparm_limit=1&sysparm_offset=1"));
        assertEquals(
                List.of(z, beta.get(0), beta.get(1)),
                attachmentIds("table_name=incident^ORDERBYDESCfile_name", ""));
        assertEquals(
                files.stream()
                        .map(row -> row.get("sys_id"))
                        .sorted(Comparator.comparing(Object::toString).reversed())
                        .toList(),
                attachmentIds("ORDERBYDESCsys_id", ""));
        assertEquals(
                files.stream()
                        .sorted(
                                Comparator.comparing((Map<String, Object> row) ->
                                                row.get("table_sys_id").toString())
                                        .thenComparing(row -> row.get("sys_id").toString()))
                        .map(row -> row.get("sys_id"))
                        .toList(),
                attachmentIds("ORDERBYtable_sys_id", ""));
        assertEquals(List.of(), attachmentIds("ORDERBYfile_name", "&sysparm_offset=2147483647"));
        for (String query :
                List.of(
                        "ORDERBY",
                        "ORDERBYDESC",
                        "ORDERBYunknown",
                        "ORDERBYfile_name;DROP TABLE os_user",
                        "ORDERBYsize_bytes",
                        "ORDERBYfile_name^"))
            assertEquals(
                    400,
                    get("/api/now/attachment?sysparm_query=" + encoded(query), auth("admin"))
                            .statusCode(),
                    query);
        assertEquals(
                400,
                get(
                                "/api/now/attachment?sysparm_query=ORDERBYfile_name&sysparm_query=ORDERBYsys_id",
                                auth("admin"))
                        .statusCode());
        assertEquals(
                401, get("/api/now/attachment?sysparm_query=ORDERBYfile_name", null).statusCode());
        try (var db = store.open()) {
            assertEquals(4, db.count("SELECT COUNT(*) FROM os_attachment"));
            assertEquals(1, db.count("SELECT COUNT(*) FROM os_user"));
        }
    }
}
