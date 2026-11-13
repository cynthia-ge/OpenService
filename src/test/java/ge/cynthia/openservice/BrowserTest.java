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
