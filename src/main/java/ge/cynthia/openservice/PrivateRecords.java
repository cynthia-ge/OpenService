package ge.cynthia.openservice;

import static ge.cynthia.openservice.Values.*;

import java.sql.SQLException;
import java.util.*;

/** Administrative handover of live private-access ownership, preserving business attribution. */
final class PrivateRecords {
    private PrivateRecords() {}

    static long count(Store.Db db, String user) throws SQLException {
        return db.count(
                "SELECT COUNT(*) FROM os_record_access a JOIN os_record r ON r.sys_id=a.record_id"
                    + " WHERE a.visibility='private' AND a.owner_id=?",
                user);
    }

    static Map<String, Object> transfer(Store.Db db, String actor, String user, Object body)
            throws SQLException {
        Service.requireAccountAdministrator(db, actor);
        Service.account(db, user);
        var value = object(body);
        if (!value.keySet().equals(Set.of("new_owner_id"))
                || !(value.get("new_owner_id") instanceof String next)
                || !next.matches("[0-9a-f]{32}")
                || next.equals(user)
                || db.one("SELECT user_id FROM os_user WHERE user_id=? AND active=1", next) == null)
            throw new Failure(400, "Choose a different active account");
        // Policy writers share the administrator lock; no business-record row locks are acquired.
        var ids = new ArrayList<String>();
        try (var statement =
                db.connection.prepareStatement(
                        "UPDATE os_record_access SET owner_id=? WHERE visibility='private' AND"
                            + " owner_id=? AND EXISTS (SELECT 1 FROM os_record WHERE"
                            + " sys_id=record_id) RETURNING record_id")) {
            statement.setString(1, next);
            statement.setString(2, user);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) ids.add(rows.getString(1));
            }
        }
        Collections.sort(ids);
        var result = map("transferred_count", ids.size(), "record_ids", ids, "new_owner_id", next);
        if (!ids.isEmpty())
            SecurityEvents.record(db, actor, user, "account.private_records_transferred", result);
        return result;
    }
}
