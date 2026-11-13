package ge.cynthia.openservice;

import static ge.cynthia.openservice.Values.*;

import java.sql.*;
import java.util.*;
import java.util.regex.*;

public final class Service {
    private Service() {}

    public static final Map<String, Object> ROLES =
            map(
                    "reader",
                    List.of(
                            "Reader",
                            "View records, activity and attachments. Cannot change records."),
                    "agent",
                    List.of(
                            "Agent",
                            "Create and update records, add notes and upload attachments."),
                    "admin",
                    List.of(
                            "Administrator",
                            "Manage accounts, import data, approve changes and delete records."));
    private static final Set<String> SYSTEM =
            Set.of(
                    "sys_id",
                    "number",
                    "sys_created_on",
                    "sys_updated_on",
                    "sys_created_by",
                    "sys_updated_by",
                    "sys_mod_count",
                    "sys_class_name");

    public static Map<String, Object> schema(Store.Db db, String name) throws SQLException {
        var item = db.one("SELECT definition FROM os_schema WHERE name=?", name);
        if (item == null) throw new Failure(404, "Unknown table");
        return object(item.get("definition"));
    }

    public static Map<String, Object> record(Store.Db db, String table, String id)
            throws SQLException {
        var item =
                db.one(
                        "SELECT * FROM os_record WHERE \"table\"=? AND sys_id=?"
                                + (db.postgres ? " FOR UPDATE" : ""),
                        table,
                        id);
        if (item == null) throw new Failure(404, "Record not found");
        return item;
    }

    public static Map<String, Object> serialize(Map<String, Object> item) {
        var result = new LinkedHashMap<>(object(item.get("data")));
        result.putAll(
                map(
                        "sys_id",
                        item.get("sys_id"),
                        "sys_class_name",
                        item.get("table"),
                        "sys_created_on",
                        item.get("created"),
                        "sys_updated_on",
                        item.get("updated"),
                        "sys_created_by",
                        item.get("creator"),
                        "sys_updated_by",
                        item.get("updater"),
                        "sys_mod_count",
                        item.get("revision").toString()));
        return result;
    }

    static void validate(Map<String, Object> definition, Map<String, Object> payload) {
        if (!object(definition.get("fields")).keySet().containsAll(payload.keySet()))
            throw new Failure(400, "Unknown or read-only fields");
        for (Object value : payload.values())
            if (!(value instanceof String s) || s.codePointCount(0, s.length()) > 65536)
                throw new Failure(400, "Field values must be strings of at most 65536 characters");
        if (payload.containsKey("priority")
                && !List.of("1", "2", "3", "4", "5").contains(payload.get("priority")))
            throw new Failure(400, "Priority must be 1–5");
        if (payload.containsKey("state")
                && !object(definition.get("states")).containsKey(payload.get("state")))
            throw new Failure(400, "Unknown state");
    }

    public static Map<String, Object> create(Store.Db db, String table, Object body, String actor)
            throws SQLException {
        var definition = schema(db, table);
        var payload = object(body);
        validate(definition, payload);
        if (((String) payload.getOrDefault("short_description", "")).isBlank())
            throw new Failure(400, "Summary is required");
        var data = new LinkedHashMap<String, Object>();
        object(definition.get("fields")).keySet().forEach(key -> data.put(key, ""));
        var states = object(definition.get("states"));
        if (!states.isEmpty()) data.put("state", states.keySet().iterator().next());
        if (data.containsKey("priority")) data.put("priority", "3");
        if (payload.containsKey("state") && !payload.get("state").equals(data.get("state")))
            throw new Failure(400, "Create in the initial state, then use a transition");
        data.putAll(payload);
        String now = now();
        var item =
                map(
                        "sys_id",
                        id(),
                        "table",
                        table,
                        "data",
                        data,
                        "original",
                        map(),
                        "created",
                        now,
                        "updated",
                        now,
                        "creator",
                        actor,
                        "updater",
                        actor,
                        "revision",
                        0);
        long sequence = insert(db, item);
        data.put("number", definition.get("prefix") + String.format(Locale.ROOT, "%07d", sequence));
        save(db, item);
        audit(db, item, actor, "created", map("after", serialize(item)));
        return item;
    }

    static long insert(Store.Db db, Map<String, Object> item) throws SQLException {
        try (var query =
                db.statement(
                        "INSERT INTO"
                            + " os_record(sys_id,\"table\",data,original,created,updated,creator,updater,revision)"
                            + " VALUES(?,?,"
                                + db.jsonParameter()
                                + ","
                                + db.jsonParameter()
                                + ",?,?,?,?,?)",
                        item.get("sys_id"),
                        item.get("table"),
                        JSON.writeValueAsString(item.get("data")),
                        JSON.writeValueAsString(item.get("original")),
                        item.get("created"),
                        item.get("updated"),
                        item.get("creator"),
                        item.get("updater"),
                        item.get("revision"))) {
            query.executeUpdate();
            try (var keys = query.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    static void save(Store.Db db, Map<String, Object> item) throws SQLException {
        db.update(
                "UPDATE os_record SET data="
                        + db.jsonParameter()
                        + ",updated=?,updater=?,revision=? WHERE sys_id=?",
                JSON.writeValueAsString(item.get("data")),
                item.get("updated"),
                item.get("updater"),
                item.get("revision"),
                item.get("sys_id"));
    }

    public static void audit(
            Store.Db db, Map<String, Object> item, String actor, String action, Object detail)
            throws SQLException {
        db.update(
                "INSERT INTO os_event(record_id,actor,action,created,detail) VALUES(?,?,?, ?,"
                        + db.jsonParameter()
                        + ")",
                item.get("sys_id"),
                actor,
                action,
                now(),
                JSON.writeValueAsString(detail));
    }

    public static Map<String, Object> update(
            Store.Db db,
            String table,
            Map<String, Object> item,
            Object body,
            String actor,
            String role,
            String revision)
            throws SQLException {
        var definition = schema(db, table);
        var payload = object(body);
        validate(definition, payload);
        var data = object(item.get("data"));
        if (revision != null && !item.get("revision").toString().equals(revision))
            throw new Failure(409, "Record changed; reload before saving");
        if (payload.containsKey("short_description")
                && ((String) payload.get("short_description")).isBlank())
            throw new Failure(400, "Summary is required");
        if (payload.containsKey("state") && !payload.get("state").equals(data.get("state"))) {
            if (!((List<?>)
                            object(definition.get("flow"))
                                    .getOrDefault(data.get("state"), List.of()))
                    .contains(payload.get("state")))
                throw new Failure(409, "Transition is not allowed");
            if (table.equals("change_request")
                    && List.of("approved", "rejected").contains(payload.get("state"))
                    && !role.equals("admin"))
                throw new Failure(403, "An administrator must approve or reject changes");
        }
        var before = serialize(item);
        data.putAll(payload);
        item.putAll(
                map(
                        "updated",
                        now(),
                        "updater",
                        actor,
                        "revision",
                        ((Number) item.get("revision")).intValue() + 1));
        save(db, item);
        audit(db, item, actor, "updated", map("before", before, "after", serialize(item)));
        return item;
    }

    public static void remove(Store.Db db, Map<String, Object> item, String actor)
            throws SQLException {
        audit(db, item, actor, "deleted", map("before", serialize(item)));
        db.update("DELETE FROM os_attachment WHERE record_id=?", item.get("sys_id"));
        db.update("DELETE FROM os_record WHERE sys_id=?", item.get("sys_id"));
    }

    static Map<String, String> columns(Store.Db db, Map<String, Object> definition) {
        var columns = new LinkedHashMap<String, String>();
        var fields = new ArrayList<>(object(definition.get("fields")).keySet());
        fields.add("number");
        for (String field : fields)
            columns.put(
                    field,
                    db.postgres
                            ? "(data->>'" + field.replace("'", "''") + "')"
                            : "json_extract(data,'$." + field.replace("'", "''") + "')");
        columns.putAll(
                Map.of(
                        "sys_id",
                        "sys_id",
                        "sys_created_on",
                        "created",
                        "sys_updated_on",
                        "updated"));
        return columns;
    }

    public record Selection(
            String where, List<Object> values, String ordering, Map<String, String> columns) {}

    static Selection selection(Store.Db db, String table, String encoded, String search)
            throws SQLException {
        var columns = columns(db, schema(db, table));
        var where = new StringBuilder("\"table\"=?");
        var values = new ArrayList<Object>(List.of(table));
        var ordering = new ArrayList<String>();
        for (String term : encoded.split("\\^")) {
            if (term.isEmpty()) continue;
            if (term.startsWith("ORDERBY")) {
                boolean desc = term.startsWith("ORDERBYDESC");
                String field = term.substring(desc ? 11 : 7);
                if (!columns.containsKey(field)) throw new Failure(400, "Unknown ordering field");
                ordering.add(columns.get(field) + (desc ? " DESC" : " ASC"));
                continue;
            }
            Matcher match =
                    Pattern.compile(
                                    "([a-z_][a-z_0-9]*)(!=|=|LIKE|NOT LIKE|STARTSWITH|ENDSWITH|NOT"
                                            + " IN|IN|ISEMPTY|ISNOTEMPTY)(.*)",
                                    Pattern.DOTALL)
                            .matcher(term);
            if (!match.matches() || !columns.containsKey(match.group(1)))
                throw new Failure(400, "Unsupported encoded query");
            String column = columns.get(match.group(1)),
                    op = match.group(2),
                    value = match.group(3);
            if (op.equals("ISEMPTY") || op.equals("ISNOTEMPTY")) {
                if (!value.isEmpty())
                    throw new Failure(400, "Empty predicates do not accept a value");
                where.append(" AND COALESCE(")
                        .append(column)
                        .append(",'')")
                        .append(op.equals("ISEMPTY") ? "=''" : "<>''");
            } else if (op.equals("IN") || op.equals("NOT IN")) {
                var members = Arrays.asList(value.split(",", -1));
                if (members.size() > 100 || members.contains(""))
                    throw new Failure(
                            400, "Membership requires 1–100 nonempty comma-separated values");
                where.append(" AND ")
                        .append(column)
                        .append(' ')
                        .append(op)
                        .append(" (")
                        .append(String.join(",", Collections.nCopies(members.size(), "?")))
                        .append(')');
                values.addAll(members);
            } else if (Set.of("LIKE", "STARTSWITH", "ENDSWITH", "NOT LIKE").contains(op)) {
                where.append(" AND ")
                        .append(column)
                        .append(op.equals("NOT LIKE") ? " NOT" : "")
                        .append(" LIKE ? ESCAPE '\\'");
                values.add(
                        (op.equals("STARTSWITH") ? "" : "%")
                                + escaped(value)
                                + (op.equals("ENDSWITH") ? "" : "%"));
            } else {
                where.append(" AND ").append(column).append(op).append('?');
                values.add(value);
            }
        }
        if (!search.isEmpty()) {
            where.append(" AND ")
                    .append(columns.get("short_description"))
                    .append(" LIKE ? ESCAPE '\\'");
            values.add("%" + escaped(search) + "%");
        }
        ordering.add("seq");
        return new Selection(where.toString(), values, String.join(",", ordering), columns);
    }

    private static String escaped(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    public record Page(
            List<Map<String, Object>> rows,
            long total,
            int limit,
            int offset,
            boolean suppressPagination,
            boolean hasNext) {}

    public static Page page(Store.Db db, String table, Map<String, String> args, String search)
            throws SQLException {
        if (!Set.of(
                        "sysparm_query",
                        "sysparm_limit",
                        "sysparm_offset",
                        "sysparm_fields",
                        "sysparm_suppress_pagination_header",
                        "sysparm_no_count")
                .containsAll(args.keySet())) throw new Failure(400, "Unsupported query parameter");
        int limit = number(args.getOrDefault("sysparm_limit", "10000"), 1, 10000),
                offset = number(args.getOrDefault("sysparm_offset", "0"), 0, Integer.MAX_VALUE);
        boolean suppressPagination = flag(args, "sysparm_suppress_pagination_header"),
                noCount = flag(args, "sysparm_no_count");
        var query = selection(db, table, args.getOrDefault("sysparm_query", ""), search);
        long total =
                noCount
                        ? -1
                        : db.count(
                                "SELECT COUNT(*) FROM os_record WHERE " + query.where,
                                query.values.toArray());
        var parameters = new ArrayList<>(query.values);
        parameters.add(noCount ? limit + 1 : limit);
        parameters.add(offset);
        var selected =
                db.all(
                        "SELECT * FROM os_record WHERE "
                                + query.where
                                + " ORDER BY "
                                + query.ordering
                                + " LIMIT ? OFFSET ?",
                        parameters.toArray());
        boolean hasNext = noCount ? selected.size() > limit : (long) offset + limit < total;
        var rows =
                selected.stream()
                        .limit(limit)
                        .map(Service::serialize)
                        .map(row -> project(row, args.getOrDefault("sysparm_fields", "")))
                        .toList();
        return new Page(rows, total, limit, offset, suppressPagination, hasNext);
    }

    public static boolean flag(Map<String, String> args, String name) {
        String value = args.getOrDefault(name, "false");
        if (!Set.of("true", "false").contains(value))
            throw new Failure(400, name + " must be true or false");
        return value.equals("true");
    }

    public static int number(String value, int minimum, int maximum) {
        try {
            long number = Long.parseLong(value.strip());
            if (number < minimum || number > maximum) throw new NumberFormatException();
            return (int) number;
        } catch (NumberFormatException | NullPointerException error) {
            throw new Failure(400, "Integer is outside the supported range");
        }
    }

    public static Map<String, Object> project(Map<String, Object> data, String fields) {
        if (fields.isEmpty()) return data;
        var result = new LinkedHashMap<String, Object>();
        for (String name : fields.split(",")) {
            name = name.strip();
            if (data.containsKey(name)) result.put(name, data.get(name));
        }
        return result;
    }

    public static Map<String, Object> account(Store.Db db, String userId) throws SQLException {
        var user = db.one("SELECT user_id,name,role,active FROM os_user WHERE user_id=?", userId);
        if (user == null) throw new Failure(404, "User not found");
        user.put("active", ((Number) user.get("active")).intValue() == 1);
        return user;
    }

    public static Map<String, Object> changeActive(
            Store.Db db, String actorId, String userId, Object body) throws SQLException {
        var value = object(body);
        if (!value.keySet().equals(Set.of("active"))
                || !(value.get("active") instanceof Boolean active))
            throw new Failure(400, "Provide one boolean active field");
        requireAccountAdministrator(db, actorId);
        var user = account(db, userId);
        if (user.get("active").equals(active)) return user;
        if (user.get("role").equals("admin")
                && !active
                && db.count("SELECT COUNT(*) FROM os_user WHERE role='admin' AND active=1") <= 1)
            throw new Failure(409, "Keep at least one active administrator");
        db.update("UPDATE os_user SET active=? WHERE user_id=?", active ? 1 : 0, userId);
        revokeSessions(db, userId);
        return account(db, userId);
    }

    private static void requireAccountAdministrator(Store.Db db, String actorId)
            throws SQLException {
        if (db.postgres) db.all("SELECT pg_advisory_xact_lock(18439,2)");
        if (db.one(
                        "SELECT user_id FROM os_user WHERE user_id=? AND active=1 AND role='admin'",
                        actorId)
                == null) throw new Failure(403, "Active administrator required");
    }

    public static Map<String, Object> changeRole(
            Store.Db db, String actorId, String userId, Object body) throws SQLException {
        var value = object(body);
        if (!value.keySet().equals(Set.of("role")) || !ROLES.containsKey(value.get("role")))
            throw new Failure(400, "Provide one supported role field");
        requireAccountAdministrator(db, actorId);
        var user = account(db, userId);
        String role = value.get("role").toString();
        if (user.get("role").equals(role)) return user;
        if (user.get("role").equals("admin")
                && user.get("active").equals(true)
                && db.count("SELECT COUNT(*) FROM os_user WHERE role='admin' AND active=1") <= 1)
            throw new Failure(409, "Keep at least one active administrator");
        db.update("UPDATE os_user SET role=? WHERE user_id=?", role, userId);
        revokeSessions(db, userId);
        return account(db, userId);
    }

    public static void changePassword(Store.Db db, String userId, Object body) throws Exception {
        var value = object(body);
        if (!value.keySet().equals(Set.of("current_password", "new_password"))
                || !(value.get("current_password") instanceof String current)
                || current.codePointCount(0, current.length()) > 1024
                || !(value.get("new_password") instanceof String password)
                || password.codePointCount(0, password.length()) < 12
                || password.codePointCount(0, password.length()) > 1024)
            throw new Failure(
                    400, "Provide current_password and a new_password of 12–1024 characters");
        var user =
                db.one(
                        "SELECT password FROM os_user WHERE user_id=? AND active=1"
                                + (db.postgres ? " FOR UPDATE" : ""),
                        userId);
        if (user == null) throw new Failure(401, "Active account required");
        if (!Passwords.matches(user.get("password").toString(), current))
            throw new Failure(400, "Current password is incorrect");
        if (password.equals(current)) throw new Failure(400, "Choose a different password");
        db.update(
                "UPDATE os_user SET password=? WHERE user_id=?", Passwords.hash(password), userId);
        revokeSessions(db, userId);
    }

    public static void revokeSessions(Store.Db db, String userId) throws SQLException {
        db.update("UPDATE os_user SET auth_revision=auth_revision+1 WHERE user_id=?", userId);
    }

    public static Map<String, Object> createAccount(Store.Db db, Object body) throws Exception {
        var value = object(body);
        if (!value.keySet().equals(Set.of("name", "password", "role")))
            throw new Failure(400, "Provide name, password and role");
        if (!(value.get("name") instanceof String name) || !name.matches("[A-Za-z0-9]{1,80}"))
            throw new Failure(400, "Username must contain 1–80 ASCII letters or digits");
        if (!(value.get("password") instanceof String password)
                || password.codePointCount(0, password.length()) < 12
                || password.codePointCount(0, password.length()) > 1024)
            throw new Failure(400, "Password must contain 12–1024 characters");
        if (!(value.get("role") instanceof String role) || !ROLES.containsKey(role))
            throw new Failure(400, "Choose an admin, agent or reader role");
        try {
            db.update(
                    "INSERT INTO os_user(name,password,role,user_id) VALUES(?,?,?,?)",
                    name,
                    Passwords.hash(password),
                    role,
                    id());
        } catch (SQLException error) {
            if (!"23505".equals(error.getSQLState()) && error.getErrorCode() != 19) throw error;
            db.connection.rollback();
            throw new Failure(400, "User already exists");
        }
        return map("name", name, "role", role);
    }

    public static Map<String, Object> migrate(
            Store.Db db, String table, Object body, String actor, boolean dryRun)
            throws SQLException {
        var definition = schema(db, table);
        Object input = body instanceof Map<?, ?> wrapper ? wrapper.get("result") : body;
        if (!(input instanceof List<?> rows) || rows.size() > 10000)
            throw new Failure(400, "Import a result array or list with at most 10000 records");
        var errors = new ArrayList<Map<String, Object>>();
        var pending = new ArrayList<Map<String, Object>>();
        var seen = new HashSet<String>();
        var extra = new TreeSet<String>();
        int skipped = 0, index = 0;
        for (Object value : rows) {
            index++;
            if (!(value instanceof Map<?, ?> raw)
                    || !(raw.get("sys_id") instanceof String id)
                    || !id.matches("[a-f0-9]{32}")) {
                errors.add(
                        map(
                                "row",
                                index,
                                "message",
                                "A 32-character lowercase hex sys_id is required"));
                continue;
            }
            var row = object(raw);
            if (!seen.add(id)) {
                errors.add(map("row", index, "message", "Duplicate sys_id in import"));
                continue;
            }
            var existing = db.one("SELECT \"table\" FROM os_record WHERE sys_id=?", id);
            if (existing != null) {
                if (!existing.get("table").equals(table))
                    errors.add(map("row", index, "message", "sys_id belongs to a different table"));
                else skipped++;
                continue;
            }
            var normalized = new LinkedHashMap<String, Object>();
            for (String key : object(definition.get("fields")).keySet()) {
                Object field = row.getOrDefault(key, "");
                if (field instanceof Map<?, ?> reference)
                    field = reference.getOrDefault("value", null);
                normalized.put(key, field == null ? "" : sourceText(field));
            }
            normalized.put(
                    "number",
                    sourceText(
                            row.getOrDefault(
                                    "number",
                                    definition.get("prefix")
                                            + id.substring(0, 8).toUpperCase(Locale.ROOT))));
            for (String key : row.keySet())
                if (!object(definition.get("fields")).containsKey(key) && !SYSTEM.contains(key))
                    extra.add(key);
            pending.add(
                    map(
                            "sys_id",
                            id,
                            "table",
                            table,
                            "data",
                            normalized,
                            "original",
                            row,
                            "created",
                            sourceText(row.getOrDefault("sys_created_on", now())),
                            "updated",
                            sourceText(row.getOrDefault("sys_updated_on", now())),
                            "creator",
                            sourceText(row.getOrDefault("sys_created_by", actor)),
                            "updater",
                            sourceText(row.getOrDefault("sys_updated_by", actor)),
                            "revision",
                            0));
        }
        if (!dryRun && errors.isEmpty())
            for (var item : pending) {
                insert(db, item);
                audit(
                        db,
                        item,
                        actor,
                        "imported",
                        map("source", "ServiceNow JSON", "original_preserved", true));
            }
        return map(
                "total",
                rows.size(),
                "created",
                pending.size(),
                "skipped",
                skipped,
                "errors",
                errors,
                "preserved_fields",
                new ArrayList<>(extra),
                "dry_run",
                dryRun);
    }

    private static String sourceText(Object value) {
        return value == null
                ? "None"
                : value instanceof Boolean flag ? (flag ? "True" : "False") : value.toString();
    }

    public static Object stats(Store.Db db, String table, Map<String, String> args)
            throws SQLException {
        if (!Set.of(
                                "sysparm_count",
                                "sysparm_query",
                                "sysparm_group_by",
                                "sysparm_order_by",
                                "sysparm_display_value")
                        .containsAll(args.keySet())
                || !"true".equals(args.get("sysparm_count"))
                || !args.getOrDefault("sysparm_display_value", "false").equals("false"))
            throw new Failure(400, "This subset requires sysparm_count=true and stored values");
        var query = selection(db, table, args.getOrDefault("sysparm_query", ""), "");
        String grouping = args.getOrDefault("sysparm_group_by", "");
        var groups =
                grouping.isEmpty() ? List.<String>of() : Arrays.asList(grouping.split(",", -1));
        if (new HashSet<>(groups).size() != groups.size()
                || !query.columns.keySet().containsAll(groups))
            throw new Failure(400, "Unknown or duplicate grouping field");
        var expressions =
                groups.stream()
                        .map(name -> "COALESCE(" + query.columns.get(name) + ",'')")
                        .toList();
        var projection = new ArrayList<String>(List.of("COUNT(*) AS total"));
        for (int i = 0; i < groups.size(); i++) projection.add(expressions.get(i) + " AS g" + i);
        String order = args.getOrDefault("sysparm_order_by", "");
        var ordering = new ArrayList<String>();
        if (!order.isEmpty()) {
            String field = order.endsWith("^DESC") ? order.substring(0, order.length() - 5) : order;
            if (groups.isEmpty() || !field.equals("COUNT") && !groups.contains(field))
                throw new Failure(
                        400, "Order by COUNT or a grouping field, optionally followed by ^DESC");
            ordering.add(
                    (field.equals("COUNT") ? "COUNT(*)" : expressions.get(groups.indexOf(field)))
                            + (order.endsWith("^DESC") ? " DESC" : " ASC"));
        }
        ordering.addAll(expressions);
        String sql =
                "SELECT "
                        + String.join(",", projection)
                        + " FROM os_record WHERE "
                        + query.where
                        + (groups.isEmpty() ? "" : " GROUP BY " + String.join(",", expressions))
                        + (ordering.isEmpty() ? "" : " ORDER BY " + String.join(",", ordering));
        var result = new ArrayList<Map<String, Object>>();
        for (var row : db.all(sql, query.values.toArray())) {
            var item = map("stats", map("count", row.get("total").toString()));
            if (!groups.isEmpty()) {
                var fields = new ArrayList<Map<String, Object>>();
                for (int i = 0; i < groups.size(); i++)
                    fields.add(map("field", groups.get(i), "value", row.get("g" + i)));
                item.put("groupby_fields", fields);
            }
            result.add(item);
        }
        return groups.isEmpty() ? result.getFirst() : result;
    }
}
