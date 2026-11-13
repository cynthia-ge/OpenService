package ge.cynthia.openservice;

import static ge.cynthia.openservice.Values.*;

import java.sql.SQLException;
import java.util.*;

/** Administrator-managed membership; it never grants an account role. */
final class GroupMembers {
    private GroupMembers() {}

    static List<Map<String, Object>> list(Store.Db db, String group) throws SQLException {
        return db.all(
                "SELECT u.user_id,u.name,u.role,u.active FROM os_group_member m JOIN os_user u ON"
                    + " u.user_id=m.user_id WHERE m.group_id=? ORDER BY u.name,u.user_id",
                group);
    }

    static List<Map<String, Object>> change(
            Store.Db db, String group, String actor, Object body, boolean add) throws SQLException {
        Service.requireAccountAdministrator(db, actor);
        var record = Service.record(db, "sys_user_group", group);
        var value = object(body);
        if (!value.keySet().equals(Set.of("user_id"))
                || !(value.get("user_id") instanceof String user))
            throw new Failure(400, "Provide user_id");
        int changed;
        if (add) {
            if (db.one("SELECT user_id FROM os_user WHERE user_id=? AND active=1", user) == null)
                throw new Failure(400, "Choose an active account");
            changed =
                    db.update(
                            "INSERT INTO os_group_member(group_id,user_id) VALUES(?,?) ON"
                                + " CONFLICT(group_id,user_id) DO NOTHING",
                            group,
                            user);
        } else
            changed =
                    db.update(
                            "DELETE FROM os_group_member WHERE group_id=? AND user_id=?",
                            group,
                            user);
        if (changed != 0)
            Service.audit(
                    db,
                    record,
                    db.one("SELECT name FROM os_user WHERE user_id=?", actor)
                            .get("name")
                            .toString(),
                    add ? "member_added" : "member_removed",
                    map("user_id", user));
        return list(db, group);
    }
}
