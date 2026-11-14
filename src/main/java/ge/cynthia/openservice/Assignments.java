package ge.cynthia.openservice;

import static ge.cynthia.openservice.Values.*;

import java.sql.SQLException;
import java.util.*;

/** Work ownership comes from installed assignment metadata, not arbitrary text fields. */
final class Assignments {
    private Assignments() {}

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
                        "SELECT sys_id,\"table\",data FROM os_record WHERE "
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
                            data.get("short_description")));
        }
        return map(
                "account", account, "records", records, "page", page, "pages", pages, "total",
                total);
    }
}
