package ge.cynthia.openservice;

import java.sql.SQLException;

/** Ordered, transactional upgrades. The caller commits schema and bootstrap data together. */
final class Migrations {
    static final int VERSION = 6;

    private Migrations() {}

    static void lock(Store.Db db) throws SQLException {
        // SQLite connections already begin IMMEDIATE transactions before any schema access.
        if (db.postgres) db.all("SELECT pg_advisory_xact_lock(18439, 1)");
    }

    static void apply(Store.Db db) throws SQLException {
        db.update(
                "CREATE TABLE IF NOT EXISTS os_migration (version INTEGER PRIMARY KEY, name"
                        + " VARCHAR(120) NOT NULL)");
        var history = db.all("SELECT version FROM os_migration ORDER BY version");
        int version = history.size();
        if (version > VERSION) throw new SQLException("Unsupported OpenService migration history");
        for (int i = 0; i < version; i++)
            if (((Number) history.get(i).get("version")).intValue() != i + 1)
                throw new SQLException("Incomplete OpenService migration history");
        if (version < 1) {
            addColumn(
                    db,
                    "os_attachment",
                    "content_type",
                    "VARCHAR(255) NOT NULL DEFAULT 'application/octet-stream'");
            db.update(
                    "INSERT INTO os_migration(version,name) VALUES(1,?)",
                    "attachment content type");
        }
        if (version < 2) {
            addColumn(db, "os_user", "user_id", "VARCHAR(32) NOT NULL DEFAULT ''");
            for (var user : db.all("SELECT name FROM os_user WHERE user_id=''"))
                db.update(
                        "UPDATE os_user SET user_id=? WHERE name=?", Values.id(), user.get("name"));
            db.update("CREATE UNIQUE INDEX IF NOT EXISTS ix_os_user_identity ON os_user(user_id)");
            db.update(
                    "INSERT INTO os_migration(version,name) VALUES(2,?)", "stable user identities");
        }
        if (version < 3) {
            addColumn(db, "os_user", "auth_revision", "BIGINT NOT NULL DEFAULT 0");
            db.update(
                    "INSERT INTO os_migration(version,name) VALUES(3,?)",
                    "revocable browser sessions");
        }
        if (version < 4) {
            addColumn(
                    db, "os_user", "active", "INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0,1))");
            db.update(
                    "INSERT INTO os_migration(version,name) VALUES(4,?)",
                    "account activation state");
        }
        if (version < 5) {
            db.update(
                    "CREATE TABLE IF NOT EXISTS os_security_event (sequence "
                            + (db.postgres
                                    ? "BIGSERIAL PRIMARY KEY"
                                    : "INTEGER PRIMARY KEY AUTOINCREMENT")
                            + ",event_id VARCHAR(32) NOT NULL UNIQUE,occurred_at VARCHAR(40) NOT"
                            + " NULL,actor_id VARCHAR(32) NOT NULL,actor_name VARCHAR(80) NOT"
                            + " NULL,target_id VARCHAR(32) NOT NULL,target_name VARCHAR(80) NOT"
                            + " NULL,action VARCHAR(64) NOT NULL,detail TEXT NOT NULL)");
            db.update(
                    "INSERT INTO os_migration(version,name) VALUES(5,?)",
                    "account security events");
        }
        if (version < 6) {
            db.update(
                    "CREATE TABLE IF NOT EXISTS os_invitation (sequence "
                            + (db.postgres
                                    ? "BIGSERIAL PRIMARY KEY"
                                    : "INTEGER PRIMARY KEY AUTOINCREMENT")
                            + ",invite_id VARCHAR(32) NOT NULL UNIQUE,name VARCHAR(80) NOT"
                            + " NULL,role VARCHAR(20) NOT NULL,token_hash VARCHAR(64) NOT NULL"
                            + " UNIQUE,created_at VARCHAR(40) NOT NULL,expires_at BIGINT NOT"
                            + " NULL,issuer_id VARCHAR(32) NOT NULL,issuer_revision BIGINT NOT"
                            + " NULL,state VARCHAR(20) NOT NULL,revoked_by VARCHAR(32) NOT NULL"
                            + " DEFAULT '')");
            db.update(
                    "INSERT INTO os_migration(version,name) VALUES(6,?)",
                    "manual invitation onboarding");
        }
    }

    private static void addColumn(Store.Db db, String table, String column, String definition)
            throws SQLException {
        try (var columns =
                db.connection
                        .getMetaData()
                        .getColumns(null, db.connection.getSchema(), table, column)) {
            if (!columns.next()) {
                // SQLite may expose constraint validation as a result during ALTER TABLE.
                try (var query =
                        db.statement(
                                "ALTER TABLE "
                                        + table
                                        + " ADD COLUMN "
                                        + column
                                        + " "
                                        + definition)) {
                    query.execute();
                }
            }
        }
    }
}
