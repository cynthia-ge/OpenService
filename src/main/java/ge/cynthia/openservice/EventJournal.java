package ge.cynthia.openservice;

import static ge.cynthia.openservice.Values.*;

import java.sql.SQLException;
import java.util.*;

/** Keyset navigation over internal journal schemas, never caller-supplied SQL identifiers. */
final class EventJournal {
    private EventJournal() {}

    static Map<String, Object> page(
            Store.Db db, String table, String columns, Map<String, String> args)
            throws SQLException {
        if (!Set.of("before", "download").containsAll(args.keySet())
                || !Set.of("true", "false").contains(args.getOrDefault("download", "false")))
            throw new Failure(400, "Unsupported journal parameter");
        String before = args.get("before");
        var parameters = new ArrayList<Object>();
        if (before != null) {
            try {
                if (!before.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException();
                parameters.add(Long.parseLong(before));
            } catch (NumberFormatException error) {
                throw new Failure(400, "before must be a positive signed 64-bit integer");
            }
        }
        var rows =
                db.all(
                        "SELECT sequence,"
                                + columns
                                + " FROM "
                                + table
                                + (before == null ? "" : " WHERE sequence<?")
                                + " ORDER BY sequence DESC LIMIT 101",
                        parameters.toArray());
        String next = "";
        if (rows.size() > 100) {
            rows.remove(100);
            next = rows.getLast().get("sequence").toString();
        }
        rows.forEach(row -> row.remove("sequence"));
        return map(
                "result",
                rows,
                "next_before",
                next,
                "before",
                before == null ? "" : before,
                "download",
                Boolean.parseBoolean(args.getOrDefault("download", "false")));
    }
}
