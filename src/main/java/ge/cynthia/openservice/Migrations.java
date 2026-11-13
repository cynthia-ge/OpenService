package ge.cynthia.openservice;

import java.sql.SQLException;

/** Ordered, transactional upgrades. The caller commits schema and bootstrap data together. */
final class Migrations {
    static final int VERSION = 2;

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
    }

    private static void addColumn(Store.Db db, String table, String column, String definition)
            throws SQLException {
        try (var columns =
                db.connection
                        .getMetaData()
                        .getColumns(null, db.connection.getSchema(), table, column)) {
            if (!columns.next())
                db.update("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }
}
