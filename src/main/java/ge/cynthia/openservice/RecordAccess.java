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
        parameters.add(db.viewerId);
        return "NOT EXISTS (SELECT 1 FROM os_record_access access WHERE access.record_id="
                + recordId
                + " AND NOT (access.visibility='workspace' OR (access.visibility='private' AND"
                + " access.owner_id=?) OR (access.visibility='group' AND EXISTS (SELECT 1 FROM"
                + " os_group_member m JOIN os_record g ON g.sys_id=m.group_id AND"
                + " g.\"table\"='sys_user_group' WHERE m.group_id=access.group_id AND"
                + " m.user_id=?))))";
    }

    static Map<String, Object> status(Store.Db db, String id) throws SQLException {
        var row =
                db.one(
                        "SELECT visibility,owner_id,group_id,COALESCE((SELECT name FROM os_user"
                            + " WHERE user_id=owner_id),'') AS owner FROM os_record_access WHERE"
                            + " record_id=?",
                        id);
        return row == null
                ? map("visibility", "workspace", "owner_id", "", "owner", "", "group_id", "")
                : row;
    }

    static List<Map<String, Object>> groups(Store.Db db) throws SQLException {
        return db.all(
                "SELECT sys_id,data FROM os_record WHERE \"table\"='sys_user_group' ORDER BY seq");
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
        if (!(value.get("visibility") instanceof String visibility)
                || !Set.of("workspace", "private", "group").contains(visibility))
            throw new Failure(400, "Choose supported visibility");
        boolean group = visibility.equals("group");
        String field = group ? "group_id" : "owner_id";
        if (!value.keySet().equals(Set.of("visibility", field))
                || !(value.get(field) instanceof String principal))
            throw new Failure(400, "Provide visibility and its principal");
        String owner = group ? "" : principal, groupId = group ? principal : "";
        if (visibility.equals("private")) {
            if (db.one("SELECT user_id FROM os_user WHERE user_id=? AND active=1", owner) == null)
                throw new Failure(400, "Private records require an active account");
        } else if (group) {
            if (db.one(
                            "SELECT sys_id FROM os_record WHERE sys_id=? AND"
                                + " \"table\"='sys_user_group'",
                            groupId)
                    == null) throw new Failure(400, "Choose an existing group");
        } else if (!principal.isEmpty())
            throw new Failure(400, "Workspace records do not have a private owner");
        String id = item.get("sys_id").toString();
        var before = status(db, id);
        db.update(
                "INSERT INTO os_record_access(record_id,visibility,owner_id,group_id)"
                    + " VALUES(?,?,?,?) ON CONFLICT(record_id) DO UPDATE SET"
                    + " visibility=excluded.visibility,owner_id=excluded.owner_id,group_id=excluded.group_id",
                id,
                value.get("visibility"),
                owner,
                groupId);
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
