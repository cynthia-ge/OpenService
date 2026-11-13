package ge.cynthia.openservice;

import static ge.cynthia.openservice.Values.*;

import jakarta.servlet.http.*;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.View;

import java.net.*;
import java.nio.*;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@Controller
public class WebController {
    private final Store store;

    public WebController(Store store) {
        this.store = store;
    }

    record Request(
            Store.Db db,
            HttpServletRequest http,
            HttpServletResponse response,
            HttpSession session,
            Map<String, Object> user) {
        String name() {
            return (String) user.get("name");
        }

        String role() {
            return (String) user.get("role");
        }

        String parameter(String key, String fallback) {
            if (http.getRequestURI().equals("/api/now/attachment/file"))
                return queryArguments(this).getOrDefault(key, fallback);
            return Objects.toString(http.getParameter(key), fallback);
        }

        Map<String, String> arguments() {
            if (http.getRequestURI().equals("/api/now/attachment/file"))
                return queryArguments(this);
            var values = new LinkedHashMap<String, String>();
            http.getParameterMap().forEach((key, value) -> values.put(key, value[0]));
            return values;
        }

        void admin() {
            if (!role().equals("admin"))
                throw new Failure(403, "Only administrators can perform this action");
        }

        void noQuery() {
            if (http.getQueryString() != null && !http.getQueryString().isEmpty())
                throw new Failure(400, "Write query parameters are not yet supported");
        }

        void uniqueQuery() {
            if (http.getRequestURI().equals("/api/now/attachment/file")) {
                queryArguments(this);
                return;
            }
            if (http.getParameterMap().values().stream().anyMatch(v -> v.length != 1))
                throw new Failure(400, "Repeated query parameter");
        }

        String base() {
            String url = http.getRequestURL().toString();
            return url.substring(0, url.length() - http.getRequestURI().length());
        }
    }

    @RequestMapping("/**")
    public ModelAndView dispatch(HttpServletRequest request, HttpServletResponse response)
            throws Exception {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader(
                "Content-Security-Policy",
                "default-src 'self'; style-src 'self'; img-src 'self' data:; frame-ancestors"
                        + " 'none'; form-action 'self'; base-uri 'self'");
        try (var db = store.open()) {
            return dispatch(db, request, response);
        }
    }

    private ModelAndView dispatch(
            Store.Db db, HttpServletRequest request, HttpServletResponse response)
            throws Exception {
        HttpSession session = request.getSession();
        if (session.getAttribute("csrf") == null) session.setAttribute("csrf", csrf());
        try {
            if (request.getContentLengthLong() > 6 * 1024 * 1024)
                throw new Failure(413, "Request exceeds 6 MB");
            String path = request.getRequestURI(), method = request.getMethod();
            boolean publicPath = Set.of("/healthz", "/login", "/static/app.css").contains(path);
            var user =
                    session.getAttribute("user") == null
                            ? null
                            : db.one(
                                    "SELECT * FROM os_user WHERE user_id=? AND active=1",
                                    session.getAttribute("user"));
            if (user != null
                    && !Objects.equals(
                            session.getAttribute("auth_revision"),
                            ((Number) user.get("auth_revision")).longValue())) user = null;
            String authorization = request.getHeader("Authorization");
            if (!publicPath && authorization != null && !authorization.isEmpty())
                user = authenticate(db, authorization);
            if (!publicPath && user == null) {
                if (path.startsWith("/api/")) throw new Failure(401, "Authentication required");
                return redirect("/login");
            }
            var context = new Request(db, request, response, session, user);
            if (path.startsWith("/api/")) context.uniqueQuery();
            if (!publicPath && !Set.of("GET", "HEAD", "OPTIONS").contains(method)) {
                if (authorization == null || authorization.isEmpty())
                    checkCsrf(
                            context,
                            Objects.toString(
                                    request.getHeader("X-CSRF-Token"),
                                    context.parameter("csrf", "")));
                if (context.role().equals("reader")
                        && !Set.of(
                                        "/logout",
                                        "/account/sessions/revoke",
                                        "/api/openservice/me/sessions/revoke")
                                .contains(path)) throw new Failure(403, "Read-only account");
            }
            var result = route(context, path, method.equals("HEAD") ? "GET" : method);
            if (result.getStatus() != null && result.getStatus().value() >= 400)
                db.connection.rollback();
            else db.connection.commit();
            return result;
        } catch (Failure error) {
            db.connection.rollback();
            response.setStatus(error.status);
            if (error.status == 401)
                response.setHeader("WWW-Authenticate", "Basic realm=\"OpenService\"");
            if (request.getRequestURI().startsWith("/api/"))
                return json(
                        map(
                                "error",
                                map("message", error.title(), "detail", error.getMessage()),
                                "status",
                                "failure"),
                        error.status);
            return new ModelAndView(
                    "error",
                    map("errorTitle", error.title(), "errorDetail", error.getMessage()),
                    HttpStatus.valueOf(error.status));
        }
    }

    private Map<String, Object> authenticate(Store.Db db, String header) throws Exception {
        try {
            if (!header.regionMatches(true, 0, "Basic ", 0, 6))
                throw new IllegalArgumentException();
            String[] pair =
                    new String(
                                    Base64.getDecoder().decode(header.substring(6)),
                                    StandardCharsets.UTF_8)
                            .split(":", 2);
            var user = db.one("SELECT * FROM os_user WHERE name=? AND active=1", pair[0]);
            if (pair.length != 2
                    || user == null
                    || !Passwords.matches((String) user.get("password"), pair[1]))
                throw new Failure(401, "Invalid credentials");
            return user;
        } catch (IllegalArgumentException error) {
            throw new Failure(401, "Invalid credentials");
        }
    }

    private String csrf() throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(store.secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(mac.doFinal(Passwords.token().getBytes(StandardCharsets.UTF_8)));
    }

    private static void checkCsrf(Request context, String supplied) {
        if (!MessageDigest.isEqual(
                supplied.getBytes(StandardCharsets.UTF_8),
                context.session.getAttribute("csrf").toString().getBytes(StandardCharsets.UTF_8)))
            throw new Failure(403, "Invalid CSRF token");
    }

    private Object body(Request context) throws Exception {
        if (context.http.getContentType() == null
                || !context.http
                        .getContentType()
                        .split(";", 2)[0]
                        .strip()
                        .toLowerCase(Locale.ROOT)
                        .matches("application/(json|[^/; ]+\\+json)"))
            throw new Failure(415, "JSON content type is required");
        byte[] bytes = context.http.getInputStream().readNBytes(6 * 1024 * 1024 + 1);
        if (bytes.length > 6 * 1024 * 1024) throw new Failure(413, "Request exceeds 6 MB");
        try {
            return JSON.readValue(bytes, Object.class);
        } catch (tools.jackson.core.JacksonException error) {
            throw new Failure(400, "Invalid JSON body");
        }
    }

    private ModelAndView route(Request c, String path, String method) throws Exception {
        if (path.equals("/healthz")) {
            method(method, "GET");
            c.db.count("SELECT 1");
            return json(map("status", "ok"), 200);
        }
        if (path.equals("/static/app.css")) {
            method(method, "GET");
            c.response.setHeader("Content-Disposition", "inline; filename=app.css");
            try (var input = WebController.class.getResourceAsStream("/static/app.css")) {
                return binary(
                        Objects.requireNonNull(input).readAllBytes(),
                        "text/css; charset=utf-8",
                        200);
            }
        }
        if (path.equals("/login")) {
            method(method, "GET", "POST");
            if (method.equals("POST")) {
                checkCsrf(c, c.parameter("csrf", ""));
                var user =
                        c.db.one(
                                "SELECT * FROM os_user WHERE name=? AND active=1",
                                c.parameter("username", ""));
                if (user == null
                        || !Passwords.matches(
                                (String) user.get("password"), c.parameter("password", "")))
                    throw new Failure(401, "Incorrect username or password");
                c.http.changeSessionId();
                Collections.list(c.session.getAttributeNames()).forEach(c.session::removeAttribute);
                c.session.setAttribute("user", user.get("user_id"));
                c.session.setAttribute(
                        "auth_revision", ((Number) user.get("auth_revision")).longValue());
                c.session.setAttribute("csrf", csrf());
                return redirect("/");
            }
            return new ModelAndView("login", map("csrf", c.session.getAttribute("csrf")));
        }
        if (path.equals("/logout")) {
            method(method, "POST");
            c.session.invalidate();
            return redirect("/login");
        }
        if (path.equals("/account")) {
            method(method, "GET");
            c.noQuery();
            return view(c, "account", map("table", "account"));
        }
        if (Set.of("/account/sessions/revoke", "/api/openservice/me/sessions/revoke")
                .contains(path)) {
            method(method, "POST");
            c.noQuery();
            c.uniqueQuery();
            boolean api = path.startsWith("/api/");
            if (api && !object(body(c)).isEmpty())
                throw new Failure(400, "Provide an empty JSON object");
            Service.revokeSessions(c.db, c.user.get("user_id").toString());
            c.session.invalidate();
            return api ? json(map("result", map("revoked", true)), 200) : redirect("/login");
        }
        Matcher collection = Pattern.compile("/api/now/(?:v[12]/)?table/([^/]+)").matcher(path);
        if (collection.matches()) {
            method(method, "GET", "POST");
            String table = collection.group(1);
            if (method.equals("POST")) {
                c.noQuery();
                var item = Service.create(c.db, table, body(c), c.name());
                c.response.setHeader("Location", c.base() + path + "/" + item.get("sys_id"));
                return json(map("result", Service.serialize(item)), 201);
            }
            var page = Service.page(c.db, table, c.arguments(), "");
            if (path.startsWith("/api/now/v1/") && page.rows().isEmpty())
                throw new Failure(404, "No records found");
            if (page.total() >= 0)
                c.response.setHeader("X-Total-Count", String.valueOf(page.total()));
            if (!page.suppressPagination()) {
                var links = new ArrayList<String>();
                var positions = map("first", 0);
                if (page.total() >= 0)
                    positions.put(
                            "last", Math.max(0, (page.total() - 1) / page.limit() * page.limit()));
                positions.put("prev", (long) page.offset() - page.limit());
                positions.put("next", (long) page.offset() + page.limit());
                for (var entry : positions.entrySet()) {
                    long index = ((Number) entry.getValue()).longValue();
                    if (entry.getKey().equals("prev") && index < 0
                            || entry.getKey().equals("next") && !page.hasNext()) continue;
                    var args = new LinkedHashMap<>(c.arguments());
                    args.put("sysparm_limit", String.valueOf(page.limit()));
                    args.put("sysparm_offset", String.valueOf(index));
                    links.add(
                            "<"
                                    + c.http.getRequestURL()
                                    + "?"
                                    + query(args)
                                    + ">; rel=\""
                                    + entry.getKey()
                                    + "\"");
                }
                c.response.setHeader("Link", String.join(", ", links));
            }
            return json(map("result", page.rows()), 200);
        }
        Matcher resource =
                Pattern.compile("/api/now/(?:v[12]/)?table/([^/]+)/([^/]+)").matcher(path);
        if (resource.matches()) {
            method(method, "GET", "PATCH", "PUT", "DELETE");
            String table = resource.group(1);
            Service.schema(c.db, table);
            var item = Service.record(c.db, table, resource.group(2));
            if (method.equals("GET")) {
                if (!Set.of("sysparm_fields").containsAll(c.arguments().keySet()))
                    throw new Failure(400, "Unsupported query parameter");
                c.response.setHeader("ETag", "\"" + item.get("revision") + "\"");
                return json(
                        map(
                                "result",
                                Service.project(
                                        Service.serialize(item),
                                        c.parameter("sysparm_fields", ""))),
                        200);
            }
            c.noQuery();
            if (method.equals("DELETE")) {
                c.admin();
                Service.remove(c.db, item, c.name());
                return binary(new byte[0], "application/json", 204);
            }
            String revision = c.http.getHeader("If-Match");
            if (revision != null) revision = revision.replaceAll("^\"+|\"+$", "");
            return json(
                    map(
                            "result",
                            Service.serialize(
                                    Service.update(
                                            c.db, table, item, body(c), c.name(), c.role(),
                                            revision))),
                    200);
        }
        Matcher stats = Pattern.compile("/api/now/stats/([^/]+)").matcher(path);
        if (stats.matches()) {
            method(method, "GET");
            c.uniqueQuery();
            return json(map("result", Service.stats(c.db, stats.group(1), c.arguments())), 200);
        }
        if (path.equals("/api/openservice/schema")) {
            method(method, "GET");
            var result = new LinkedHashMap<String, Object>();
            for (var table : c.db.all("SELECT * FROM os_schema ORDER BY name"))
                result.put((String) table.get("name"), table.get("definition"));
            return json(map("result", result), 200);
        }
        if (path.equals("/api/openservice/me")) {
            method(method, "GET");
            c.noQuery();
            return json(
                    map(
                            "result",
                            map(
                                    "user_id",
                                    c.user.get("user_id"),
                                    "name",
                                    c.name(),
                                    "role",
                                    c.role())),
                    200);
        }
        Matcher accountRole =
                Pattern.compile("/api/openservice/users/([0-9a-f]{32})/role").matcher(path);
        if (accountRole.matches()) {
            method(method, "PATCH");
            c.admin();
            c.noQuery();
            return json(
                    map(
                            "result",
                            Service.changeRole(
                                    c.db,
                                    c.user.get("user_id").toString(),
                                    accountRole.group(1),
                                    body(c))),
                    200);
        }
        Matcher accountStatus =
                Pattern.compile("/api/openservice/users/([0-9a-f]{32})/status").matcher(path);
        if (accountStatus.matches()) {
            method(method, "PATCH");
            c.admin();
            c.noQuery();
            return json(
                    map(
                            "result",
                            Service.changeActive(
                                    c.db,
                                    c.user.get("user_id").toString(),
                                    accountStatus.group(1),
                                    body(c))),
                    200);
        }
        Matcher accountPage = Pattern.compile("/admin/accounts/([0-9a-f]{32})").matcher(path);
        if (accountPage.matches()) return accountDetail(c, accountPage.group(1), method);
        if (path.equals("/api/openservice/users")) {
            method(method, "POST");
            c.admin();
            return json(map("result", Service.createAccount(c.db, body(c))), 201);
        }
        Matcher importing = Pattern.compile("/api/openservice/import/([^/]+)").matcher(path);
        if (importing.matches()) {
            method(method, "POST");
            c.admin();
            if (!Set.of("dry_run").containsAll(c.arguments().keySet())
                    || !List.of("true", "false").contains(c.parameter("dry_run", "true")))
                throw new Failure(400, "Use dry_run=true or false");
            var report =
                    Service.migrate(
                            c.db,
                            importing.group(1),
                            body(c),
                            c.name(),
                            c.parameter("dry_run", "true").equals("true"));
            return json(report, ((List<?>) report.get("errors")).isEmpty() ? 200 : 400);
        }
        if (path.startsWith("/api/now/attachment")) return attachments(c, path, method);
        if (path.equals("/") || path.startsWith("/workspace/")) {
            method(method, "GET");
            String table = path.equals("/") ? "incident" : path.substring(11);
            var definition = Service.schema(c.db, table);
            var page =
                    Service.page(
                            c.db,
                            table,
                            Map.of(
                                    "sysparm_query",
                                    c.parameter("q", ""),
                                    "sysparm_limit",
                                    "30",
                                    "sysparm_offset",
                                    c.parameter("offset", "0")),
                            c.parameter("search", ""));
            return view(
                    c,
                    "workspace",
                    map(
                            "table",
                            table,
                            "definition",
                            definition,
                            "rows",
                            page.rows(),
                            "total",
                            page.total(),
                            "offset",
                            page.offset(),
                            "overview",
                            path.equals("/"),
                            "search",
                            c.parameter("search", ""),
                            "previousLink",
                            "?"
                                    + query(
                                            Map.of(
                                                    "search",
                                                    c.parameter("search", ""),
                                                    "offset",
                                                    String.valueOf(
                                                            Math.max(0, page.offset() - 30)))),
                            "nextLink",
                            "?"
                                    + query(
                                            Map.of(
                                                    "search",
                                                    c.parameter("search", ""),
                                                    "offset",
                                                    String.valueOf((long) page.offset() + 30)))));
        }
        Matcher detail =
                Pattern.compile("/record/([^/]+)/([^/]+)(?:/(note|attachment))?").matcher(path);
        if (detail.matches())
            return record(c, detail.group(1), detail.group(2), detail.group(3), method);
        Matcher download = Pattern.compile("/attachment/([^/]+)").matcher(path);
        if (download.matches()) {
            method(method, "GET");
            var file = c.db.one("SELECT * FROM os_attachment WHERE sys_id=?", download.group(1));
            if (file == null) throw new Failure(404, "Attachment not found");
            disposition(c, (String) file.get("name"));
            return binary((byte[]) file.get("content"), (String) file.get("content_type"), 200);
        }
        if (path.equals("/migration")) return migration(c, method);
        Matcher export = Pattern.compile("/export/([^/]+)").matcher(path);
        if (export.matches()) {
            method(method, "GET");
            String table = export.group(1);
            Service.schema(c.db, table);
            var data = new ArrayList<Map<String, Object>>();
            for (var item :
                    c.db.all("SELECT * FROM os_record WHERE \"table\"=? ORDER BY seq", table)) {
                var original = object(item.get("original"));
                if (c.parameter("source", "").equals("true") && !original.isEmpty())
                    data.add(original);
                else {
                    var merged = new LinkedHashMap<>(original);
                    merged.putAll(Service.serialize(item));
                    data.add(merged);
                }
            }
            disposition(c, table + ".json");
            return json(map("result", data), 200);
        }
        if (path.equals("/audit")) {
            method(method, "GET");
            var events = c.db.all("SELECT * FROM os_event ORDER BY seq DESC LIMIT 200");
            events.forEach(event ->
                    event.put(
                            "detailText",
                            JSON.writerWithDefaultPrettyPrinter()
                                    .writeValueAsString(event.get("detail"))));
            return view(c, "audit", map("table", "audit", "events", events));
        }
        if (path.equals("/dictionary")) {
            method(method, "GET");
            return view(c, "dictionary", map("table", "dictionary"));
        }
        if (path.equals("/admin/accounts")) return accounts(c, method);
        throw new Failure(404, "Resource not found");
    }

    private ModelAndView record(Request c, String table, String id, String action, String method)
            throws Exception {
        var definition = Service.schema(c.db, table);
        var item = id.equals("new") ? null : Service.record(c.db, table, id);
        String location = "/record/" + table + "/" + id;
        if (action != null) {
            method(method, "POST");
            if (item == null) throw new Failure(404, "Record not found");
            if (action.equals("note")) {
                String note = c.parameter("body", "").strip();
                if (note.isEmpty() || note.codePointCount(0, note.length()) > 65536)
                    throw new Failure(400, "Enter a note of 1–65536 characters");
                Service.audit(c.db, item, c.name(), "note", map("body", note));
            } else {
                var file =
                        c.http instanceof MultipartHttpServletRequest multipart
                                ? multipart.getFile("file")
                                : null;
                if (file == null
                        || file.getOriginalFilename() == null
                        || file.getOriginalFilename().isEmpty())
                    throw new Failure(400, "Choose a file");
                if (file.getSize() > 5 * 1024 * 1024)
                    throw new Failure(400, "Attachments are limited to 5 MB");
                String name = filename(file.getOriginalFilename());
                c.db.update(
                        "INSERT INTO os_attachment(sys_id,record_id,name,content) VALUES(?,?,?,?)",
                        id(),
                        id,
                        name,
                        file.getBytes());
                Service.audit(c.db, item, c.name(), "attachment", map("name", name));
            }
            return redirect(location);
        }
        method(method, "GET", "POST");
        if (method.equals("POST")) {
            var payload = new LinkedHashMap<String, Object>();
            for (String field : object(definition.get("fields")).keySet())
                if (c.http.getParameter(field) != null)
                    payload.put(field, c.http.getParameter(field));
            if (item == null) item = Service.create(c.db, table, payload, c.name());
            else
                Service.update(
                        c.db,
                        table,
                        item,
                        payload,
                        c.name(),
                        c.role(),
                        c.http.getParameter("revision"));
            c.session.setAttribute("flash", "Record saved. Everything is up to date.");
            return redirect("/record/" + table + "/" + item.get("sys_id"));
        }
        var data = item == null ? map() : Service.serialize(item);
        var states = object(definition.get("states"));
        String state =
                (String)
                        data.getOrDefault(
                                "state", states.isEmpty() ? "" : states.keySet().iterator().next());
        var choices = new LinkedHashMap<String, Object>();
        for (var option : states.entrySet())
            if (option.getKey().equals(state)
                    || item != null
                            && ((List<?>)
                                            object(definition.get("flow"))
                                                    .getOrDefault(state, List.of()))
                                    .contains(option.getKey()))
                choices.put(option.getKey(), option.getValue());
        return view(
                c,
                "detail",
                map(
                        "table",
                        table,
                        "definition",
                        definition,
                        "item",
                        item,
                        "data",
                        data,
                        "stateChoices",
                        choices,
                        "currentState",
                        state,
                        "events",
                        item == null
                                ? List.of()
                                : c.db.all(
                                        "SELECT * FROM os_event WHERE record_id=? ORDER BY seq"
                                                + " DESC",
                                        id),
                        "attachments",
                        item == null
                                ? List.of()
                                : c.db.all(
                                        "SELECT sys_id,name FROM os_attachment WHERE record_id=?"
                                                + " ORDER BY sys_id",
                                        id)));
    }

    private ModelAndView accountDetail(Request c, String id, String method) throws Exception {
        method(method, "GET", "POST");
        c.admin();
        c.noQuery();
        c.uniqueQuery();
        var account = Service.account(c.db, id);
        String error = null;
        int status = 200;
        if (method.equals("POST")) {
            try {
                String operation = c.parameter("operation", "status");
                if (!Set.of("status", "role").contains(operation))
                    throw new Failure(400, "Choose an account operation");
                if (operation.equals("role")) {
                    Service.changeRole(
                            c.db,
                            c.user.get("user_id").toString(),
                            id,
                            map("role", c.parameter("role", "")));
                } else {
                    String active = c.parameter("active", "");
                    if (!Set.of("true", "false").contains(active))
                        throw new Failure(400, "Choose active or inactive");
                    Service.changeActive(
                            c.db,
                            c.user.get("user_id").toString(),
                            id,
                            map("active", Boolean.parseBoolean(active)));
                }
                return redirect("/admin/accounts/" + id);
            } catch (Failure failure) {
                error = failure.getMessage();
                status = failure.status;
            }
        }
        var result =
                view(
                        c,
                        "account-detail",
                        map(
                                "table",
                                "accounts",
                                "account",
                                account,
                                "error",
                                error,
                                "roles",
                                Service.ROLES));
        result.setStatus(HttpStatus.valueOf(status));
        return result;
    }

    private ModelAndView accounts(Request c, String method) throws Exception {
        method(method, "GET", "POST");
        c.admin();
        var args = queryArguments(c);
        if (!Set.of("page").containsAll(args.keySet())
                || !args.getOrDefault("page", "1").matches("[1-9][0-9]{0,5}"))
            throw new Failure(400, "Use a single positive page parameter of at most six digits");
        int page = Integer.parseInt(args.getOrDefault("page", "1"));
        long total = c.db.count("SELECT COUNT(*) FROM os_user"),
                pages = Math.max(1, (total + 24) / 25);
        if (page > pages) throw new Failure(400, "Page is outside the account list");
        var values = map("name", "", "role", "reader");
        String error = null;
        if (method.equals("POST")) {
            values = map("name", c.parameter("name", ""), "role", c.parameter("role", "reader"));
            try {
                var account =
                        Service.createAccount(
                                c.db,
                                map(
                                        "name",
                                        values.get("name"),
                                        "role",
                                        values.get("role"),
                                        "password",
                                        c.parameter("password", "")));
                c.session.setAttribute(
                        "flash",
                        "Account for " + account.get("name") + " created. They can now sign in.");
                return redirect("/admin/accounts?page=" + page);
            } catch (Failure failure) {
                if (failure.status != 400) throw failure;
                error = failure.getMessage();
            }
        }
        var result =
                view(
                        c,
                        "accounts",
                        map(
                                "table",
                                "accounts",
                                "accounts",
                                c.db.all(
                                        "SELECT user_id,name,role,active FROM os_user ORDER BY name"
                                                + " LIMIT 25 OFFSET ?",
                                        (page - 1) * 25),
                                "total",
                                total,
                                "page",
                                page,
                                "pages",
                                pages,
                                "roles",
                                Service.ROLES,
                                "values",
                                values,
                                "error",
                                error));
        if (error != null) result.setStatus(HttpStatus.BAD_REQUEST);
        return result;
    }

    private ModelAndView migration(Request c, String method) throws Exception {
        method(method, "GET", "POST");
        Map<String, Object> report = null;
        if (method.equals("POST")) {
            c.admin();
            var file =
                    c.http instanceof MultipartHttpServletRequest multipart
                            ? multipart.getFile("file")
                            : null;
            if (file == null) throw new Failure(400, "Choose a JSON or CSV export");
            Object rows;
            try {
                String text =
                        StandardCharsets.UTF_8
                                .newDecoder()
                                .onMalformedInput(CodingErrorAction.REPORT)
                                .decode(ByteBuffer.wrap(file.getBytes()))
                                .toString()
                                .replaceFirst("^\uFEFF", "");
                if (Objects.toString(file.getOriginalFilename(), "")
                        .toLowerCase(Locale.ROOT)
                        .endsWith(".csv")) {
                    var data = new ArrayList<Map<String, String>>();
                    try (var parser =
                            CSVParser.parse(
                                    text,
                                    CSVFormat.DEFAULT
                                            .builder()
                                            .setHeader()
                                            .setSkipHeaderRecord(true)
                                            .get())) {
                        for (var row : parser) data.add(row.toMap());
                    }
                    rows = data;
                } else rows = JSON.readValue(text, Object.class);
            } catch (java.io.IOException | RuntimeException error) {
                throw new Failure(400, "The file is not valid UTF-8 JSON or CSV");
            }
            report =
                    Service.migrate(
                            c.db,
                            c.parameter("table", "incident"),
                            rows,
                            c.name(),
                            !c.parameter("mode", "").equals("import"));
        }
        return view(
                c,
                "migration",
                map(
                        "table",
                        "migration",
                        "report",
                        report,
                        "destination",
                        c.parameter("table", "incident")));
    }

    private ModelAndView attachments(Request c, String path, String method) throws Exception {
        if (path.equals("/api/now/attachment/file")) {
            method(method, "POST");
            if (c.http instanceof MultipartHttpServletRequest)
                throw new Failure(415, "Send raw file bytes to this endpoint");
            if (!Set.of("table_name", "table_sys_id", "file_name").equals(c.arguments().keySet()))
                throw new Failure(400, "Supply table_name, table_sys_id and file_name only");
            return saveAttachment(
                    c,
                    c.parameter("table_name", ""),
                    c.parameter("table_sys_id", ""),
                    c.parameter("file_name", ""),
                    c.http.getContentType(),
                    c.http.getInputStream().readNBytes(5 * 1024 * 1024 + 1));
        }
        if (path.equals("/api/now/attachment/upload")) {
            method(method, "POST");
            c.noQuery();
            if (!(c.http instanceof MultipartHttpServletRequest multipart))
                throw new Failure(415, "Multipart form data is required");
            if (!Set.of("table_name", "table_sys_id").equals(c.arguments().keySet()))
                throw new Failure(400, "Supply table_name and table_sys_id form fields only");
            var files = multipart.getFiles("uploadFile");
            if (!multipart.getMultiFileMap().keySet().equals(Set.of("uploadFile"))
                    || files.size() != 1)
                throw new Failure(400, "Supply exactly one file part named uploadFile");
            var file = files.getFirst();
            return saveAttachment(
                    c,
                    c.parameter("table_name", ""),
                    c.parameter("table_sys_id", ""),
                    file.getOriginalFilename(),
                    file.getContentType(),
                    file.getBytes());
        }
        String select =
                "SELECT a.sys_id,a.name AS file_name,a.record_id AS table_sys_id,r.\"table\" AS"
                        + " table_name,a.content_type,length(a.content) AS size_bytes";
        String from = " FROM os_attachment a JOIN os_record r ON r.sys_id=a.record_id";
        if (path.equals("/api/now/attachment")) {
            method(method, "GET");
            c.uniqueQuery();
            var args = c.arguments();
            if (!Set.of("sysparm_query", "sysparm_limit", "sysparm_offset")
                    .containsAll(args.keySet()))
                throw new Failure(400, "Unsupported attachment parameter");
            int limit = Service.number(c.parameter("sysparm_limit", "1000"), 1, 1000),
                    offset =
                            Service.number(
                                    c.parameter("sysparm_offset", "0"), 0, Integer.MAX_VALUE);
            var where = new ArrayList<String>();
            var ordering = new ArrayList<String>();
            var parameters = new ArrayList<Object>();
            var columns =
                    Map.of(
                            "sys_id",
                            "a.sys_id",
                            "file_name",
                            "a.name",
                            "table_sys_id",
                            "a.record_id",
                            "table_name",
                            "r.\"table\"");
            String encoded = c.parameter("sysparm_query", "");
            if (!encoded.isEmpty())
                for (String term : encoded.split("\\^", -1)) {
                    if (term.startsWith("ORDERBY")) {
                        boolean descending = term.startsWith("ORDERBYDESC");
                        String field = term.substring(descending ? 11 : 7);
                        if (!columns.containsKey(field))
                            throw new Failure(400, "Unsupported attachment ordering field");
                        ordering.add(columns.get(field) + (descending ? " DESC" : " ASC"));
                        continue;
                    }
                    int equals = term.indexOf('=');
                    if (equals < 0 || !columns.containsKey(term.substring(0, equals)))
                        throw new Failure(
                                400,
                                "Use equality on sys_id, file_name, table_sys_id or table_name,"
                                        + " joined by ^");
                    where.add(columns.get(term.substring(0, equals)) + "=?");
                    parameters.add(term.substring(equals + 1));
                }
            ordering.add("a.sys_id");
            parameters.add(limit);
            parameters.add(offset);
            var result = new ArrayList<Map<String, Object>>();
            for (var row :
                    c.db.all(
                            select
                                    + from
                                    + (where.isEmpty()
                                            ? ""
                                            : " WHERE " + String.join(" AND ", where))
                                    + " ORDER BY "
                                    + String.join(",", ordering)
                                    + " LIMIT ? OFFSET ?",
                            parameters.toArray())) result.add(metadata(c, row));
            return json(map("result", result), 200);
        }
        Matcher route = Pattern.compile("/api/now/attachment/([^/]+)(/file)?").matcher(path);
        if (!route.matches()) throw new Failure(404, "Resource not found");
        c.noQuery();
        boolean content = route.group(2) != null;
        method(method, content ? new String[] {"GET"} : new String[] {"GET", "DELETE"});
        if (method.equals("DELETE")) c.admin();
        var row =
                c.db.one(
                        select + (content ? ",a.content" : "") + from + " WHERE a.sys_id=?",
                        route.group(1));
        if (row == null) throw new Failure(404, "Attachment not found");
        if (method.equals("DELETE")) {
            var parent =
                    Service.record(
                            c.db, (String) row.get("table_name"), (String) row.get("table_sys_id"));
            c.db.update("DELETE FROM os_attachment WHERE sys_id=?", row.get("sys_id"));
            Service.audit(
                    c.db,
                    parent,
                    c.name(),
                    "attachment_deleted",
                    map("sys_id", row.get("sys_id"), "name", row.get("file_name")));
            return binary(new byte[0], "application/json", 204);
        }
        var metadata = metadata(c, row);
        if (!content) return json(map("result", metadata), 200);
        disposition(c, (String) row.get("file_name"));
        c.response.setHeader("X-Attachment-Metadata", JSON.writeValueAsString(metadata));
        return binary((byte[]) row.get("content"), (String) row.get("content_type"), 200);
    }

    private ModelAndView saveAttachment(
            Request c, String table, String parent, String originalName, String type, byte[] bytes)
            throws Exception {
        for (String value : Arrays.asList(table, parent, originalName))
            if (value == null || value.isBlank())
                throw new Failure(400, "Attachment fields must not be blank");
        try {
            if (type == null
                    || type.length() > 255
                    || !org.springframework.http.MediaType.parseMediaType(type).isConcrete())
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException error) {
            throw new Failure(415, "A concrete Content-Type of at most 255 characters is required");
        }
        if (bytes.length > 5 * 1024 * 1024)
            throw new Failure(413, "Attachments are limited to 5 MB");
        var item = Service.record(c.db, table, parent);
        String name = filename(originalName), id = id();
        c.db.update(
                "INSERT INTO os_attachment(sys_id,record_id,name,content,content_type)"
                        + " VALUES(?,?,?,?,?)",
                id,
                item.get("sys_id"),
                name,
                bytes,
                type);
        Service.audit(c.db, item, c.name(), "attachment", map("name", name));
        var row =
                map(
                        "sys_id",
                        id,
                        "file_name",
                        name,
                        "table_sys_id",
                        item.get("sys_id"),
                        "table_name",
                        item.get("table"),
                        "size_bytes",
                        bytes.length,
                        "content_type",
                        type);
        c.response.setHeader("Location", c.base() + "/api/now/attachment/" + id);
        return json(map("result", metadata(c, row)), 201);
    }

    private static Map<String, Object> metadata(Request c, Map<String, Object> row) {
        return map(
                "sys_id",
                row.get("sys_id"),
                "file_name",
                row.get("file_name"),
                "table_sys_id",
                row.get("table_sys_id"),
                "table_name",
                row.get("table_name"),
                "size_bytes",
                row.get("size_bytes").toString(),
                "content_type",
                row.get("content_type"),
                "download_link",
                c.base() + "/api/now/attachment/" + row.get("sys_id") + "/file");
    }

    private ModelAndView view(Request c, String name, Map<String, Object> data) throws Exception {
        var counts = new LinkedHashMap<String, Object>();
        for (var count :
                c.db.all("SELECT \"table\",COUNT(*) AS total FROM os_record GROUP BY \"table\""))
            counts.put((String) count.get("table"), count.get("total"));
        var model =
                map(
                        "tables",
                        c.db.all("SELECT * FROM os_schema ORDER BY name"),
                        "counts",
                        counts,
                        "user",
                        map("name", c.name(), "role", c.role()),
                        "csrf",
                        c.session.getAttribute("csrf"),
                        "flash",
                        c.session.getAttribute("flash"),
                        "overview",
                        false,
                        "priorities",
                        map(
                                "1",
                                "Critical",
                                "2",
                                "High",
                                "3",
                                "Moderate",
                                "4",
                                "Low",
                                "5",
                                "Planning"));
        c.session.removeAttribute("flash");
        model.putAll(data);
        return new ModelAndView(name, model);
    }

    private static Map<String, String> queryArguments(Request c) {
        var result = new LinkedHashMap<String, String>();
        String query = c.http.getQueryString();
        if (query == null || query.isEmpty()) return result;
        for (String field : query.split("&")) {
            String[] pair = field.split("=", 2);
            String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                    value =
                            pair.length == 2
                                    ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8)
                                    : "";
            if (result.putIfAbsent(key, value) != null)
                throw new Failure(400, "Repeated query parameter");
        }
        return result;
    }

    private static String query(Map<String, String> arguments) {
        return String.join(
                "&",
                arguments.entrySet().stream()
                        .map(e ->
                                URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                                        + "="
                                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                        .toList());
    }

    static String filename(String name) {
        String clean =
                Normalizer.normalize(name, Normalizer.Form.NFKD)
                        .replaceAll("[^\\x00-\\x7F]", "")
                        .replace('/', ' ')
                        .strip()
                        .replaceAll("\\s+", "_")
                        .replaceAll("[^A-Za-z0-9_.-]", "")
                        .replaceAll("^[._]+|[._]+$", "");
        return clean.isEmpty() ? "attachment" : clean.substring(0, Math.min(255, clean.length()));
    }

    private static void disposition(Request c, String filename) {
        c.response.setHeader(
                "Content-Disposition",
                "attachment; filename=\""
                        + filename.replace("\"", "").replace("\r", "").replace("\n", "")
                        + "\"");
    }

    private static ModelAndView redirect(String location) {
        var result = new ModelAndView("redirect:" + location);
        result.setStatus(HttpStatus.FOUND);
        return result;
    }

    private static ModelAndView json(Object value, int status) {
        return binary(JSON.writeValueAsBytes(value), "application/json", status);
    }

    private static ModelAndView binary(byte[] bytes, String type, int status) {
        View view =
                (model, request, response) -> {
                    response.setCharacterEncoding((String) null);
                    response.setContentType(type);
                    response.setContentLength(bytes.length);
                    response.getOutputStream().write(bytes);
                };
        var result = new ModelAndView(view);
        result.setStatus(HttpStatus.valueOf(status));
        return result;
    }

    private static void method(String actual, String... accepted) {
        if (!List.of(accepted).contains(actual)) throw new Failure(405, "Method not allowed");
    }
}
