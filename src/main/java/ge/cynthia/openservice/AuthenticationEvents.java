package ge.cynthia.openservice;

import static ge.cynthia.openservice.Values.*;

import java.sql.SQLException;
import java.util.*;

/** Bounded local authentication journal; all outcome/mechanism values come from trusted callers. */
final class AuthenticationEvents {
    private AuthenticationEvents() {}

    static void record(Store.Db db, String name, String identity, String mechanism, String outcome)
            throws SQLException {
        var user =
                name.indexOf(0) >= 0
                        ? null
                        : db.one("SELECT user_id,name FROM os_user WHERE name=?", name);
        db.update(
                "INSERT INTO"
                    + " os_auth_event(event_id,occurred_at,user_id,user_name,identity_hash,mechanism,outcome)"
                    + " VALUES(?,?,?,?,?,?,?)",
                id(),
                now(),
                user == null ? "" : user.get("user_id"),
                user == null ? "" : user.get("name"),
                identity,
                mechanism,
                outcome);
        db.update(
                "DELETE FROM os_auth_event WHERE sequence<(SELECT sequence FROM os_auth_event ORDER"
                        + " BY sequence DESC LIMIT 1 OFFSET 9999)");
    }

    static List<Map<String, Object>> latest(Store.Db db) throws SQLException {
        return db.all(
                "SELECT event_id,occurred_at,user_id,user_name,identity_hash,mechanism,outcome FROM"
                        + " os_auth_event ORDER BY sequence DESC LIMIT 100");
    }
}
