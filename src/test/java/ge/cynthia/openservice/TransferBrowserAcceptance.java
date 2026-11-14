package ge.cynthia.openservice;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import static ge.cynthia.openservice.Values.*;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.microsoft.playwright.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

final class TransferBrowserAcceptance {
    static void saved(Page page) {
        assertThat(page.locator("#migration-message"))
                .hasText("Saved. Review the migration report below.");
    }

    static void run(BrowserTest h) throws Exception {
        MigrationSource.reset();
        String source = MigrationSource.key(9000);
        MigrationSource.tables.put(
                "incident",
                List.of(
                        map(
                                "sys_id",
                                source,
                                "short_description",
                                "Browser migration",
                                "state",
                                "1",
                                "priority",
                                "3")));
        try (var db = h.store.open()) {
            for (String table : List.of("os_transfer_job", "os_transfer_item", "os_transfer_link"))
                db.update("DELETE FROM " + table);
            db.update("DELETE FROM os_record WHERE sys_id=?", source);
            db.update("DELETE FROM os_record_access WHERE record_id=?", source);
            db.connection.commit();
        }
        try (var playwright = Playwright.create();
                var browser = playwright.chromium().launch();
                var context = browser.newContext()) {
            var page = context.newPage();
            h.login(page, "admin");
            var cdp = context.newCDPSession(page);
            cdp.send("Debugger.enable");
            cdp.send("Profiler.enable");
            var options = new JsonObject();
            options.addProperty("callCount", true);
            options.addProperty("detailed", true);
            cdp.send("Profiler.startPreciseCoverage", options);
            page.navigate(h.base() + "/admin/migrations");
            saved(page);
            page.locator("#migration-plan").fill("not-json");
            page.locator("#migration-create button").click();
            assertThat(page.locator("#migration-message")).containsText("JSON");
            page.locator("#migration-plan")
                    .fill(JSON.writeValueAsString(TransferValidationAcceptance.basic()));
            page.locator("#migration-create button").click();
            saved(page);
            String original = page.locator("#migration-jobs").inputValue();
            assertThat(page.locator("#migration-report")).containsText("fetching");
            page.locator("#migration-fetch").click();
            assertThat(page.locator("#migration-message"))
                    .containsText("Supply a source OAuth bearer token");
            page.locator("#migration-token").fill(MigrationSource.TOKEN);
            MigrationSource.entered = new CountDownLatch(1);
            MigrationSource.release = new CountDownLatch(1);
            page.locator("#migration-fetch").click();
            assertTrue(MigrationSource.entered.await(10, TimeUnit.SECONDS));
            page.locator("#migration-fetch").click();
            page.locator("#migration-stop").click();
            MigrationSource.release.countDown();
            MigrationSource.release = null;
            saved(page);
            assertThat(page.locator("#migration-report")).containsText(source);
            assertThat(page.locator("#migration-token")).hasValue("");
            page.locator("#migration-token").fill(MigrationSource.TOKEN);
            page.locator("#migration-fetch").click();
            saved(page);
            assertThat(page.locator("#migration-report")).containsText("review");
            page.route(
                    "**/migrations/*/preview",
                    route ->
                            route.fulfill(
                                    new Route.FulfillOptions()
                                            .setStatus(500)
                                            .setContentType("application/json")
                                            .setBody("{}")));
            page.locator("#migration-preview").click();
            assertThat(page.locator("#migration-message")).hasText("Migration request failed");
            page.unroute("**/migrations/*/preview");
            page.locator("#migration-preview").click();
            saved(page);
            assertThat(page.locator("#migration-report")).containsText("\"preview\": true");
            try (var db = h.store.open()) {
                assertNull(db.one("SELECT sys_id FROM os_record WHERE sys_id=?", source));
            }
            page.locator("#migration-apply").click();
            saved(page);
            assertThat(page.locator("#migration-report")).containsText("\"preview\": false");
            page.locator("#migration-sync").click();
            saved(page);
            String next = page.locator("#migration-jobs").inputValue();
            assertNotEquals(original, next);
            page.locator("#migration-token").fill(MigrationSource.TOKEN);
            page.locator("#migration-fetch").click();
            saved(page);
            page.locator("#migration-apply").click();
            saved(page);
            page.locator("#migration-cutover").click();
            assertThat(page.locator("#migration-message")).containsText("Review and confirm");
            page.locator("#migration-confirm").check();
            page.locator("#migration-cutover").click();
            saved(page);
            assertThat(page.locator("#migration-report")).containsText("cutover");
            page.locator("#migration-jobs").selectOption(original);
            saved(page);
            page.locator("#migration-refresh").click();
            saved(page);
            assertThat(page.locator("#migration-archives a"))
                    .hasAttribute(
                            "href",
                            TransferAcceptance.API + "/" + original + "/archive?table=incident");
            var coverage = cdp.send("Profiler.takePreciseCoverage").getAsJsonArray("result");
            for (var entry : coverage)
                if (entry.getAsJsonObject()
                        .get("url")
                        .getAsString()
                        .endsWith("/static/migrations.js")) {
                    var script = entry.getAsJsonObject();
                    var params = new JsonObject();
                    params.add("scriptId", script.get("scriptId"));
                    var report = new JsonObject();
                    report.add(
                            "source",
                            cdp.send("Debugger.getScriptSource", params).get("scriptSource"));
                    report.add("functions", script.get("functions"));
                    Files.writeString(
                            Path.of("target/migration-browser-v8.json"), report.toString());
                }
            assertTrue(Files.exists(Path.of("target/migration-browser-v8.json")));
        } finally {
            MigrationSource.reset();
        }
    }
}
