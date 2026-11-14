package ge.cynthia.openservice;

import static ge.cynthia.openservice.Values.*;

import java.sql.SQLException;
import java.util.*;

/** Append-only shared comments. Callers must resolve a visible record first. */
final class Comments {
    private Comments() {}

    static List<Map<String, Object>> list(Store.Db db, String record) throws SQLException {
        return db.all(
                "SELECT seq AS entry_id,actor,created,detail FROM os_event WHERE record_id=? AND"
                    + " action='comment' ORDER BY seq DESC LIMIT 100",
                record);
    }

    static Map<String, Object> add(
            Store.Db db, Map<String, Object> record, Object input, String actor)
            throws SQLException {
        var value = object(input);
        if (!value.keySet().equals(Set.of("body")) || !(value.get("body") instanceof String text))
            throw new Failure(400, "Provide one string body");
        String body = text.strip();
        if (body.isEmpty() || body.codePointCount(0, body.length()) > 65536)
            throw new Failure(400, "Enter a comment of 1–65536 characters");
        Service.audit(db, record, actor, "comment", map("body", body));
        return list(db, record.get("sys_id").toString()).getFirst();
    }
}
