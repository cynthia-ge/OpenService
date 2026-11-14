package ge.cynthia.openservice;

import static ge.cynthia.openservice.Values.*;

import java.util.Map;

/** Authentication is committed before any business mutation so failed attempts remain durable. */
final class Authentication {
    private Authentication() {}

    private static final String DUMMY = "pbkdf2:sha256:600000$OpenServiceDummy$" + "00".repeat(32);

    static void requireCurrent(Store.Db db, Map<String, Object> user) throws java.sql.SQLException {
        if (db.one(
                        "SELECT user_id FROM os_user WHERE user_id=? AND active=1 AND"
                            + " auth_revision=?",
                        user.get("user_id"),
                        user.get("auth_revision"))
                == null) throw new Failure(401, "Account access changed; sign in again");
    }

    static Map<String, Object> verify(Store.Db db, String name, String password, String mechanism)
            throws Exception {
        // SQLite already holds an IMMEDIATE transaction. Release this lock before business work.
        if (db.postgres) db.all("SELECT pg_advisory_xact_lock(18439,3)");
        long now = System.currentTimeMillis();
        db.update("DELETE FROM os_auth_attempt WHERE expires_at<=?", now);
        String key = Passwords.tokenHash(name);
        var attempt = db.one("SELECT failures FROM os_auth_attempt WHERE identity_hash=?", key);
        if (attempt != null && ((Number) attempt.get("failures")).intValue() >= 5) {
            AuthenticationEvents.record(db, name, key, mechanism, "limited");
            db.connection.commit();
            throw new Failure(429, "Too many sign-in attempts. Try again in five minutes.");
        }
        var user =
                name.indexOf(0) >= 0
                        ? null
                        : db.one("SELECT * FROM os_user WHERE name=? AND active=1", name);
        boolean valid =
                password.codePointCount(0, password.length()) <= 1024
                        && Passwords.matches(
                                user == null ? DUMMY : user.get("password").toString(), password);
        if (user != null && valid) {
            db.update("DELETE FROM os_auth_attempt WHERE identity_hash=?", key);
            AuthenticationEvents.record(db, name, key, mechanism, "succeeded");
            db.connection.commit();
            return user;
        }
        if (attempt == null) {
            if (db.count("SELECT COUNT(*) FROM os_auth_attempt") >= 4096) {
                AuthenticationEvents.record(db, name, key, mechanism, "limited");
                db.connection.commit();
                throw new Failure(429, "Too many sign-in attempts. Try again in five minutes.");
            }
            db.update(
                    "INSERT INTO os_auth_attempt(identity_hash,failures,expires_at) VALUES(?,1,?)",
                    key,
                    now + 300000L);
        } else
            db.update("UPDATE os_auth_attempt SET failures=failures+1 WHERE identity_hash=?", key);
        AuthenticationEvents.record(db, name, key, mechanism, "failed");
        db.connection.commit();
        return null;
    }
}
