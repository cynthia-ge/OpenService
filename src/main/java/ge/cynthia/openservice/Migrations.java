package ge.cynthia.openservice;

import java.sql.SQLException;

/** Ordered, transactional upgrades. The caller commits schema and bootstrap data together. */
final class Migrations {
    private Migrations() {}

    static void lock(Store.Db db) throws SQLException {
        // SQLite connections already begin IMMEDIATE transactions before any schema access.
        if (db.postgres) db.all("SELECT pg_advisory_xact_lock(18439, 1)");
    }

    static void apply(Store.Db db) throws SQLException {
        db.update(
                "CREATE TABLE IF NOT EXISTS os_migration (version INTEGER PRIMARY KEY, name"
                    + " VARCHAR(120) NOT NULL)");
        long version = db.count("SELECT COALESCE(MAX(version),0) FROM os_migration");
        if (version > 1 || db.count("SELECT COUNT(*) FROM os_migration") != version)
            throw new SQLException("Unsupported or incomplete OpenService migration history");
        if (version < 1) {
            try (var columns =
                    db.connection
                            .getMetaData()
                            .getColumns(
                                    null,
                                    db.connection.getSchema(),
                                    "os_attachment",
                                    "content_type")) {
                if (!columns.next())
                    db.update(
                            "ALTER TABLE os_attachment ADD COLUMN content_type VARCHAR(255) NOT"
                                + " NULL DEFAULT 'application/octet-stream'");
            }
            db.update(
                    "INSERT INTO os_migration(version,name) VALUES(1,?)",
                    "attachment content type");
        }
    }
}
