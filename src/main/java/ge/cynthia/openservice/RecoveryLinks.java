package ge.cynthia.openservice;

import static ge.cynthia.openservice.Values.*;

import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

final class RecoveryLinks {
    private RecoveryLinks() {}

    private static void lockAccounts(Store.Db db, String actor, String target) throws SQLException {
        if (db.postgres)
            db.all(
                    "SELECT user_id FROM os_user WHERE user_id IN (?,?) ORDER BY user_id FOR"
                        + " UPDATE",
                    actor,
                    target);
    }

    static Map<String, Object> issue(Store.Db db, String actor, String target, Object body)
            throws Exception {
        var value = object(body);
        if (!value.keySet().equals(Set.of("current_password"))
                || !(value.get("current_password") instanceof String password)
                || password.codePointCount(0, password.length()) > 1024)
            throw new Failure(400, "Confirm your current administrator password");
        Service.requireAccountAdministrator(db, actor);
        lockAccounts(db, actor, target);
        var issuer = db.one("SELECT password,auth_revision FROM os_user WHERE user_id=?", actor);
        if (!Passwords.matches(issuer.get("password").toString(), password))
            throw new Failure(400, "Current administrator password is incorrect");
        var user = Service.account(db, target);
        if (!user.get("active").equals(true))
            throw new Failure(409, "Recovery requires an active account");
        var revision =
                db.one("SELECT auth_revision FROM os_user WHERE user_id=?", target)
                        .get("auth_revision");
        String token = Passwords.token();
        long expires = System.currentTimeMillis() + 15 * 60 * 1000L;
        db.update(
                "INSERT INTO"
                    + " os_recovery(user_id,token_hash,issuer_id,auth_revision,issuer_revision,expires_at)"
                    + " VALUES(?,?,?,?,?,?) ON CONFLICT(user_id) DO UPDATE SET"
                    + " token_hash=excluded.token_hash,issuer_id=excluded.issuer_id,auth_revision=excluded.auth_revision,issuer_revision=excluded.issuer_revision,expires_at=excluded.expires_at",
                target,
                Passwords.tokenHash(token),
                actor,
                revision,
                issuer.get("auth_revision"),
                expires);
        String expiry = Instant.ofEpochMilli(expires).toString();
        SecurityEvents.record(
                db, actor, target, "account.recovery_issued", map("expires_at", expiry));
        return map("reset_path", "/recover/" + token, "expires_at", expiry);
    }

    private static Map<String, Object> pending(Store.Db db, String token, boolean lock)
            throws Exception {
        var link =
                db.one("SELECT * FROM os_recovery WHERE token_hash=?", Passwords.tokenHash(token));
        if (link == null
                || ((Number) link.get("expires_at")).longValue() <= System.currentTimeMillis())
            throw new Failure(400, "Recovery link is unavailable or expired");
        if (lock)
            lockAccounts(db, link.get("issuer_id").toString(), link.get("user_id").toString());
        var target =
                db.one(
                        "SELECT name,password FROM os_user WHERE user_id=? AND active=1 AND"
                            + " auth_revision=?",
                        link.get("user_id"),
                        link.get("auth_revision"));
        if (target == null
                || db.one(
                                "SELECT user_id FROM os_user WHERE user_id=? AND active=1 AND"
                                    + " role='admin' AND auth_revision=?",
                                link.get("issuer_id"),
                                link.get("issuer_revision"))
                        == null) throw new Failure(400, "Recovery link is unavailable or expired");
        link.put("name", target.get("name"));
        link.put("password", target.get("password"));
        return link;
    }

    static Map<String, Object> describe(Store.Db db, String token) throws Exception {
        var link = pending(db, token, false);
        return map(
                "name",
                link.get("name"),
                "expires_at",
                Instant.ofEpochMilli(((Number) link.get("expires_at")).longValue()).toString());
    }

    static void recover(Store.Db db, String token, String password) throws Exception {
        if (password.codePointCount(0, password.length()) < 12
                || password.codePointCount(0, password.length()) > 1024)
            throw new Failure(400, "Password must contain 12–1024 characters");
        if (db.postgres) db.all("SELECT pg_advisory_xact_lock(18439,2)");
        var link = pending(db, token, true);
        if (Passwords.matches(link.get("password").toString(), password))
            throw new Failure(400, "Choose a different password");
        String target = link.get("user_id").toString();
        db.update(
                "UPDATE os_user SET password=? WHERE user_id=?", Passwords.hash(password), target);
        Service.revokeSessions(db, target);
        db.update("DELETE FROM os_recovery WHERE user_id=?", target);
        SecurityEvents.record(
                db,
                target,
                target,
                "account.password_recovered",
                map("issued_by", link.get("issuer_id")));
    }

    static void revoke(Store.Db db, String actor, String target) throws SQLException {
        Service.requireAccountAdministrator(db, actor);
        Service.account(db, target);
        if (db.update("DELETE FROM os_recovery WHERE user_id=?", target) > 0)
            SecurityEvents.record(db, actor, target, "account.recovery_revoked", map());
    }
}
