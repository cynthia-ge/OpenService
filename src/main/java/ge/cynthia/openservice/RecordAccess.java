package ge.cynthia.openservice;

import static ge.cynthia.openservice.Values.*;

import java.sql.SQLException;
import java.util.*;

/** Local record visibility, distinct from ServiceNow's programmable ACL engine. */
final class RecordAccess {
    private RecordAccess() {}

    static String filter(Store.Db db, String recordId, List<Object> parameters) {
        // Unbound connections are internal setup/test transactions; HTTP binds every principal.
        if (db.viewerId == null || "admin".equals(db.viewerRole)) return "1=1";
        parameters.add(db.viewerId);
        return "NOT EXISTS (SELECT 1 FROM os_record_access access WHERE access.record_id="
                + recordId
                + " AND NOT (access.visibility='workspace' OR (access.visibility='private' AND"
                + " access.owner_id=?)))";
    }

    static Map<String, Object> status(Store.Db db, String id) throws SQLException {
        var row =
                db.one(
                        "SELECT visibility,owner_id,COALESCE((SELECT name FROM os_user WHERE"
                            + " user_id=owner_id),'') AS owner FROM os_record_access WHERE"
                            + " record_id=?",
                        id);
        return row == null ? map("visibility", "workspace", "owner_id", "", "owner", "") : row;
    }

    static String owner(Store.Db db, String name) throws SQLException {
        var user = db.one("SELECT user_id FROM os_user WHERE name=? AND active=1", name);
        if (user == null) throw new Failure(400, "Choose an active account username");
        return user.get("user_id").toString();
    }

    static Map<String, Object> change(
            Store.Db db, Map<String, Object> item, String actor, Object body) throws SQLException {
        Service.requireAccountAdministrator(db, actor);
        var value = object(body);
        if (!value.keySet().equals(Set.of("visibility", "owner_id"))
                || !(value.get("visibility") instanceof String visibility)
                || !Set.of("workspace", "private").contains(visibility)
                || !(value.get("owner_id") instanceof String owner))
            throw new Failure(400, "Provide visibility and owner_id");
        if (value.get("visibility").equals("private")) {
            if (db.one("SELECT user_id FROM os_user WHERE user_id=? AND active=1", owner) == null)
                throw new Failure(400, "Private records require an active account");
        } else if (!owner.isEmpty())
            throw new Failure(400, "Workspace records do not have a private owner");
        String id = item.get("sys_id").toString();
        var before = status(db, id);
        db.update(
                "INSERT INTO os_record_access(record_id,visibility,owner_id) VALUES(?,?,?) ON"
                    + " CONFLICT(record_id) DO UPDATE SET"
                    + " visibility=excluded.visibility,owner_id=excluded.owner_id",
                id,
                value.get("visibility"),
                owner);
        var after = status(db, id);
        Service.audit(
                db,
                item,
                db.one("SELECT name FROM os_user WHERE user_id=?", actor).get("name").toString(),
                "access_changed",
                map("before", before, "after", after));
        return after;
    }
}
