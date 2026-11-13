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
                assertNotNull(
                        page.getByLabel("Summary", new Page.GetByLabelOptions().setExact(true))
                                .getAttribute("readonly"));
                assertTrue(page.locator("select[name=state]").isDisabled());
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
