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
        response.setHeader("Referrer-Policy", "no-referrer");
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
            boolean publicPath =
                    Set.of("/healthz", "/login", "/static/app.css").contains(path)
                            || path.matches("/(?:join|recover)/[A-Za-z0-9_-]{32}");
            var user =
                    session.getAttribute("user") == null
                            ? null
                            : db.one(
                                    "SELECT * FROM os_user WHERE user_id=? AND active=1",
                                    session.getAttribute("user"));
            if (user != null
                    && (!Objects.equals(
                                    session.getAttribute("auth_revision"),
                                    ((Number) user.get("auth_revision")).longValue())
                            || !SessionLifetime.valid(
                                    session.getAttribute("authenticated_at"),
                                    System.currentTimeMillis()))) {
                session.invalidate();
                session = request.getSession(true);
                session.setAttribute("csrf", csrf());
                user = null;
            }
            String authorization = request.getHeader("Authorization");
            if (!publicPath && authorization != null && !authorization.isEmpty())
                user = authenticate(db, authorization);
            if (!publicPath && user == null) {
                if (path.startsWith("/api/")) throw new Failure(401, "Authentication required");
                return redirect("/login");
            }
            db.viewerId = user == null ? "" : user.get("user_id").toString();
            db.viewerRole = user == null ? "" : user.get("role").toString();
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
                                        "/account/password",
                                        "/api/openservice/me/password",
                                        "/api/openservice/me/sessions/revoke")
                                .contains(path)
                        && !path.matches(
                                "/(?:api/openservice/comments/[^/]+/[0-9a-f]{32}|record/[^/]+/[0-9a-f]{32}/comment)"))
                    throw new Failure(403, "Read-only account");
                // Configuration promotion, record writes and relationship deletion share a lock.
                if (db.postgres) db.all("SELECT pg_advisory_xact_lock(18439,4)");
                Authentication.requireCurrent(db, user);
            }
            var result = route(context, path, method.equals("HEAD") ? "GET" : method);
            if (result.getStatus() != null && result.getStatus().value() >= 400)
                db.connection.rollback();
            else db.connection.commit();
            return result;
        } catch (Failure error) {
            db.connection.rollback();
            response.setStatus(error.status);
            if (error.status == 429) response.setHeader("Retry-After", "300");
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
            if (pair.length != 2) throw new Failure(401, "Invalid credentials");
            var user = Authentication.verify(db, pair[0], pair[1], "basic");
            if (user == null) throw new Failure(401, "Invalid credentials");
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
        if (path.equals("/static/migrations.js")) {
            method(method, "GET");
            try (var input = WebController.class.getResourceAsStream("/static/migrations.js")) {
                return binary(
                        Objects.requireNonNull(input).readAllBytes(),
                        "text/javascript; charset=utf-8",
                        200);
            }
        }
        if (path.equals("/admin/migrations")) {
            method(method, "GET");
            c.admin();
            c.noQuery();
            return view(c, "transfers", map("table", "migration"));
        }
        if (path.equals("/api/openservice/migrations")) {
            c.admin();
            c.noQuery();
            method(method, "GET", "POST");
            return json(
                    map(
                            "result",
                            method.equals("GET")
                                    ? TransferJobs.list(c.db)
                                    : TransferJobs.create(
                                            c.db, body(c), c.db.viewerId, store.migrationOrigins)),
                    method.equals("GET") ? 200 : 201);
        }
        Matcher transfer =
                Pattern.compile(
                                "/api/openservice/migrations/([a-f0-9]{32})(?:/(step|preview|apply|sync|cutover|archive))?")
                        .matcher(path);
        if (transfer.matches()) {
            c.admin();
            String job = transfer.group(1), action = Objects.toString(transfer.group(2), "");
            if (action.equals("archive")) {
                method(method, "GET");
                if (!Set.of("table", "after").containsAll(c.arguments().keySet()))
                    throw new Failure(400, "Use table and after only");
                var definition = object(TransferJobs.job(c.db, job).get("definition"));
                String table = c.parameter("table", ""), cursor = c.parameter("after", "");
                if (!TransferJobs.phases(definition).contains(table)
                        || !cursor.matches("(?:[a-f0-9]{32})?"))
                    throw new Failure(400, "Choose a staged table and valid cursor");
                return json(
                        map(
                                "result",
                                c.db.all(
                                        "SELECT source_id,data FROM os_transfer_item WHERE job_id=?"
                                            + " AND source_table=? AND source_id>? ORDER BY"
                                            + " source_id LIMIT 100",
                                        job,
                                        table,
                                        cursor)),
                        200);
            }
            c.noQuery();
            if (action.isEmpty()) {
                method(method, "GET");
                return json(map("result", TransferJobs.describe(c.db, job)), 200);
            }
            method(method, "POST");
            Object input = body(c);
            if (!action.equals("cutover") && !object(input).isEmpty())
                throw new Failure(400, "This action takes an empty object");
            Object result =
                    switch (action) {
                        case "step" ->
                                TransferJobs.step(
                                        c.db,
                                        job,
                                        Objects.toString(c.http.getHeader("X-Source-Token"), ""),
                                        store.migrationOrigins);
                        case "preview" -> TransferApply.apply(c.db, job, c.db.viewerId, true);
                        case "apply" -> TransferApply.apply(c.db, job, c.db.viewerId, false);
                        case "sync" ->
                                TransferJobs.synchronize(
                                        c.db, job, c.db.viewerId, store.migrationOrigins);
                        default -> TransferJobs.cutover(c.db, job, input, c.db.viewerId);
                    };
            return json(map("result", result), 200);
        }
        Matcher recovering = Pattern.compile("/recover/([A-Za-z0-9_-]{32})").matcher(path);
        if (recovering.matches()) {
            method(method, "GET", "POST");
            c.noQuery();
            c.uniqueQuery();
            var recovery = RecoveryLinks.describe(c.db, recovering.group(1));
            String error = null;
            int status = 200;
            if (method.equals("POST")) {
                checkCsrf(c, c.parameter("csrf", ""));
                try {
                    RecoveryLinks.recover(c.db, recovering.group(1), c.parameter("password", ""));
                    c.session.invalidate();
                    return redirect("/login");
                } catch (Failure failure) {
                    error = failure.getMessage();
                    status = failure.status;
                }
            }
            return new ModelAndView(
                    "recover",
                    map(
                            "recovery",
                            recovery,
                            "csrf",
                            c.session.getAttribute("csrf"),
                            "error",
                            error),
                    HttpStatus.valueOf(status));
        }
        Matcher joining = Pattern.compile("/join/([A-Za-z0-9_-]{32})").matcher(path);
        if (joining.matches()) {
            method(method, "GET", "POST");
            c.noQuery();
            c.uniqueQuery();
            var invitation = Invitations.describe(c.db, joining.group(1));
            String error = null;
            int status = 200;
            if (method.equals("POST")) {
                checkCsrf(c, c.parameter("csrf", ""));
                try {
                    Invitations.accept(c.db, joining.group(1), c.parameter("password", ""));
                    c.session.invalidate();
                    return redirect("/login");
                } catch (Failure failure) {
                    error = failure.getMessage();
                    status = failure.status;
                }
            }
            return new ModelAndView(
                    "join",
                    map(
                            "invitation",
                            invitation,
                            "csrf",
                            c.session.getAttribute("csrf"),
                            "error",
                            error),
                    HttpStatus.valueOf(status));
        }
        if (path.equals("/login")) {
            method(method, "GET", "POST");
            if (method.equals("POST")) {
                checkCsrf(c, c.parameter("csrf", ""));
                var user =
                        Authentication.verify(
                                c.db,
                                c.parameter("username", ""),
                                c.parameter("password", ""),
                                "browser");
                if (user == null) throw new Failure(401, "Incorrect username or password");
                c.http.changeSessionId();
                Collections.list(c.session.getAttributeNames()).forEach(c.session::removeAttribute);
                c.session.setAttribute("user", user.get("user_id"));
                c.session.setAttribute(
                        "auth_revision", ((Number) user.get("auth_revision")).longValue());
                c.session.setAttribute("authenticated_at", System.currentTimeMillis());
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
            return view(c, "account", map("table", "account", "error", null));
        }
        if (Set.of("/account/password", "/api/openservice/me/password").contains(path)) {
            method(method, "POST");
            c.noQuery();
            c.uniqueQuery();
            boolean api = path.startsWith("/api/");
            try {
                Service.changePassword(
                        c.db,
                        c.user.get("user_id").toString(),
                        api
                                ? body(c)
                                : map(
                                        "current_password",
                                        c.parameter("current_password", ""),
                                        "new_password",
                                        c.parameter("new_password", "")));
                c.session.invalidate();
                return api ? json(map("result", map("changed", true)), 200) : redirect("/login");
            } catch (Failure failure) {
                if (api) throw failure;
                var result =
                        view(c, "account", map("table", "account", "error", failure.getMessage()));
                result.setStatus(HttpStatus.valueOf(failure.status));
                return result;
            }
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
            SecurityEvents.record(
                    c.db,
                    c.user.get("user_id").toString(),
                    c.user.get("user_id").toString(),
                    "account.sessions_revoked",
                    map());
            c.session.invalidate();
            return api ? json(map("result", map("revoked", true)), 200) : redirect("/login");
        }
        Matcher membership =
                Pattern.compile(
                                "/api/openservice/groups/([0-9a-f]{32})/members(?:/([0-9a-f]{32}))?")
                        .matcher(path);
        if (membership.matches()) {
            c.admin();
            c.noQuery();
            String group = membership.group(1), member = membership.group(2);
            method(method, member == null ? new String[] {"GET", "PUT"} : new String[] {"DELETE"});
            Service.record(c.db, "sys_user_group", group);
            var result =
                    method.equals("GET")
                            ? GroupMembers.list(c.db, group)
                            : GroupMembers.change(
                                    c.db,
                                    group,
                                    c.user.get("user_id").toString(),
                                    member == null ? body(c) : map("user_id", member),
                                    method.equals("PUT"));
            return json(map("result", result), 200);
        }
        Matcher memberForm = Pattern.compile("/group-members/([0-9a-f]{32})").matcher(path);
        if (memberForm.matches()) {
            method(method, "POST");
            c.admin();
            c.noQuery();
            c.uniqueQuery();
            String operation = c.parameter("operation", "");
            if (!Set.of("add", "remove").contains(operation))
                throw new Failure(400, "Choose add or remove");
            boolean add = operation.equals("add");
            String member =
                    add
                            ? RecordAccess.owner(c.db, c.parameter("username", ""))
                            : c.parameter("user_id", "");
            GroupMembers.change(
                    c.db,
                    memberForm.group(1),
                    c.user.get("user_id").toString(),
                    map("user_id", member),
                    add);
            return redirect("/record/sys_user_group/" + memberForm.group(1));
        }
        Matcher access =
                Pattern.compile("/(api/openservice/access|record-access)/([^/]+)/([0-9a-f]{32})")
                        .matcher(path);
        if (access.matches()) {
            boolean api = access.group(1).startsWith("api/");
            method(method, api ? "PATCH" : "POST");
            c.admin();
            c.noQuery();
            c.uniqueQuery();
            var item = Service.record(c.db, access.group(2), access.group(3));
            Object change =
                    api
                            ? body(c)
                            : c.parameter("visibility", "").equals("group")
                                    ? map(
                                            "visibility",
                                            "group",
                                            "group_id",
                                            c.parameter("group_id", ""))
                                    : map(
                                            "visibility",
                                            c.parameter("visibility", ""),
                                            "owner_id",
                                            c.parameter("visibility", "").equals("private")
                                                    ? RecordAccess.owner(
                                                            c.db, c.parameter("owner", ""))
                                                    : "");
            var result = RecordAccess.change(c.db, item, c.user.get("user_id").toString(), change);
            return api
                    ? json(map("result", result), 200)
                    : redirect("/record/" + access.group(2) + "/" + access.group(3));
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
        Matcher permissions =
                Pattern.compile("/api/openservice/permissions/([^/]+)/([0-9a-f]{32})")
                        .matcher(path);
        if (permissions.matches()) {
            method(method, "GET");
            c.noQuery();
            return json(
                    map(
                            "result",
                            Permissions.inspect(c.db, permissions.group(1), permissions.group(2))),
                    200);
        }
        Matcher comments =
                Pattern.compile("/api/openservice/comments/([^/]+)/([0-9a-f]{32})").matcher(path);
        if (comments.matches()) {
            method(method, "GET", "POST");
            c.noQuery();
            var item = Service.record(c.db, comments.group(1), comments.group(2));
            if (method.equals("POST"))
                return json(map("result", Comments.add(c.db, item, body(c), c.name())), 201);
            return json(map("result", Comments.list(c.db, comments.group(2))), 200);
        }
        Matcher analytics =
                Pattern.compile("/(api/openservice/analytics|analytics)/([^/]+)").matcher(path);
        if (analytics.matches()) {
            method(method, "GET");
            c.uniqueQuery();
            String table = analytics.group(2);
            if (analytics.group(1).startsWith("api/"))
                return json(map("result", Analytics.report(c.db, table, c.arguments())), 200);
            var definition = Service.schema(c.db, table);
            var fields = Analytics.fields(definition);
            var args = new LinkedHashMap<>(c.arguments());
            args.putIfAbsent("field", fields.isEmpty() ? "" : fields.keySet().iterator().next());
            var report =
                    fields.isEmpty() ? map("rows", List.of()) : Analytics.report(c.db, table, args);
            return view(
                    c,
                    "analytics",
                    map(
                            "table",
                            table,
                            "definition",
                            definition,
                            "metricFields",
                            fields,
                            "metric",
                            args.get("field"),
                            "groupBy",
                            c.parameter("group_by", ""),
                            "filter",
                            c.parameter("q", ""),
                            "report",
                            report));
        }
        Matcher stats = Pattern.compile("/api/now/stats/([^/]+)").matcher(path);
        if (stats.matches()) {
            method(method, "GET");
            c.uniqueQuery();
            return json(map("result", Service.stats(c.db, stats.group(1), c.arguments())), 200);
        }
        if (path.equals("/api/openservice/configuration")) {
            method(method, "GET", "POST");
            c.admin();
            if (method.equals("GET")) {
                c.noQuery();
                disposition(c, "openservice-configuration.json");
                return json(Configuration.export(c.db), 200);
            }
            if (!Set.of("dry_run").containsAll(c.arguments().keySet())
                    || !Set.of("true", "false").contains(c.parameter("dry_run", "true")))
                throw new Failure(400, "Use dry_run=true or false");
            return json(
                    map(
                            "result",
                            Configuration.install(
                                    c.db,
                                    body(c),
                                    c.user.get("user_id").toString(),
                                    c.parameter("dry_run", "true").equals("true"))),
                    200);
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
        if (path.equals("/admin/invitations")) return invitations(c, method);
        if (path.equals("/api/openservice/invitations")) {
            method(method, "GET", "POST");
            c.admin();
            c.noQuery();
            return method.equals("POST")
                    ? json(
                            map(
                                    "result",
                                    Invitations.issue(
                                            c.db, c.user.get("user_id").toString(), body(c))),
                            201)
                    : json(map("result", Invitations.latest(c.db)), 200);
        }
        Matcher revokeInvite =
                Pattern.compile("/(?:admin|api/openservice)/invitations/([0-9a-f]{32})/revoke")
                        .matcher(path);
        if (revokeInvite.matches()) {
            method(method, "POST");
            c.admin();
            c.noQuery();
            c.uniqueQuery();
            boolean api = path.startsWith("/api/");
            if (api && !object(body(c)).isEmpty())
                throw new Failure(400, "Provide an empty JSON object");
            var result =
                    Invitations.revoke(
                            c.db, c.user.get("user_id").toString(), revokeInvite.group(1));
            return api ? json(map("result", result), 200) : redirect("/admin/invitations");
        }
        if (Set.of(
                        "/admin/authentication-events",
                        "/api/openservice/authentication-events",
                        "/admin/security-events",
                        "/api/openservice/security-events")
                .contains(path)) {
            method(method, "GET");
            c.admin();
            boolean authentication = path.endsWith("authentication-events");
            var journal =
                    authentication
                            ? AuthenticationEvents.page(c.db, queryArguments(c))
                            : SecurityEvents.page(c.db, queryArguments(c));
            boolean download = (Boolean) journal.get("download");
            if (download)
                disposition(
                        c, authentication ? "authentication-events.json" : "security-events.json");
            if (path.startsWith("/api/") || download)
                return json(
                        map(
                                "result",
                                journal.get("result"),
                                "next_before",
                                journal.get("next_before")),
                        200);
            String before = journal.get("before").toString();
            return view(
                    c,
                    authentication ? "authentication-events" : "security-events",
                    map(
                            "table",
                            "security",
                            "events",
                            journal.get("result"),
                            "nextBefore",
                            journal.get("next_before"),
                            "downloadQuery",
                            before.isEmpty() ? "download=true" : "download=true&before=" + before));
        }
        Matcher recoveryIssue =
                Pattern.compile("/api/openservice/users/([0-9a-f]{32})/recovery").matcher(path);
        if (recoveryIssue.matches()) {
            method(method, "POST");
            c.admin();
            c.noQuery();
            return json(
                    map(
                            "result",
                            RecoveryLinks.issue(
                                    c.db,
                                    c.user.get("user_id").toString(),
                                    recoveryIssue.group(1),
                                    body(c))),
                    201);
        }
        Matcher recoveryRevoke =
                Pattern.compile(
                                "/(?:admin/accounts|api/openservice/users)/([0-9a-f]{32})/recovery/revoke")
                        .matcher(path);
        if (recoveryRevoke.matches()) {
            method(method, "POST");
            c.admin();
            c.noQuery();
            c.uniqueQuery();
            boolean api = path.startsWith("/api/");
            if (api && !object(body(c)).isEmpty())
                throw new Failure(400, "Provide an empty JSON object");
            RecoveryLinks.revoke(c.db, c.user.get("user_id").toString(), recoveryRevoke.group(1));
            return api
                    ? json(map("result", map("revoked", true)), 200)
                    : redirect("/admin/accounts/" + recoveryRevoke.group(1));
        }
        Matcher transferAssignments =
                Pattern.compile(
                                "/(?:admin/accounts|api/openservice/users)/([0-9a-f]{32})/assignments/transfer")
                        .matcher(path);
        if (transferAssignments.matches()) {
            method(method, "POST");
            c.admin();
            boolean api = path.startsWith("/api/");
            Object input;
            boolean dryRun;
            if (api) {
                if (!Set.of("dry_run").containsAll(c.arguments().keySet())
                        || !Set.of("true", "false").contains(c.parameter("dry_run", "false")))
                    throw new Failure(400, "Use dry_run=true or false");
                dryRun = c.parameter("dry_run", "false").equals("true");
                input = body(c);
            } else {
                c.noQuery();
                String operation = c.parameter("operation", "transfer");
                if (!Set.of("preview", "transfer").contains(operation))
                    throw new Failure(400, "Choose preview or transfer");
                dryRun = operation.equals("preview");
                if (c.http.getParameterMap().entrySet().stream()
                        .anyMatch(entry ->
                                !entry.getKey().equals("record_ids")
                                        && entry.getValue().length != 1))
                    throw new Failure(400, "Repeated scalar form parameter");
                String name = c.parameter("new_owner", "");
                if (!name.matches("[A-Za-z0-9]{1,80}"))
                    throw new Failure(400, "Choose an active account username");
                String[] selected = c.http.getParameterValues("record_ids");
                List<String> selections = selected == null ? List.of() : Arrays.asList(selected);
                var revisions = new LinkedHashMap<String, Object>();
                for (String selection : selections)
                    revisions.put(selection, c.parameter("revision_" + selection, ""));
                input =
                        map(
                                "new_owner_id",
                                RecordAccess.owner(c.db, name),
                                "record_ids",
                                selections,
                                "expected_revisions",
                                revisions);
            }
            var report =
                    Assignments.transfer(
                            c.db,
                            c.user.get("user_id").toString(),
                            transferAssignments.group(1),
                            input,
                            dryRun);
            if (!api && dryRun) {
                var page =
                        Assignments.page(
                                c.db,
                                transferAssignments.group(1),
                                Map.of("page", c.parameter("page", "1")));
                page.putAll(
                        map(
                                "table",
                                "accounts",
                                "preview",
                                report,
                                "newOwner",
                                c.parameter("new_owner", "")));
                return view(c, "assignments", page);
            }
            return api
                    ? json(map("result", report), 200)
                    : redirect("/admin/accounts/" + transferAssignments.group(1) + "/assignments");
        }
        Matcher assignments =
                Pattern.compile(
                                "/(?:admin/accounts|api/openservice/users)/([0-9a-f]{32})/assignments")
                        .matcher(path);
        if (assignments.matches()) {
            method(method, "GET");
            c.admin();
            c.uniqueQuery();
            var report = Assignments.page(c.db, assignments.group(1), c.arguments());
            if (path.startsWith("/api/")) return json(map("result", report), 200);
            report.put("table", "accounts");
            return view(c, "assignments", report);
        }
        Matcher transferPrivate =
                Pattern.compile(
                                "/(?:admin/accounts|api/openservice/users)/([0-9a-f]{32})/private-records/transfer")
                        .matcher(path);
        if (transferPrivate.matches()) {
            method(method, "POST");
            c.admin();
            c.noQuery();
            c.uniqueQuery();
            boolean api = path.startsWith("/api/");
            Object input;
            if (api) input = body(c);
            else {
                String name = c.parameter("new_owner", "");
                if (!name.matches("[A-Za-z0-9]{1,80}"))
                    throw new Failure(400, "Choose an active account username");
                input = map("new_owner_id", RecordAccess.owner(c.db, name));
            }
            var result =
                    PrivateRecords.transfer(
                            c.db,
                            c.user.get("user_id").toString(),
                            transferPrivate.group(1),
                            input);
            return api
                    ? json(map("result", result), 200)
                    : redirect("/admin/accounts/" + transferPrivate.group(1));
        }
        Matcher accountMemberships =
                Pattern.compile("/api/openservice/users/([0-9a-f]{32})/memberships").matcher(path);
        if (accountMemberships.matches()) {
            method(method, "GET");
            c.admin();
            c.noQuery();
            return json(
                    map("result", GroupMembers.forUser(c.db, accountMemberships.group(1))), 200);
        }
        Matcher removeMemberships =
                Pattern.compile(
                                "/(?:admin/accounts|api/openservice/users)/([0-9a-f]{32})/memberships/remove")
                        .matcher(path);
        if (removeMemberships.matches()) {
            method(method, "POST");
            c.admin();
            c.noQuery();
            c.uniqueQuery();
            boolean api = path.startsWith("/api/");
            if (api && !object(body(c)).isEmpty())
                throw new Failure(400, "Provide an empty JSON object");
            var result =
                    GroupMembers.removeForUser(
                            c.db, c.user.get("user_id").toString(), removeMemberships.group(1));
            return api
                    ? json(map("result", result), 200)
                    : redirect("/admin/accounts/" + removeMemberships.group(1));
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
            method(method, "GET", "POST");
            c.admin();
            if (method.equals("GET")) {
                var directory = AccountDirectory.list(c.db, queryArguments(c));
                return json(
                        map(
                                "result",
                                directory.get("accounts"),
                                "page",
                                directory.get("page"),
                                "pages",
                                directory.get("pages"),
                                "total",
                                directory.get("total")),
                        200);
            }
            return json(
                    map(
                            "result",
                            Service.createManagedAccount(
                                    c.db, body(c), c.user.get("user_id").toString())),
                    201);
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
                            "canCreateRecord",
                            !c.role().equals("reader")
                                    && RecordPolicy.allows(definition, "create", c.role()),
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
                Pattern.compile("/record/([^/]+)/([^/]+)(?:/(note|attachment|comment))?")
                        .matcher(path);
        if (detail.matches())
            return record(c, detail.group(1), detail.group(2), detail.group(3), method);
        Matcher download = Pattern.compile("/attachment/([^/]+)").matcher(path);
        if (download.matches()) {
            method(method, "GET");
            var file = c.db.one("SELECT * FROM os_attachment WHERE sys_id=?", download.group(1));
            if (file == null) throw new Failure(404, "Attachment not found");
            var allowed = new ArrayList<Object>(List.of(file.get("record_id")));
            if (c.db.one(
                            "SELECT sys_id FROM os_record WHERE sys_id=? AND "
                                    + RecordAccess.filter(c.db, "os_record.sys_id", allowed),
                            allowed.toArray())
                    == null) throw new Failure(404, "Attachment not found");
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
            var parameters = new ArrayList<Object>(List.of(table));
            String visible = RecordAccess.filter(c.db, "os_record.sys_id", parameters);
            for (var item :
                    c.db.all(
                            "SELECT * FROM os_record WHERE \"table\"=? AND "
                                    + visible
                                    + " ORDER BY seq",
                            parameters.toArray())) {
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
            var parameters = new ArrayList<Object>();
            var events =
                    c.db.all(
                            "SELECT * FROM os_event WHERE "
                                    + RecordAccess.filter(c.db, "os_event.record_id", parameters)
                                    + " AND "
                                    + RecordAccess.journalFilter(c.db)
                                    + " ORDER BY seq DESC LIMIT 200",
                            parameters.toArray());
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
        if (path.equals("/admin/configuration")) {
            method(method, "GET", "POST");
            c.admin();
            Map<String, Object> report = null;
            String document = c.parameter("document", "");
            if (method.equals("POST")) {
                if (!Set.of("preview", "install").contains(c.parameter("operation", "")))
                    throw new Failure(400, "Choose preview or install");
                Object packageBody;
                try {
                    packageBody = JSON.readValue(document, Object.class);
                } catch (tools.jackson.core.JacksonException invalid) {
                    throw new Failure(400, "Enter a valid JSON configuration package");
                }
                report =
                        Configuration.install(
                                c.db,
                                packageBody,
                                c.user.get("user_id").toString(),
                                c.parameter("operation", "").equals("preview"));
            }
            return view(
                    c,
                    "configuration",
                    map("table", "configuration", "document", document, "report", report));
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
            if (action.equals("comment")) {
                Comments.add(c.db, item, map("body", c.parameter("body", "")), c.name());
            } else if (action.equals("note")) {
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
                        "canWriteRecord",
                        !c.role().equals("reader")
                                && RecordPolicy.allows(
                                        definition, item == null ? "create" : "update", c.role()),
                        "fieldRules",
                        TypedFields.rules(definition),
                        "emptyRule",
                        map(),
                        "item",
                        item,
                        "data",
                        data,
                        "access",
                        item == null ? map() : RecordAccess.status(c.db, id),
                        "accessGroups",
                        c.role().equals("admin") ? RecordAccess.groups(c.db) : List.of(),
                        "members",
                        item != null && table.equals("sys_user_group") && c.role().equals("admin")
                                ? GroupMembers.list(c.db, id)
                                : List.of(),
                        "stateChoices",
                        choices,
                        "currentState",
                        state,
                        "events",
                        item == null
                                ? List.of()
                                : c.db.all(
                                        "SELECT * FROM os_event WHERE record_id=? AND "
                                                + RecordAccess.journalFilter(c.db)
                                                + " ORDER BY seq DESC",
                                        id),
                        "attachments",
                        item == null
                                ? List.of()
                                : c.db.all(
                                        "SELECT sys_id,name FROM os_attachment WHERE record_id=?"
                                                + " ORDER BY sys_id",
                                        id)));
    }

    private ModelAndView invitations(Request c, String method) throws Exception {
        method(method, "GET", "POST");
        c.admin();
        c.noQuery();
        c.uniqueQuery();
        Map<String, Object> issued = null;
        String error = null;
        int status = 200;
        if (method.equals("POST")) {
            try {
                issued =
                        Invitations.issue(
                                c.db,
                                c.user.get("user_id").toString(),
                                map(
                                        "name",
                                        c.parameter("name", ""),
                                        "role",
                                        c.parameter("role", "reader")));
                status = 201;
            } catch (Failure failure) {
                error = failure.getMessage();
                status = failure.status;
            }
        }
        var view =
                view(
                        c,
                        "invitations",
                        map(
                                "table",
                                "invitations",
                                "issued",
                                issued,
                                "error",
                                error,
                                "invitations",
                                Invitations.latest(c.db),
                                "roles",
                                Service.ROLES));
        view.setStatus(HttpStatus.valueOf(status));
        return view;
    }

    private ModelAndView accountDetail(Request c, String id, String method) throws Exception {
        method(method, "GET", "POST");
        c.admin();
        c.noQuery();
        c.uniqueQuery();
        var account = Service.account(c.db, id);
        Map<String, Object> recovery = null;
        String error = null;
        int status = 200;
        if (method.equals("POST")) {
            try {
                String operation = c.parameter("operation", "status");
                if (!Set.of("status", "role", "recovery").contains(operation))
                    throw new Failure(400, "Choose an account operation");
                if (operation.equals("role")) {
                    Service.changeRole(
                            c.db,
                            c.user.get("user_id").toString(),
                            id,
                            map("role", c.parameter("role", "")));
                } else if (operation.equals("recovery")) {
                    recovery =
                            RecoveryLinks.issue(
                                    c.db,
                                    c.user.get("user_id").toString(),
                                    id,
                                    map("current_password", c.parameter("current_password", "")));
                    status = 201;
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
                if (!operation.equals("recovery")) return redirect("/admin/accounts/" + id);
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
                                Service.ROLES,
                                "privateCount",
                                PrivateRecords.count(c.db, id),
                                "memberships",
                                GroupMembers.forUser(c.db, id),
                                "recovery",
                                recovery));
        result.setStatus(HttpStatus.valueOf(status));
        return result;
    }

    private ModelAndView accounts(Request c, String method) throws Exception {
        method(method, "GET", "POST");
        c.admin();
        var args = queryArguments(c);
        var directory = AccountDirectory.list(c.db, args);
        int page = (Integer) directory.get("page");
        var values = map("name", "", "role", "reader");
        String error = null;
        if (method.equals("POST")) {
            values = map("name", c.parameter("name", ""), "role", c.parameter("role", "reader"));
            try {
                var account =
                        Service.createManagedAccount(
                                c.db,
                                map(
                                        "name",
                                        values.get("name"),
                                        "role",
                                        values.get("role"),
                                        "password",
                                        c.parameter("password", "")),
                                c.user.get("user_id").toString());
                c.session.setAttribute(
                        "flash",
                        "Account for " + account.get("name") + " created. They can now sign in.");
                args.put("page", Integer.toString(page));
                return redirect("/admin/accounts?" + query(args));
            } catch (Failure failure) {
                if (failure.status != 400) throw failure;
                error = failure.getMessage();
            }
        }
        var model =
                map("table", "accounts", "roles", Service.ROLES, "values", values, "error", error);
        model.putAll(directory);
        var result = view(c, "accounts", model);
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
            String visible = RecordAccess.filter(c.db, "r.sys_id", parameters);
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
                                    + (where.isEmpty() ? " WHERE " : " AND ")
                                    + visible
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
        var parameters = new ArrayList<Object>(List.of(route.group(1)));
        var row =
                c.db.one(
                        select
                                + (content ? ",a.content" : "")
                                + from
                                + " WHERE a.sys_id=? AND "
                                + RecordAccess.filter(c.db, "r.sys_id", parameters),
                        parameters.toArray());
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
        var parameters = new ArrayList<Object>();
        for (var count :
                c.db.all(
                        "SELECT \"table\",COUNT(*) AS total FROM os_record WHERE "
                                + RecordAccess.filter(c.db, "os_record.sys_id", parameters)
                                + " GROUP BY \"table\"",
                        parameters.toArray()))
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
