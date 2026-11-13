package ge.cynthia.openservice;

import static ge.cynthia.openservice.Values.*;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/** Account lifecycle evidence. Only trusted callers supply event types and sanitized details. */
final class SecurityEvents {
    private SecurityEvents() {}

    static void record(
            Store.Db db, String actor, String target, String action, Map<String, Object> detail)
            throws SQLException {
        var actorName = db.one("SELECT name FROM os_user WHERE user_id=?", actor).get("name");
        var targetName = db.one("SELECT name FROM os_user WHERE user_id=?", target).get("name");
        db.update(
                "INSERT INTO"
                    + " os_security_event(event_id,occurred_at,actor_id,actor_name,target_id,target_name,action,detail)"
                    + " VALUES(?,?,?,?,?,?,?,?)",
                id(),
                now(),
                actor,
                actorName,
                target,
                targetName,
                action,
                JSON.writeValueAsString(detail));
    }

    static List<Map<String, Object>> latest(Store.Db db) throws SQLException {
        return db.all(
                "SELECT"
                    + " event_id,occurred_at,actor_id,actor_name,target_id,target_name,action,detail"
                    + " FROM os_security_event ORDER BY sequence DESC LIMIT 100");
    }
}
