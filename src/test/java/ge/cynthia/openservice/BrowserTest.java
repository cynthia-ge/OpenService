package ge.cynthia.openservice;

import static ge.cynthia.openservice.Values.*;

import static org.junit.jupiter.api.Assertions.*;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        useMainMethod = SpringBootTest.UseMainMethod.ALWAYS)
class BrowserTest {
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        ContractSupport.properties(registry);
    }

    @Autowired Environment environment;
    @Autowired Store store;

    String base() {
        return "http://localhost:" + environment.getProperty("local.server.port");
    }

    void login(Page page, String name) {
        page.navigate(base() + "/login");
        page.getByLabel("Username", new Page.GetByLabelOptions().setExact(true)).fill(name);
        page.getByLabel("Password", new Page.GetByLabelOptions().setExact(true))
                .fill(WorkflowSupport.PASSWORD);
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Enter your workspace"))
                .click();
        page.getByRole(
                        AriaRole.HEADING,
                        new Page.GetByRoleOptions().setName("Make room for better work."))
                .waitFor();
    }

    @Test
    void connectedMigrationWizardUsesPersistedCheckpointsAndReviewedCutover() throws Exception {
        TransferBrowserAcceptance.run(this);
    }

    @Test
    void assignmentHandoverRequiresFreshReviewAfterAnotherTabEditsWork() throws Exception {
        String table = "u_stale_" + id(),
                name = "Source" + id().substring(0, 8),
                replacement = "Target" + id().substring(0, 8),
                user,
                first,
                second;
        try (var db = store.open()) {
            for (String account : List.of(name, replacement))
                Service.createAccount(
                        db,
                        map(
                                "name",
                                account,
                                "role",
                                "agent",
                                "password",
                                WorkflowSupport.PASSWORD));
            user = RecordAccess.owner(db, name);
            String group = AssignmentAcceptance.seed(db, table, user, 2);
            GroupMembers.change(
                    db,
                    group,
                    RecordAccess.owner(db, "admin"),
                    map("user_id", RecordAccess.owner(db, replacement)),
                    true);
            var records = objects(Assignments.page(db, user, Map.of()).get("records"));
            first = records.getFirst().get("sys_id").toString();
            second = records.getLast().get("sys_id").toString();
            db.connection.commit();
        }
        try (var playwright = Playwright.create();
                var browser = playwright.chromium().launch();
                var context = browser.newContext()) {
            var page = context.newPage();
            login(page, "admin");
            String directory = base() + "/admin/accounts/" + user + "/assignments";
            page.navigate(directory);
            page.locator("input[name=record_ids]").nth(0).check();
            page.locator("input[name=record_ids]").nth(1).check();
            page.getByLabel("Replacement username").fill(replacement);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Preview handover"))
                    .click();
            assertEquals("0", page.locator("input[name=revision_" + second + "]").inputValue());
            var editor = context.newPage();
            editor.navigate(base() + "/record/" + table + "_b/" + second);
            editor.locator("input[name=short_description]").fill("Edited from another tab");
            editor.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Save changes"))
                    .click();
            assertEquals(
                    "Edited from another tab",
                    editor.locator("input[name=short_description]").inputValue());
            var response =
                    page.waitForResponse(
                            r -> r.url().endsWith("/assignments/transfer"),
                            () ->
                                    page.getByRole(
                                                    AriaRole.BUTTON,
                                                    new Page.GetByRoleOptions()
                                                            .setName(
                                                                    "Transfer selected"
                                                                            + " assignments"))
                                            .click());
            assertEquals(409, response.status());
            assertTrue(
                    page.locator("main")
                            .innerText()
                            .contains("Record changed; reload before saving"));
            try (var db = store.open()) {
                assertEquals(
                        user, object(Service.record(db, table, first).get("data")).get("owner"));
                assertEquals("0", Service.record(db, table, first).get("revision").toString());
            }
            page.navigate(directory);
            assertEquals("1", page.locator("input[name=revision_" + second + "]").inputValue());
            page.locator("input[name=record_ids]").nth(0).check();
            page.locator("input[name=record_ids]").nth(1).check();
            page.getByLabel("Replacement username").fill(replacement);
            page.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Transfer selected assignments"))
                    .click();
            assertTrue(page.locator("main").innerText().contains("0 configured assignments"));
        } finally {
            try (var db = store.open()) {
                db.update("DELETE FROM os_record WHERE \"table\" LIKE ?", table + "%");
                db.update("DELETE FROM os_schema WHERE name LIKE ?", table + "%");
                db.connection.commit();
            }
        }
    }

    @Test
    void administratorBrowsesInactiveAccountAssignments() throws Exception {
        String table = "u_assignment_" + id(),
                name = "AssignedUser" + id().substring(0, 8),
                replacement = "Replacement" + id().substring(0, 8),
                user;
        try (var db = store.open()) {
            Service.createAccount(
                    db, map("name", name, "role", "agent", "password", WorkflowSupport.PASSWORD));
            user = RecordAccess.owner(db, name);
            Service.createAccount(
                    db,
                    map(
                            "name",
                            replacement,
                            "role",
                            "agent",
                            "password",
                            WorkflowSupport.PASSWORD));
            String group = AssignmentAcceptance.seed(db, table, user, 26);
            GroupMembers.change(
                    db,
                    group,
                    RecordAccess.owner(db, "admin"),
                    map("user_id", RecordAccess.owner(db, replacement)),
                    true);
            Service.changeActive(db, RecordAccess.owner(db, "admin"), user, map("active", false));
            db.connection.commit();
        }
        try (var playwright = Playwright.create();
                var browser = playwright.chromium().launch();
                var context = browser.newContext()) {
            var page = context.newPage();
            login(page, "admin");
            page.navigate(base() + "/admin/accounts/" + user);
            page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("View assigned work"))
                    .click();
            assertTrue(page.locator("main").innerText().contains("26 configured assignments"));
            assertEquals(25, page.locator("tbody tr").count());
            page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Next assignments"))
                    .click();
            assertEquals(1, page.locator("tbody tr").count());
            page.getByRole(
                            AriaRole.LINK,
                            new Page.GetByRoleOptions().setName("Previous assignments"))
                    .click();
            page.locator("input[name=record_ids]").nth(0).check();
            page.locator("input[name=record_ids]").nth(1).check();
            page.getByLabel("Replacement username").fill(replacement);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Preview handover"))
                    .click();
            assertTrue(
                    page.locator("main")
                            .innerText()
                            .contains("Preview passed. No assignments changed."));
            assertTrue(page.locator("main").innerText().contains("26 configured assignments"));
            assertEquals(2, page.locator("input[name=record_ids]:checked").count());
            assertEquals(replacement, page.getByLabel("Replacement username").inputValue());
            page.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Transfer selected assignments"))
                    .click();
            assertTrue(page.locator("main").innerText().contains("24 configured assignments"));
            page.locator("tbody a").first().click();
            assertTrue(
                    page.locator("input[name=short_description]")
                            .inputValue()
                            .startsWith("Assigned task"));
        } finally {
            try (var db = store.open()) {
                db.update("DELETE FROM os_record WHERE \"table\" LIKE ?", table + "%");
                db.update("DELETE FROM os_schema WHERE name LIKE ?", table + "%");
                db.connection.commit();
            }
        }
    }

    @Test
    void managedTablesHideForbiddenMutationControls() throws Exception {
        String table = "u_managed_" + id(),
                agentName = "ManagedAgent" + id().substring(0, 8),
                record;
        try (var db = store.open()) {
            Service.createAccount(
                    db,
                    map("name", agentName, "role", "agent", "password", WorkflowSupport.PASSWORD));
            db.viewerRole = "admin";
            db.viewerId = RecordAccess.owner(db, "admin");
            var definition = ConfigurationAcceptance.definition();
            definition.put(
                    "record_roles",
                    map(
                            "create",
                            List.of("admin"),
                            "update",
                            List.of("admin"),
                            "delete",
                            List.of()));
            Configuration.install(
                    db, ConfigurationAcceptance.bundle(table, definition), db.viewerId, false);
            record =
                    Service.create(db, table, map("short_description", "Managed record"), "admin")
                            .get("sys_id")
                            .toString();
            db.connection.commit();
        }
        try (var playwright = Playwright.create();
                var browser = playwright.chromium().launch();
                var context = browser.newContext()) {
            var page = context.newPage();
            login(page, agentName);
            page.navigate(base() + "/workspace/" + table);
            assertEquals(
                    0,
                    page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("New record"))
                            .count());
            page.navigate(base() + "/record/" + table + "/" + record);
            assertNotNull(page.locator("input[name=short_description]").getAttribute("readonly"));
            assertEquals(
                    0,
                    page.getByRole(
                                    AriaRole.BUTTON,
                                    new Page.GetByRoleOptions().setName("Save changes"))
                            .count());
            assertEquals(
                    1,
                    page.getByRole(
                                    AriaRole.BUTTON,
                                    new Page.GetByRoleOptions().setName("Post comment"))
                            .count());
            page.navigate(base() + "/record/" + table + "/new");
            assertEquals(
                    0,
                    page.getByRole(
                                    AriaRole.BUTTON,
                                    new Page.GetByRoleOptions().setName("Create record"))
                            .count());
        } finally {
            try (var db = store.open()) {
                db.update("DELETE FROM os_record WHERE \"table\"=?", table);
                db.update("DELETE FROM os_schema WHERE name=?", table);
                db.connection.commit();
            }
        }
    }

    @Test
    void administratorPreviewsInstallsAndUsesAReusableTypedApplication() throws Exception {
        String table = "u_browser_" + id();
        try (var playwright = Playwright.create();
                var browser = playwright.chromium().launch();
                var context = browser.newContext()) {
            var page = context.newPage();
            login(page, "admin");
            page.getByRole(
                            AriaRole.LINK,
                            new Page.GetByRoleOptions().setName("Configuration").setExact(true))
                    .click();
            var definition = ConfigurationAcceptance.definition();
            object(object(definition.get("field_rules")).get("quantity"))
                    .put("write_roles", List.of("admin"));
            page.getByLabel("Configuration package JSON")
                    .fill(
                            JSON.writeValueAsString(
                                    ConfigurationAcceptance.bundle(table, definition)));
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Preview package"))
                    .click();
            assertTrue(
                    page.locator("main").innerText().contains("Preview passed. No changes saved."));
            try (var db = store.open()) {
                assertNull(db.one("SELECT name FROM os_schema WHERE name=?", table));
            }
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Install package"))
                    .click();
            assertTrue(page.locator("main").innerText().contains("Configuration installed."));
            page.navigate(base() + "/record/" + table + "/new");
            page.locator("input[name=short_description]").fill("Configured in browser");
            page.locator("input[name=quantity]").fill("not a number");
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create record →"))
                    .click();
            assertTrue(page.locator("main").innerText().contains("invalid integer"));
            page.goBack();
            page.locator("input[name=quantity]").fill("7");
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create record →"))
                    .click();
            assertEquals("7", page.locator("input[name=quantity]").inputValue());
            page.reload();
            assertEquals(
                    "Configured in browser",
                    page.locator("input[name=short_description]").inputValue());
            String recordLocation = page.url();
            page.navigate(base() + "/workspace/" + table);
            page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Analytics ↗"))
                    .click();
            page.getByLabel("Measure", new Page.GetByLabelOptions().setExact(true))
                    .selectOption("quantity");
            page.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Calculate metrics"))
                    .click();
            var cells = page.locator("tbody tr").first().locator("td");
            assertEquals(
                    List.of("All matching records", "1", "1", "7", "7", "7", "7"),
                    cells.allTextContents());
            String agentName = "FieldAgent" + id().substring(0, 8);
            try (var db = store.open()) {
                Service.createAccount(
                        db,
                        map(
                                "name",
                                agentName,
                                "role",
                                "agent",
                                "password",
                                WorkflowSupport.PASSWORD));
                db.connection.commit();
            }
            try (var agentContext = browser.newContext()) {
                var agent = agentContext.newPage();
                login(agent, agentName);
                agent.navigate(recordLocation);
                assertNotNull(agent.locator("input[name=quantity]").getAttribute("readonly"));
                agent.locator("input[name=short_description]").fill("Permitted field edit");
                agent.getByRole(
                                AriaRole.BUTTON,
                                new Page.GetByRoleOptions().setName("Save changes →"))
                        .click();
                agent.reload();
                assertEquals("7", agent.locator("input[name=quantity]").inputValue());
                assertEquals(
                        "Permitted field edit",
                        agent.locator("input[name=short_description]").inputValue());
            }
        } finally {
            try (var db = store.open()) {
                db.update("DELETE FROM os_record WHERE \"table\"=?", table);
                db.update("DELETE FROM os_schema WHERE name=?", table);
                db.connection.commit();
            }
        }
    }

    @Test
    void administratorHandsPrivateWorkToAnotherSignedInAccount() throws Exception {
        String suffix = id().substring(0, 8),
                oldName = "OldBrowser" + suffix,
                newName = "NewBrowser" + suffix,
                user,
                record;
        try (var db = store.open()) {
            for (String name : List.of(oldName, newName))
                Service.createAccount(
                        db,
                        map("name", name, "role", "reader", "password", WorkflowSupport.PASSWORD));
            user = RecordAccess.owner(db, oldName);
            var item =
                    Service.create(
                            db,
                            "incident",
                            map("short_description", "Private handover browser"),
                            "admin");
            record = item.get("sys_id").toString();
            RecordAccess.change(
                    db,
                    item,
                    RecordAccess.owner(db, "admin"),
                    map("visibility", "private", "owner_id", user));
            db.connection.commit();
        }
        try (var playwright = Playwright.create();
                var browser = playwright.chromium().launch();
                var adminContext = browser.newContext();
                var oldContext = browser.newContext();
                var newContext = browser.newContext()) {
            var admin = adminContext.newPage();
            var old = oldContext.newPage();
            var next = newContext.newPage();
            login(admin, "admin");
            login(old, oldName);
            login(next, newName);
            assertEquals(200, old.navigate(base() + "/record/incident/" + record).status());
            assertEquals(404, next.navigate(base() + "/record/incident/" + record).status());
            admin.navigate(base() + "/admin/accounts/" + user);
            assertTrue(admin.locator("main").innerText().contains("1 live private records"));
            admin.getByLabel("New private owner username").fill(newName);
            admin.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions()
                                    .setName("Transfer all live private records"))
                    .click();
            assertTrue(admin.locator("main").innerText().contains("0 live private records"));
            assertEquals(404, old.navigate(base() + "/record/incident/" + record).status());
            assertEquals(200, next.navigate(base() + "/record/incident/" + record).status());
            admin.setViewportSize(390, 844);
            assertTrue(
                    (Boolean) admin.evaluate("document.documentElement.scrollWidth<=innerWidth"));
        }
    }

    @Test
    void administratorOffboardsGroupMembershipsFromAnAccount() throws Exception {
        String name = "OffboardBrowser" + id().substring(0, 8), user, record;
        try (var db = store.open()) {
            Service.createAccount(
                    db, map("name", name, "role", "reader", "password", WorkflowSupport.PASSWORD));
            user = RecordAccess.owner(db, name);
            String actor = RecordAccess.owner(db, "admin");
            String group =
                    Service.create(
                                    db,
                                    "sys_user_group",
                                    map("short_description", "Offboarding browser group"),
                                    "admin")
                            .get("sys_id")
                            .toString();
            GroupMembers.change(db, group, actor, map("user_id", user), true);
            var item =
                    Service.create(
                            db,
                            "incident",
                            map("short_description", "Offboarding browser record"),
                            "admin");
            record = item.get("sys_id").toString();
            RecordAccess.change(db, item, actor, map("visibility", "group", "group_id", group));
            db.connection.commit();
        }
        try (var playwright = Playwright.create();
                var browser = playwright.chromium().launch();
                var adminContext = browser.newContext();
                var memberContext = browser.newContext()) {
            var admin = adminContext.newPage();
            var member = memberContext.newPage();
            login(admin, "admin");
            login(member, name);
            assertEquals(200, member.navigate(base() + "/record/incident/" + record).status());
            admin.navigate(base() + "/admin/accounts/" + user);
            admin.getByRole(
                            AriaRole.LINK,
                            new Page.GetByRoleOptions().setName("Offboarding browser group"))
                    .waitFor();
            admin.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Remove all group memberships"))
                    .click();
            admin.getByText("No group memberships.", new Page.GetByTextOptions().setExact(true))
                    .waitFor();
            assertEquals(404, member.navigate(base() + "/record/incident/" + record).status());
            admin.setViewportSize(390, 844);
            assertTrue(
                    (Boolean) admin.evaluate("document.documentElement.scrollWidth<=innerWidth"));
        }
    }

    @Test
    void administratorSeesAndRevokesAnInvalidatedInvitation() throws Exception {
        String name = "UnavailableBrowser" + id().substring(0, 8);
        try (var db = store.open()) {
            String admin =
                    db.one("SELECT user_id FROM os_user WHERE name='admin'")
                            .get("user_id")
                            .toString();
            var invite = Invitations.issue(db, admin, map("name", name, "role", "reader"));
            db.update(
                    "UPDATE os_invitation SET issuer_revision=-1 WHERE invite_id=?",
                    invite.get("invite_id"));
            db.connection.commit();
        }
        try (var playwright = Playwright.create();
                var browser = playwright.chromium().launch();
                var context = browser.newContext()) {
            var page = context.newPage();
            login(page, "admin");
            page.getByRole(
                            AriaRole.LINK,
                            new Page.GetByRoleOptions().setName("Invitations").setExact(true))
                    .click();
            var row =
                    page.getByRole(AriaRole.ROW)
                            .filter(new Locator.FilterOptions().setHasText(name));
            assertTrue(row.innerText().contains("invalidated"));
            row.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Revoke"))
                    .click();
            assertTrue(
                    page.getByRole(AriaRole.ROW)
                            .filter(new Locator.FilterOptions().setHasText(name))
                            .innerText()
                            .contains("revoked"));
            page.setViewportSize(390, 844);
            assertTrue((Boolean) page.evaluate("document.documentElement.scrollWidth<=innerWidth"));
        }
    }

    @Test
    void administratorBrowsesAndDownloadsOlderJournalPages() throws Exception {
        try (var db = store.open()) {
            String admin =
                    db.one("SELECT user_id FROM os_user WHERE name='admin'")
                            .get("user_id")
                            .toString();
            for (int i = 0; i < 105; i++) {
                SecurityEvents.record(
                        db,
                        admin,
                        admin,
                        "account.browser_history",
                        map("marker", String.format(Locale.ROOT, "history%03d", i)));
                AuthenticationEvents.record(
                        db,
                        "admin",
                        Passwords.tokenHash(String.format(Locale.ROOT, "history%03d", i)),
                        "browser",
                        "failed");
            }
            db.connection.commit();
        }
        try (var playwright = Playwright.create();
                var browser = playwright.chromium().launch();
                var context = browser.newContext()) {
            var page = context.newPage();
            login(page, "admin");
            for (String kind : List.of("security-events", "authentication-events")) {
                page.navigate(base() + "/admin/" + kind);
                page.getByRole(
                                AriaRole.LINK,
                                new Page.GetByRoleOptions().setName("Older events").setExact(true))
                        .click();
                var download =
                        page.waitForDownload(() ->
                                page.getByRole(
                                                AriaRole.LINK,
                                                new Page.GetByRoleOptions()
                                                        .setName("Download this page"))
                                        .click());
                assertEquals(kind + ".json", download.suggestedFilename());
                String content = Files.readString(download.path());
                assertTrue(
                        content.contains(
                                kind.equals("security-events")
                                        ? "history000"
                                        : Passwords.tokenHash("history000")));
                assertFalse(
                        content.contains(
                                kind.equals("security-events")
                                        ? "history104"
                                        : Passwords.tokenHash("history104")));
                assertTrue(
                        objects(object(JSON.readValue(content, Object.class)).get("result")).size()
                                <= 100);
                page.setViewportSize(390, 844);
                assertTrue(
                        (Boolean)
                                page.evaluate("document.documentElement.scrollWidth<=innerWidth"));
            }
        }
    }

    @Test
    void administratorSearchesAccountsAndKeepsFiltersWhilePaging() throws Exception {
        String prefix = "FindBrowser" + id().substring(0, 8);
        try (var db = store.open()) {
            String password =
                    db.one("SELECT password FROM os_user WHERE name='admin'")
                            .get("password")
                            .toString();
            for (int i = 0; i < 30; i++)
                db.update(
                        "INSERT INTO os_user(name,password,role,user_id,active) VALUES(?,?,?,?,?)",
                        prefix + String.format(Locale.ROOT, "%02d", i),
                        password,
                        "reader",
                        id(),
                        1);
            db.connection.commit();
        }
        try (var playwright = Playwright.create();
                var browser = playwright.chromium().launch();
                var context = browser.newContext()) {
            var page = context.newPage();
            login(page, "admin");
            page.getByRole(
                            AriaRole.LINK,
                            new Page.GetByRoleOptions()
                                    .setName(java.util.regex.Pattern.compile("Accounts$")))
                    .click();
            page.getByLabel("Find username").fill(prefix);
            page.getByLabel("Filter role", new Page.GetByLabelOptions().setExact(true))
                    .selectOption("reader");
            page.getByLabel("Filter status", new Page.GetByLabelOptions().setExact(true))
                    .selectOption("active");
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Find accounts"))
                    .click();
            page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Next →")).click();
            assertTrue(page.url().contains("role=reader"));
            assertEquals(prefix, page.getByLabel("Find username").inputValue());
            assertEquals(
                    6,
                    page.getByRole(
                                    AriaRole.TABLE,
                                    new Page.GetByRoleOptions().setName("Workspace accounts"))
                            .getByRole(AriaRole.ROW)
                            .count());
            page.getByLabel("Find username").fill("nobody" + prefix);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Find accounts"))
                    .click();
            page.getByText("No accounts match these filters.").waitFor();
            page.setViewportSize(390, 844);
            assertTrue((Boolean) page.evaluate("document.documentElement.scrollWidth<=innerWidth"));
        }
    }

    @Test
    void administratorInvestigatesAFailedLoginInTheBrowser() throws Exception {
        String name = "JournalBrowser" + id().substring(0, 8);
        try (var db = store.open()) {
            Service.createAccount(
                    db, map("name", name, "role", "reader", "password", WorkflowSupport.PASSWORD));
            db.connection.commit();
        }
        try (var playwright = Playwright.create();
                var browser = playwright.chromium().launch();
                var context = browser.newContext()) {
            var page = context.newPage();
            page.navigate(base() + "/login");
            page.getByLabel("Username", new Page.GetByLabelOptions().setExact(true)).fill(name);
            page.getByLabel("Password", new Page.GetByLabelOptions().setExact(true))
                    .fill("wrong-password");
            page.waitForResponse(
                    rsp -> rsp.url().endsWith("/login") && rsp.status() == 401,
                    () ->
                            page.getByRole(
                                            AriaRole.BUTTON,
                                            new Page.GetByRoleOptions()
                                                    .setName("Enter your workspace"))
                                    .click());
            login(page, "admin");
            page.getByRole(
                            AriaRole.LINK,
                            new Page.GetByRoleOptions()
                                    .setName("Authentication events")
                                    .setExact(true))
                    .click();
            page.getByRole(
                            AriaRole.HEADING,
                            new Page.GetByRoleOptions().setName("Authentication events"))
                    .waitFor();
            assertTrue(
                    page.getByRole(AriaRole.ROW)
                            .filter(new Locator.FilterOptions().setHasText(name))
                            .innerText()
                            .contains("failed"));
            page.setViewportSize(390, 844);
            assertTrue((Boolean) page.evaluate("document.documentElement.scrollWidth<=innerWidth"));
        }
    }

    @Test
    void browserExplainsTemporaryLoginThrottlingAndAllowsLoginAfterExpiry() throws Exception {
        String name = "ThrottleBrowser" + id().substring(0, 8);
        try (var db = store.open()) {
            Service.createAccount(
                    db, map("name", name, "role", "reader", "password", WorkflowSupport.PASSWORD));
            db.connection.commit();
        }
        try (var playwright = Playwright.create();
                var browser = playwright.chromium().launch();
                var context = browser.newContext()) {
            var page = context.newPage();
            for (int i = 0; i < 6; i++) {
                page.navigate(base() + "/login");
                page.getByLabel("Username", new Page.GetByLabelOptions().setExact(true)).fill(name);
                page.getByLabel("Password", new Page.GetByLabelOptions().setExact(true))
                        .fill("incorrect");
                var response =
                        page.waitForResponse(
                                rsp ->
                                        rsp.url().endsWith("/login")
                                                && rsp.request().method().equals("POST"),
                                () ->
                                        page.getByRole(
                                                        AriaRole.BUTTON,
                                                        new Page.GetByRoleOptions()
                                                                .setName("Enter your workspace"))
                                                .click());
                assertEquals(i < 5 ? 401 : 429, response.status());
            }
            page.getByText(
                            "Too many sign-in attempts. Try again in five minutes.",
                            new Page.GetByTextOptions().setExact(true))
                    .waitFor();
            try (var db = store.open()) {
                db.update(
                        "UPDATE os_auth_attempt SET expires_at=0 WHERE identity_hash=?",
                        Passwords.tokenHash(name));
                db.connection.commit();
            }
            login(page, name);
            page.setViewportSize(390, 844);
            assertTrue((Boolean) page.evaluate("document.documentElement.scrollWidth<=innerWidth"));
        }
    }

    @Test
    void groupVisibilityChangesTakeEffectInAnAlreadySignedInBrowser() throws Exception {
        String name = "TeamBrowser" + id().substring(0, 8), group, record;
        try (var db = store.open()) {
            Service.createAccount(
                    db, map("name", name, "role", "reader", "password", WorkflowSupport.PASSWORD));
            group =
                    Service.create(
                                    db,
                                    "sys_user_group",
                                    map("short_description", "Browser privacy team"),
                                    "admin")
                            .get("sys_id")
                            .toString();
            record =
                    Service.create(
                                    db,
                                    "incident",
                                    map("short_description", "Team browser record"),
                                    "admin")
                            .get("sys_id")
                            .toString();
            GroupMembers.change(
                    db,
                    group,
                    db.one("SELECT user_id FROM os_user WHERE name='admin'")
                            .get("user_id")
                            .toString(),
                    map("user_id", RecordAccess.owner(db, name)),
                    true);
            db.connection.commit();
        }
        try (var playwright = Playwright.create();
                var browser = playwright.chromium().launch();
                var adminContext = browser.newContext();
                var memberContext = browser.newContext()) {
            var admin = adminContext.newPage();
            var member = memberContext.newPage();
            login(admin, "admin");
            login(member, name);
            admin.navigate(base() + "/record/incident/" + record);
            admin.getByLabel("Visibility", new Page.GetByLabelOptions().setExact(true))
                    .selectOption("group");
            admin.getByLabel("Allowed group").selectOption(group);
            admin.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Save visibility"))
                    .click();
            assertEquals(200, member.navigate(base() + "/record/incident/" + record).status());
            admin.navigate(base() + "/record/sys_user_group/" + group);
            admin.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Remove " + name).setExact(true))
                    .click();
            assertEquals(404, member.reload().status());
            admin.setViewportSize(390, 844);
            assertTrue(
                    (Boolean) admin.evaluate("document.documentElement.scrollWidth<=innerWidth"));
        }
    }

    @Test
    void administratorAddsAndRemovesAGroupMemberInTheBrowser() throws Exception {
        String name = "GroupBrowser" + id().substring(0, 8), group;
        try (var db = store.open()) {
            Service.createAccount(
                    db, map("name", name, "role", "reader", "password", WorkflowSupport.PASSWORD));
            group =
                    Service.create(
                                    db,
                                    "sys_user_group",
                                    map("short_description", "Browser group"),
                                    "admin")
                            .get("sys_id")
                            .toString();
            db.connection.commit();
        }
        try (var playwright = Playwright.create();
                var browser = playwright.chromium().launch();
                var context = browser.newContext()) {
            var page = context.newPage();
            login(page, "admin");
            page.navigate(base() + "/record/sys_user_group/" + group);
            page.getByLabel("Member username").fill(name);
            page.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Add member").setExact(true))
                    .click();
            page.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Remove " + name).setExact(true))
                    .click();
            page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Group members"))
                    .waitFor();
            assertEquals(
                    0,
                    page.getByRole(
                                    AriaRole.BUTTON,
                                    new Page.GetByRoleOptions().setName("Remove " + name))
                            .count());
            page.setViewportSize(390, 844);
            assertTrue((Boolean) page.evaluate("document.documentElement.scrollWidth<=innerWidth"));
        }
    }

    @Test
    void administratorMakesARecordPrivateThroughTheBrowser() throws Exception {
        String name = "PrivateBrowser" + id().substring(0, 8), record;
        try (var db = store.open()) {
            Service.createAccount(
                    db, map("name", name, "role", "agent", "password", WorkflowSupport.PASSWORD));
            record =
                    Service.create(
                                    db,
                                    "incident",
                                    map("short_description", "PrivateBrowserRecord"),
                                    "admin")
                            .get("sys_id")
                            .toString();
            db.connection.commit();
        }
        try (var playwright = Playwright.create();
                var browser = playwright.chromium().launch();
                var adminContext = browser.newContext();
                var userContext = browser.newContext()) {
            var admin = adminContext.newPage();
            var user = userContext.newPage();
            login(admin, "admin");
            login(user, name);
            admin.navigate(base() + "/record/incident/" + record);
            admin.getByLabel("Visibility", new Page.GetByLabelOptions().setExact(true))
                    .selectOption("private");
            admin.getByLabel("Allowed username").fill(name);
            admin.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Save visibility"))
                    .click();
            user.navigate(base() + "/record/incident/" + record);
            user.getByRole(
                            AriaRole.HEADING,
                            new Page.GetByRoleOptions().setName("PrivateBrowserRecord"))
                    .waitFor();
            assertEquals(
                    0,
                    user.getByRole(
                                    AriaRole.BUTTON,
                                    new Page.GetByRoleOptions().setName("Save visibility"))
                            .count());
            admin.setViewportSize(390, 844);
            assertTrue(
                    (Boolean) admin.evaluate("document.documentElement.scrollWidth<=innerWidth"));
        }
    }

    @Test
    void administratorAssistsRecoveryAndOldBrowserMustAuthenticateAgain() throws Exception {
        String name = "RecoveryBrowser" + id().substring(0, 8),
                next = "browser-recovery-password",
                target;
        try (var db = store.open()) {
            Service.createAccount(
                    db, map("name", name, "role", "reader", "password", WorkflowSupport.PASSWORD));
            target =
                    db.one("SELECT user_id FROM os_user WHERE name=?", name)
                            .get("user_id")
                            .toString();
            db.connection.commit();
        }
        try (var playwright = Playwright.create();
                var browser = playwright.chromium().launch();
                var adminContext = browser.newContext();
                var oldContext = browser.newContext();
                var resetContext = browser.newContext()) {
            var admin = adminContext.newPage();
            var old = oldContext.newPage();
            var reset = resetContext.newPage();
            login(admin, "admin");
            login(old, name);
            admin.navigate(base() + "/admin/accounts/" + target);
            admin.getByLabel("Your administrator password").fill(WorkflowSupport.PASSWORD);
            admin.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Create recovery link"))
                    .click();
            String path =
                    admin.getByRole(
                                    AriaRole.LINK,
                                    new Page.GetByRoleOptions().setName("Password recovery link"))
                            .getAttribute("href");
            reset.navigate(base() + path);
            reset.getByLabel("New password").fill(next);
            reset.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Reset password"))
                    .click();
            reset.waitForURL("**/login");
            old.navigate(base() + "/account");
            old.waitForURL("**/login");
            reset.getByLabel("Username", new Page.GetByLabelOptions().setExact(true)).fill(name);
            reset.getByLabel("Password", new Page.GetByLabelOptions().setExact(true)).fill(next);
            reset.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Enter your workspace"))
                    .click();
            reset.waitForURL(base() + "/");
            assertTrue(reset.request().get(base() + "/api/openservice/me").text().contains(target));
            admin.setViewportSize(390, 844);
            assertTrue(
                    (Boolean) admin.evaluate("document.documentElement.scrollWidth <= innerWidth"));
        }
    }

    @Test
    void administratorInvitesAndRecipientChoosesTheirOwnPassword() throws Exception {
        String name = "InvitedBrowser" + id().substring(0, 8);
        try (var playwright = Playwright.create();
                var browser = playwright.chromium().launch();
                var adminContext = browser.newContext();
                var recipientContext = browser.newContext()) {
            var admin = adminContext.newPage();
            var recipient = recipientContext.newPage();
            login(admin, "admin");
            admin.getByRole(
                            AriaRole.LINK,
                            new Page.GetByRoleOptions().setName("Invitations").setExact(true))
                    .click();
            admin.getByLabel("Invited username").fill(name);
            admin.getByLabel("Invited role").selectOption("reader");
            admin.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Create invitation"))
                    .click();
            String path =
                    admin.getByRole(
                                    AriaRole.LINK,
                                    new Page.GetByRoleOptions()
                                            .setName("Invitation acceptance link"))
                            .getAttribute("href");
            recipient.navigate(base() + path);
            recipient.getByLabel("Choose your password").fill(WorkflowSupport.PASSWORD);
            recipient
                    .getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Accept invitation"))
                    .click();
            recipient.waitForURL("**/login");
            login(recipient, name);
            assertTrue(
                    recipient
                            .request()
                            .get(base() + "/api/openservice/me")
                            .text()
                            .contains("\"role\":\"reader\""));
            admin.navigate(base() + "/admin/invitations");
            assertTrue(admin.getByRole(AriaRole.TABLE).innerText().contains("accepted"));
            assertEquals(
                    0,
                    admin.getByRole(
                                    AriaRole.LINK,
                                    new Page.GetByRoleOptions()
                                            .setName("Invitation acceptance link"))
                            .count());
            admin.setViewportSize(390, 844);
            assertTrue(
                    (Boolean) admin.evaluate("document.documentElement.scrollWidth <= innerWidth"));
        }
    }

    @Test
    void readerChangesPasswordAndSignsInWithTheNewSecret() throws Exception {
        String name = "PasswordBrowser" + id().substring(0, 8), next = "browser-changed-password";
        try (var db = store.open()) {
            Service.createAccount(
                    db, map("name", name, "password", WorkflowSupport.PASSWORD, "role", "reader"));
            db.connection.commit();
        }
        try (var playwright = Playwright.create();
                var browser = playwright.chromium().launch();
                var context = browser.newContext()) {
            var page = context.newPage();
            login(page, name);
            page.navigate(base() + "/account");
            page.getByLabel("Current password", new Page.GetByLabelOptions().setExact(true))
                    .fill("incorrect-password");
            page.getByLabel("New password", new Page.GetByLabelOptions().setExact(true)).fill(next);
            page.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Change password").setExact(true))
                    .click();
            page.getByRole(AriaRole.ALERT)
                    .filter(new Locator.FilterOptions().setHasText("Current password is incorrect"))
                    .waitFor();
            assertEquals(
                    "",
                    page.getByLabel("New password", new Page.GetByLabelOptions().setExact(true))
                            .inputValue());
            page.getByLabel("Current password", new Page.GetByLabelOptions().setExact(true))
                    .fill(WorkflowSupport.PASSWORD);
            page.getByLabel("New password", new Page.GetByLabelOptions().setExact(true)).fill(next);
            page.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Change password").setExact(true))
                    .click();
            page.waitForURL("**/login");
            page.getByLabel("Username", new Page.GetByLabelOptions().setExact(true)).fill(name);
            page.getByLabel("Password", new Page.GetByLabelOptions().setExact(true)).fill(next);
            page.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Enter your workspace"))
                    .click();
            page.waitForURL(base() + "/");
            page.navigate(base() + "/account");
            page.setViewportSize(390, 844);
            assertTrue(
                    (Boolean) page.evaluate("document.documentElement.scrollWidth <= innerWidth"));
        }
    }

    @Test
    void administratorCanChangeAccountStatusAndUserMustSignInAgain() throws Exception {
        String name = "OffboardBrowser" + id().substring(0, 8);
        try (var db = store.open()) {
            Service.createAccount(
                    db, map("name", name, "password", WorkflowSupport.PASSWORD, "role", "agent"));
            db.connection.commit();
        }
        try (var playwright = Playwright.create();
                var browser = playwright.chromium().launch();
                var administrators = browser.newContext();
                var users = browser.newContext()) {
            var admin = administrators.newPage();
            var user = users.newPage();
            login(admin, "admin");
            login(user, name);
            admin.navigate(base() + "/admin/accounts");
            admin.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(name).setExact(true))
                    .click();
            admin.getByLabel("Account status").selectOption("false");
            admin.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Save account status"))
                    .click();
            user.navigate(base() + "/account");
            user.waitForURL("**/login");
            admin.getByLabel("Account status").selectOption("true");
            admin.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Save account status"))
                    .click();
            login(user, name);
            admin.getByLabel("Account role").selectOption("reader");
            admin.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Save account role"))
                    .click();
            user.navigate(base() + "/account");
            user.waitForURL("**/login");
            login(user, name);
            assertTrue(
                    user.request()
                            .get(base() + "/api/openservice/me")
                            .text()
                            .contains("\"role\":\"reader\""));
            admin.getByRole(
                            AriaRole.LINK,
                            new Page.GetByRoleOptions().setName("Security events").setExact(true))
                    .click();
            admin.getByRole(
                            AriaRole.HEADING,
                            new Page.GetByRoleOptions().setName("Security events").setExact(true))
                    .waitFor();
            assertTrue(admin.getByRole(AriaRole.TABLE).innerText().contains(name));
            assertTrue(
                    admin.getByRole(AriaRole.TABLE).innerText().contains("account.role_changed"));
            admin.setViewportSize(390, 844);
            assertTrue(
                    (Boolean) admin.evaluate("document.documentElement.scrollWidth <= innerWidth"));
        }
    }

    @Test
    void readerCanSignOutAllBrowsersFromAccountSecurity() throws Exception {
        String name = "SecurityReader" + id().substring(0, 8);
        try (var db = store.open()) {
            Service.createAccount(
                    db, map("name", name, "password", WorkflowSupport.PASSWORD, "role", "reader"));
            db.connection.commit();
        }
        try (var playwright = Playwright.create();
                var browser = playwright.chromium().launch();
                var first = browser.newContext();
                var second = browser.newContext()) {
            var one = first.newPage();
            var two = second.newPage();
            login(one, name);
            login(two, name);
            one.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Account security"))
                    .click();
            one.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Sign out all browsers"))
                    .click();
            one.waitForURL("**/login");
            two.navigate(base() + "/account");
            two.waitForURL("**/login");
            login(two, name);
            two.navigate(base() + "/account");
            two.setViewportSize(390, 844);
            assertTrue(
                    (Boolean) two.evaluate("document.documentElement.scrollWidth <= innerWidth"));
            assertTrue(
                    two.getByRole(
                                    AriaRole.HEADING,
                                    new Page.GetByRoleOptions().setName("Account security"))
                            .isVisible());
        }
    }

    @Test
    void accountOnboardingRecordLifecycleMigrationAndResponsiveLayout() throws Exception {
        try (var db = store.open()) {
            for (String table : List.of("os_attachment", "os_event", "os_record"))
                db.update("DELETE FROM " + table);
            db.update("DELETE FROM os_user WHERE name<>?", "admin");
            db.connection.commit();
        }
        Path artifacts = Path.of("target/browser-artifacts");
        Files.createDirectories(artifacts);
        var errors = new ArrayList<String>();
        String suffix = id().substring(0, 8);
        String agent = "EvalAgent" + suffix, reader = "EvalReader" + suffix;
        try (var playwright = Playwright.create();
                var browser = playwright.chromium().launch()) {
            try (var context =
                    browser.newContext(
                            new Browser.NewContextOptions().setViewportSize(1366, 768))) {
                var page = context.newPage();
                page.onPageError(errors::add);
                login(page, "admin");
                page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Accounts"))
                        .click();
                page.getByRole(
                                AriaRole.HEADING,
                                new Page.GetByRoleOptions()
                                        .setName("Give your team a place to work."))
                        .waitFor();
                assertTrue(
                        (Boolean)
                                page.locator(".sidebar")
                                        .evaluate(
                                                "el => el.scrollHeight <= el.clientHeight ||"
                                                    + " ['auto','scroll'].includes(getComputedStyle(el).overflowY)"));
                page.screenshot(
                        new Page.ScreenshotOptions()
                                .setPath(artifacts.resolve("accounts-laptop.png")));
                page.setViewportSize(1512, 1100);
                assertEquals(
                        "reader",
                        page.getByLabel("Role", new Page.GetByLabelOptions().setExact(true))
                                .inputValue());
                for (var entry : Map.of(agent, "agent", reader, "reader").entrySet()) {
                    page.getByLabel("Username", new Page.GetByLabelOptions().setExact(true))
                            .fill(entry.getKey());
                    page.getByLabel("Password", new Page.GetByLabelOptions().setExact(true))
                            .fill(WorkflowSupport.PASSWORD);
                    page.getByLabel("Role", new Page.GetByLabelOptions().setExact(true))
                            .selectOption(entry.getValue());
                    page.getByRole(
                                    AriaRole.BUTTON,
                                    new Page.GetByRoleOptions().setName("Create account"))
                            .click();
                    page.getByRole(
                                    AriaRole.CELL,
                                    new Page.GetByRoleOptions()
                                            .setName(entry.getKey())
                                            .setExact(true))
                            .waitFor();
                    assertEquals(
                            "",
                            page.getByLabel("Password", new Page.GetByLabelOptions().setExact(true))
                                    .inputValue());
                }
                page.getByLabel("Username", new Page.GetByLabelOptions().setExact(true))
                        .fill(agent);
                page.getByLabel("Password", new Page.GetByLabelOptions().setExact(true))
                        .fill(WorkflowSupport.PASSWORD);
                page.getByRole(
                                AriaRole.BUTTON,
                                new Page.GetByRoleOptions().setName("Create account"))
                        .click();
                page.getByRole(AriaRole.ALERT)
                        .filter(new Locator.FilterOptions().setHasText("User already exists"))
                        .waitFor();
                assertFalse(page.content().contains(WorkflowSupport.PASSWORD));
                page.navigate(base() + "/admin/accounts");
                page.screenshot(
                        new Page.ScreenshotOptions()
                                .setPath(artifacts.resolve("accounts-desktop.png"))
                                .setFullPage(true));
                page.setViewportSize(390, 844);
                assertTrue(
                        (Boolean)
                                page.evaluate(
                                        "document.documentElement.scrollWidth <= innerWidth"));
                page.screenshot(
                        new Page.ScreenshotOptions()
                                .setPath(artifacts.resolve("accounts-mobile.png"))
                                .setFullPage(true));
                page.setViewportSize(1512, 1100);
                page.navigate(base() + "/migration");
                page.locator("input[type=file]")
                        .setInputFiles(
                                new FilePayload(
                                        "preview.json",
                                        "application/json",
                                        JSON.writeValueAsBytes(
                                                map(
                                                        "result",
                                                        List.of(
                                                                map(
                                                                        "sys_id",
                                                                        "f".repeat(32),
                                                                        "short_description",
                                                                        "Browser migration"
                                                                                + " preview"))))));
                page.getByRole(
                                AriaRole.BUTTON,
                                new Page.GetByRoleOptions().setName("Review and run"))
                        .click();
                page.getByRole(
                                AriaRole.HEADING,
                                new Page.GetByRoleOptions().setName("Dry-run report"))
                        .waitFor();
                page.screenshot(
                        new Page.ScreenshotOptions()
                                .setPath(artifacts.resolve("migration.png"))
                                .setFullPage(true));
            }
            String recordUrl;
            try (var context =
                    browser.newContext(
                            new Browser.NewContextOptions().setViewportSize(1512, 1100))) {
                var page = context.newPage();
                page.onPageError(errors::add);
                login(page, agent);
                assertEquals(0, page.locator("a[href='/admin/accounts']").count());
                assertEquals(403, page.navigate(base() + "/admin/accounts").status());
                page.navigate(base() + "/workspace/incident");
                page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("New record"))
                        .click();
                page.getByLabel("Summary", new Page.GetByLabelOptions().setExact(true))
                        .fill("Synthetic account evaluation " + suffix);
                page.getByRole(
                                AriaRole.BUTTON,
                                new Page.GetByRoleOptions().setName("Create record"))
                        .click();
                page.locator("select[name=state]").selectOption("2");
                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Save changes"))
                        .click();
                page.reload();
                assertEquals("2", page.locator("select[name=state]").inputValue());
                recordUrl = page.url();
                page.getByLabel("Add an internal note")
                        .fill("Verified incident transition and activity history.");
                page.getByRole(
                                AriaRole.BUTTON,
                                new Page.GetByRoleOptions().setName("Add note").setExact(true))
                        .click();
                page.getByText(
                                "Verified incident transition and activity history.",
                                new Page.GetByTextOptions().setExact(true))
                        .waitFor();
                page.locator("input[type=file]")
                        .setInputFiles(
                                new FilePayload(
                                        "browser-evidence.txt",
                                        "text/plain",
                                        "Synthetic browser evidence"
                                                .getBytes(StandardCharsets.UTF_8)));
                page.getByRole(
                                AriaRole.BUTTON,
                                new Page.GetByRoleOptions().setName("Upload").setExact(true))
                        .click();
                page.getByRole(
                                AriaRole.LINK,
                                new Page.GetByRoleOptions().setName("browser-evidence.txt"))
                        .waitFor();
                page.screenshot(
                        new Page.ScreenshotOptions()
                                .setPath(artifacts.resolve("record.png"))
                                .setFullPage(true));
                page.navigate(base());
                page.screenshot(
                        new Page.ScreenshotOptions()
                                .setPath(artifacts.resolve("workspace.png"))
                                .setFullPage(true));
                page.setViewportSize(390, 844);
                assertTrue(
                        (Boolean)
                                page.evaluate(
                                        "document.documentElement.scrollWidth <= innerWidth"));
                page.screenshot(
                        new Page.ScreenshotOptions()
                                .setPath(artifacts.resolve("mobile.png"))
                                .setFullPage(true));
            }
            try (var context = browser.newContext()) {
                var page = context.newPage();
                page.onPageError(errors::add);
                login(page, reader);
                assertEquals(0, page.locator("a[href='/admin/accounts']").count());
                assertEquals(403, page.navigate(base() + "/admin/accounts").status());
                page.navigate(base() + "/workspace/incident");
                assertEquals(
                        0,
                        page.getByRole(
                                        AriaRole.LINK,
                                        new Page.GetByRoleOptions().setName("New record"))
                                .count());
                page.navigate(recordUrl);
                assertFalse(
                        page.locator("main")
                                .innerText()
                                .contains("Verified incident transition and activity history."));
                page.navigate(base() + "/audit");
                assertFalse(
                        page.locator("main")
                                .innerText()
                                .contains("Verified incident transition and activity history."));
                page.navigate(recordUrl);
                assertNotNull(
                        page.getByLabel("Summary", new Page.GetByLabelOptions().setExact(true))
                                .getAttribute("readonly"));
                assertTrue(page.locator("select[name=state]").isDisabled());
                page.getByLabel("Add a comment", new Page.GetByLabelOptions().setExact(true))
                        .fill("Requester browser comment <script>alert(1)</script>");
                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Post comment"))
                        .click();
                page.reload();
                assertTrue(
                        page.locator("main")
                                .innerText()
                                .contains("Requester browser comment <script>alert(1)</script>"));
                assertEquals(
                        0,
                        page.getByRole(
                                        AriaRole.BUTTON,
                                        new Page.GetByRoleOptions().setName("Save changes"))
                                .count());
            }
        }
        assertEquals(List.of(), errors);
    }
}
