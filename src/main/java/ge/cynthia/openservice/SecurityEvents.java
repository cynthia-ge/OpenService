package ge.cynthia.openservice;

import static ge.cynthia.openservice.Values.*;

import java.sql.SQLException;
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

    static Map<String, Object> page(Store.Db db, Map<String, String> args) throws SQLException {
        return EventJournal.page(
                db,
                "os_security_event",
                "event_id,occurred_at,actor_id,actor_name,target_id,target_name,action,detail",
                args);
    }
}
