package ge.cynthia.openservice;

import static ge.cynthia.openservice.Values.*;

import java.sql.SQLException;
import java.util.*;

/** Work ownership comes from installed assignment metadata, not arbitrary text fields. */
final class Assignments {
    private Assignments() {}

    static Map<String, Object> transfer(
            Store.Db db, String actor, String user, Object input, boolean dryRun)
            throws SQLException {
        Service.requireAccountAdministrator(db, actor);
        Service.account(db, user);
        var value = object(input);
        if (!Set.of("new_owner_id", "record_ids", "expected_revisions").containsAll(value.keySet())
                || !(value.get("new_owner_id") instanceof String next)
                || !next.matches("[0-9a-f]{32}")
                || next.equals(user)
                || !(value.get("record_ids") instanceof List<?> ids))
            throw new Failure(400, "Provide a different new_owner_id and record_ids list");
        if (ids.isEmpty() || ids.size() > 100 || new HashSet<>(ids).size() != ids.size())
            throw new Failure(400, "Select 1–100 unique records");
        Map<String, Object> expected = null;
        if (value.containsKey("expected_revisions")) {
            expected = object(value.get("expected_revisions"));
            if (!expected.keySet().equals(new HashSet<>(ids)))
                throw new Failure(400, "Provide one expected revision for every selected record");
            for (Object token : expected.values())
                if (!(token instanceof String revision) || !revision.matches("0|[1-9][0-9]{0,18}"))
                    throw new Failure(
                            400,
                            "Expected revisions must be canonical nonnegative decimal strings");
        }
        var reviewed = new LinkedHashMap<String, Object>();
        if (db.one(
                        "SELECT user_id FROM os_user WHERE user_id=? AND active=1 AND role IN"
                                + " ('admin','agent')",
                        next)
                == null) throw new Failure(400, "Choose an active agent or administrator");
        var checkpoint = db.connection.setSavepoint();
        String name = Service.account(db, actor).get("name").toString();
        for (Object id : ids) {
            if (!(id instanceof String recordId) || !recordId.matches("[0-9a-f]{32}"))
                throw new Failure(400, "Invalid record ID");
            var row = db.one("SELECT \"table\" FROM os_record WHERE sys_id=?", recordId);
            if (row == null) throw new Failure(404, "Record not found");
            String table = row.get("table").toString();
            var item = Service.record(db, table, recordId);
            var assignment = object(Service.schema(db, table).getOrDefault("assignment", map()));
            if (assignment.isEmpty()
                    || !user.equals(object(item.get("data")).get(assignment.get("user_field"))))
                throw new Failure(409, "Selected record is no longer assigned to this account");
            reviewed.put(recordId, item.get("revision").toString());
            Service.update(
                    db,
                    table,
                    item,
                    map(assignment.get("user_field").toString(), next),
                    name,
                    "admin",
                    expected == null
                            ? item.get("revision").toString()
                            : expected.get(recordId).toString());
        }
        var result =
                map(
                        "transferred_count",
                        ids.size(),
                        "record_ids",
                        ids,
                        "new_owner_id",
                        next,
                        "dry_run",
                        dryRun,
                        "expected_revisions",
                        reviewed);
        SecurityEvents.record(db, actor, user, "account.assignments_transferred", result);
        if (dryRun) db.connection.rollback(checkpoint);
        db.connection.releaseSavepoint(checkpoint);
        return result;
    }

    static Map<String, Object> page(Store.Db db, String user, Map<String, String> args)
            throws SQLException {
        var account = Service.account(db, user);
        if (!Set.of("page").containsAll(args.keySet())
                || !args.getOrDefault("page", "1").matches("[1-9][0-9]{0,5}"))
            throw new Failure(400, "Use a positive assignment page of at most six digits");
        int page = Integer.parseInt(args.getOrDefault("page", "1"));
        var clauses = new ArrayList<String>();
        var parameters = new ArrayList<Object>();
        for (var schema : db.all("SELECT name,definition FROM os_schema ORDER BY name")) {
            var definition = object(schema.get("definition"));
            var assignment = object(definition.getOrDefault("assignment", map()));
            if (assignment.isEmpty()) continue;
            clauses.add(
                    "(\"table\"=? AND "
                            + Service.columns(db, definition).get(assignment.get("user_field"))
                            + "=?)");
            parameters.add(schema.get("name"));
            parameters.add(user);
        }
        String where = clauses.isEmpty() ? "1=0" : String.join(" OR ", clauses);
        long total =
                db.count("SELECT COUNT(*) FROM os_record WHERE " + where, parameters.toArray());
        long pages = Math.max(1, (total + 24) / 25);
        if (page > pages) throw new Failure(400, "Page is outside the assignment list");
        parameters.add((page - 1) * 25);
        var records = new ArrayList<Map<String, Object>>();
        for (var row :
                db.all(
                        "SELECT sys_id,\"table\",data,revision FROM os_record WHERE "
                                + where
                                + " ORDER BY \"table\",sys_id LIMIT 25 OFFSET ?",
                        parameters.toArray())) {
            var data = object(row.get("data"));
            records.add(
                    map(
                            "sys_id",
                            row.get("sys_id"),
                            "table",
                            row.get("table"),
                            "number",
                            data.get("number"),
                            "summary",
                            data.get("short_description"),
                            "revision",
                            row.get("revision").toString()));
        }
        return map(
                "account", account, "records", records, "page", page, "pages", pages, "total",
                total);
    }
}
