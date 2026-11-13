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
    void unusualHttpInputsRespectAuthenticationAndBounds() throws Exception {
        var controller = new WebController(store());
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
        empty.getSession().setAttribute("user", "admin");
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
        try (var mocked = mockStatic(Service.class)) {
            mocked.when(() -> Service.createAccount(any(), any()))
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
