package ge.cynthia.openservice;

import static ge.cynthia.openservice.Values.*;

import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

final class Invitations {
    private Invitations() {}

    static Map<String, Object> issue(Store.Db db, String actor, Object body) throws Exception {
        var value = object(body);
        if (!value.keySet().equals(Set.of("name", "role"))
                || !(value.get("name") instanceof String name)
                || !name.matches("[A-Za-z0-9]{1,80}")
                || !Service.ROLES.containsKey(value.get("role")))
            throw new Failure(400, "Provide a valid username and local role");
        Service.requireAccountAdministrator(db, actor);
        if (db.one("SELECT user_id FROM os_user WHERE name=?", name) != null)
            throw new Failure(400, "User already exists");
        db.update(
                "UPDATE os_invitation SET state='revoked',revoked_by=? WHERE name=? AND"
                        + " state='pending'",
                actor,
                name);
        String id = id(), token = Passwords.token();
        db.update(
                "INSERT INTO"
                    + " os_invitation(invite_id,name,role,token_hash,created_at,expires_at,issuer_id,issuer_revision,state)"
                    + " VALUES(?,?,?,?,?,?,?,?,'pending')",
                id,
                name,
                value.get("role"),
                Passwords.tokenHash(token),
                now(),
                System.currentTimeMillis() + 24 * 60 * 60 * 1000L,
                actor,
                db.one("SELECT auth_revision FROM os_user WHERE user_id=?", actor)
                        .get("auth_revision"));
        var result = visible(db, db.one("SELECT * FROM os_invitation WHERE invite_id=?", id));
        result.put("accept_path", "/join/" + token);
        return result;
    }

    private static Map<String, Object> visible(Store.Db db, Map<String, Object> row)
            throws SQLException {
        long expires = ((Number) row.get("expires_at")).longValue();
        String state = row.get("state").toString();
        if (state.equals("pending")) {
            if (expires <= System.currentTimeMillis()) state = "expired";
            else if (db.one(
                            "SELECT user_id FROM os_user WHERE user_id=? AND active=1 AND"
                                + " role='admin' AND auth_revision=?",
                            row.get("issuer_id"),
                            row.get("issuer_revision"))
                    == null) state = "invalidated";
            else if (db.one("SELECT user_id FROM os_user WHERE name=?", row.get("name")) != null)
                state = "unavailable";
        }
        return map(
                "invite_id",
                row.get("invite_id"),
                "name",
                row.get("name"),
                "role",
                row.get("role"),
                "created_at",
                row.get("created_at"),
                "expires_at",
                Instant.ofEpochMilli(expires).toString(),
                "issuer_id",
                row.get("issuer_id"),
                "state",
                state,
                "revoked_by",
                row.get("revoked_by"));
    }

    static List<Map<String, Object>> latest(Store.Db db) throws SQLException {
        var result = new ArrayList<Map<String, Object>>();
        for (var row : db.all("SELECT * FROM os_invitation ORDER BY sequence DESC LIMIT 100"))
            result.add(visible(db, row));
        return result;
    }

    static Map<String, Object> describe(Store.Db db, String token) throws Exception {
        return visible(db, pending(db, token, false));
    }

    private static Map<String, Object> pending(Store.Db db, String token, boolean lockIssuer)
            throws Exception {
        var row =
                db.one(
                        "SELECT * FROM os_invitation WHERE token_hash=?",
                        Passwords.tokenHash(token));
        if (row == null
                || !row.get("state").equals("pending")
                || ((Number) row.get("expires_at")).longValue() <= System.currentTimeMillis()
                || db.one(
                                "SELECT user_id FROM os_user WHERE user_id=? AND active=1 AND"
                                        + " role='admin' AND auth_revision=?"
                                        + (lockIssuer && db.postgres ? " FOR UPDATE" : ""),
                                row.get("issuer_id"),
                                row.get("issuer_revision"))
                        == null
                || db.one("SELECT user_id FROM os_user WHERE name=?", row.get("name")) != null)
            throw new Failure(400, "Invitation is unavailable or expired");
        return row;
    }

    static void accept(Store.Db db, String token, String password) throws Exception {
        if (db.postgres) db.all("SELECT pg_advisory_xact_lock(18439,2)");
        var invite = pending(db, token, true);
        String actor = invite.get("issuer_id").toString();
        Service.createManagedAccount(
                db,
                map("name", invite.get("name"), "role", invite.get("role"), "password", password),
                actor);
        String target =
                db.one("SELECT user_id FROM os_user WHERE name=?", invite.get("name"))
                        .get("user_id")
                        .toString();
        db.update(
                "UPDATE os_invitation SET state='accepted' WHERE invite_id=?",
                invite.get("invite_id"));
        SecurityEvents.record(
                db,
                actor,
                target,
                "account.invitation_accepted",
                map("invite_id", invite.get("invite_id")));
    }

    static Map<String, Object> revoke(Store.Db db, String actor, String id) throws SQLException {
        Service.requireAccountAdministrator(db, actor);
        var row = db.one("SELECT * FROM os_invitation WHERE invite_id=?", id);
        if (row == null) throw new Failure(404, "Invitation not found");
        if (row.get("state").equals("accepted"))
            throw new Failure(409, "Deactivate the accepted account to remove access");
        if (row.get("state").equals("revoked")) return visible(db, row);
        db.update(
                "UPDATE os_invitation SET state='revoked',revoked_by=? WHERE invite_id=?",
                actor,
                id);
        row.put("state", "revoked");
        row.put("revoked_by", actor);
        return visible(db, row);
    }
}
