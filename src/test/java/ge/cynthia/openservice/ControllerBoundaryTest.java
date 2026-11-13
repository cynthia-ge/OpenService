package ge.cynthia.openservice;

import static ge.cynthia.openservice.Values.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;

class ControllerBoundaryTest {
    @TempDir Path root;

    Store store() throws Exception {
        return new Store(
                new MockEnvironment()
                        .withProperty("DATABASE_URL", "sqlite:///" + root.resolve("boundary.db"))
                        .withProperty("SECRET_KEY", "disposable-boundary-secret")
                        .withProperty("ADMIN_PASSWORD", WorkflowSupport.PASSWORD));
    }

    MockHttpServletRequest request(String method, String path) {
        var request = new MockHttpServletRequest(method, path);
        request.addHeader("Authorization", WorkflowSupport.auth("admin"));
        request.getSession().setAttribute("csrf", "known-csrf");
        return request;
    }

    int dispatch(WebController controller, MockHttpServletRequest request) throws Exception {
        var view = controller.dispatch(request, new MockHttpServletResponse());
        return view.getStatus() == null ? 200 : view.getStatus().value();
    }

    @Test
    void expiredSessionsAreInvalidatedBeforeRoutingAndFreshLoginRecovers() throws Exception {
        var instance = store();
        var controller = new WebController(instance);
        String id;
        try (var db = instance.open()) {
            id = db.one("SELECT user_id FROM os_user WHERE name='admin'").get("user_id").toString();
        }
        for (String path : List.of("/api/openservice/me", "/account")) {
            var request = request("GET", path);
            request.removeHeader("Authorization");
            var old = (MockHttpSession) request.getSession();
            old.setAttribute("user", id);
            old.setAttribute("auth_revision", 0L);
            old.setAttribute(
                    "authenticated_at",
                    System.currentTimeMillis() - SessionLifetime.MAX_AGE_MILLIS);
            assertEquals(path.startsWith("/api/") ? 401 : 302, dispatch(controller, request));
            assertTrue(old.isInvalid());
            assertNull(request.getSession().getAttribute("user"));
            String csrf = request.getSession().getAttribute("csrf").toString();
            var login = new MockHttpServletRequest("POST", "/login");
            login.setSession((MockHttpSession) request.getSession());
            login.addParameter("csrf", csrf);
            login.addParameter("username", "admin");
            login.addParameter("password", WorkflowSupport.PASSWORD);
            assertEquals(302, dispatch(controller, login));
            assertTrue(
                    SessionLifetime.valid(
                            login.getSession().getAttribute("authenticated_at"),
                            System.currentTimeMillis()));
            var restored = new MockHttpServletRequest("GET", "/api/openservice/me");
            restored.setSession((MockHttpSession) login.getSession());
            assertEquals(200, dispatch(controller, restored));
        }
        var basic = request("GET", "/api/openservice/me");
        basic.getSession().setAttribute("user", id);
        basic.getSession().setAttribute("auth_revision", 0L);
        // Pre-upgrade sessions have no authenticated_at timestamp; Basic still reauthenticates.
        assertEquals(200, dispatch(controller, basic));
        assertNull(basic.getSession().getAttribute("user"));
    }

    @Test
    void unusualHttpInputsRespectAuthenticationAndBounds() throws Exception {
        var instance = store();
        var controller = new WebController(instance);
        var head = request("HEAD", "/healthz");
        assertEquals(200, dispatch(controller, head));
        var invalid = request("GET", "/api/now/table/incident");
        invalid.removeHeader("Authorization");
        invalid.addHeader(
                "Authorization",
                "Basic "
                        + Base64.getEncoder()
                                .encodeToString("admin".getBytes(StandardCharsets.UTF_8)));
        assertEquals(401, dispatch(controller, invalid));
        var empty = request("POST", "/api/now/table/incident");
        empty.removeHeader("Authorization");
        empty.addHeader("Authorization", "");
        try (var db = instance.open()) {
            empty.getSession()
                    .setAttribute(
                            "user",
                            db.one("SELECT user_id FROM os_user WHERE name='admin'")
                                    .get("user_id"));
        }
        empty.getSession().setAttribute("auth_revision", 0L);
        empty.getSession().setAttribute("authenticated_at", System.currentTimeMillis());
        empty.addHeader("X-CSRF-Token", "known-csrf");
        empty.setContentType("application/json");
        empty.setContent(
                JSON.writeValueAsBytes(map("short_description", "Empty authorization session")));
        empty.setQueryString("");
        assertEquals(201, dispatch(controller, empty));
        var accounts = request("GET", "/admin/accounts");
        accounts.setQueryString("");
        assertEquals(200, dispatch(controller, accounts));
        accounts.setQueryString("page");
        assertEquals(400, dispatch(controller, accounts));
        var wrongType = request("POST", "/api/now/table/incident");
        wrongType.setContentType("text/plain");
        wrongType.setContent("{}".getBytes(StandardCharsets.UTF_8));
        assertEquals(415, dispatch(controller, wrongType));
        var chunked =
                new MockHttpServletRequest("POST", "/api/now/table/incident") {
                    @Override
                    public long getContentLengthLong() {
                        return -1;
                    }
                };
        chunked.addHeader("Authorization", WorkflowSupport.auth("admin"));
        chunked.setContentType("application/json");
        chunked.setContent(new byte[6 * 1024 * 1024 + 1]);
        assertEquals(413, dispatch(controller, chunked));
    }

    @Test
    void filenameAndUnexpectedAccountFailuresAreHandled() throws Exception {
        var store = store();
        var controller = new WebController(store);
        String id;
        try (var db = store.open()) {
            id =
                    (String)
                            Service.create(
                                            db,
                                            "incident",
                                            map("short_description", "Attachment boundary"),
                                            "admin")
                                    .get("sys_id");
            db.connection.commit();
        }
        var request = new MockMultipartHttpServletRequest();
        request.setMethod("POST");
        request.setRequestURI("/record/incident/" + id + "/attachment");
        request.addHeader("Authorization", WorkflowSupport.auth("admin"));
        request.addFile(
                new MockMultipartFile("file", "name.txt", "text/plain", new byte[] {1}) {
                    @Override
                    public String getOriginalFilename() {
                        return null;
                    }
                });
        assertEquals(400, dispatch(controller, request));
        var api = new MockMultipartHttpServletRequest();
        api.setMethod("POST");
        api.setRequestURI("/api/now/attachment/upload");
        api.addHeader("Authorization", WorkflowSupport.auth("admin"));
        api.addParameter("table_name", "incident");
        api.addParameter("table_sys_id", id);
        api.addFile(
                new MockMultipartFile("uploadFile", "name.txt", "text/plain", new byte[] {1}) {
                    @Override
                    public String getOriginalFilename() {
                        return null;
                    }
                });
        assertEquals(400, dispatch(controller, api));
        try (var mocked = mockStatic(Service.class)) {
            mocked.when(() -> Service.createManagedAccount(any(), any(), any()))
                    .thenThrow(new Failure(403, "Account creation denied"));
            assertEquals(403, dispatch(controller, request("POST", "/admin/accounts")));
        }
    }

    @Test
    void connectionCloseFailuresKeepTheOriginalFailure() throws Exception {
        var real = store();
        for (boolean failDuringRequest : List.of(false, true)) {
            var db = spy(real.open());
            var store = mock(Store.class);
            doReturn(db).when(store).open();
            doThrow(new SQLException("close failure")).when(db).close();
            if (failDuringRequest)
                doThrow(new IllegalStateException("request failure")).when(db).count("SELECT 1");
            try {
                var error =
                        assertThrows(
                                Exception.class,
                                () ->
                                        new WebController(store)
                                                .dispatch(
                                                        request("GET", "/healthz"),
                                                        new MockHttpServletResponse()));
                assertEquals(
                        failDuringRequest ? "request failure" : "close failure",
                        error.getMessage());
                assertEquals(failDuringRequest ? 1 : 0, error.getSuppressed().length);
            } finally {
                db.connection.close();
            }
        }
    }
}
