package ge.cynthia.openservice;

import static ge.cynthia.openservice.MigrationSource.key;
import static ge.cynthia.openservice.TransferAcceptance.*;
import static ge.cynthia.openservice.Values.*;
import static ge.cynthia.openservice.WorkflowSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

final class TransferLimitAcceptance {
    static void run(WorkflowSupport h) throws Exception {
        MigrationSource.reset();
        MigrationSource.tables.put(
                "incident",
                List.of(
                        map(
                                "sys_id",
                                key(9),
                                "short_description",
                                "Bounded",
                                "state",
                                "1",
                                "priority",
                                "3")));
        var plan = TransferValidationAcceptance.basic();
        plan.put("attachments", true);
        String job = result(h.json("POST", API, plan, auth("admin")), 201).get("job_id").toString();
        try (var db = h.store.open()) {
            var point = db.connection.setSavepoint();
            try (var statement =
                    db.statement(
                            "INSERT INTO os_transfer_item(job_id,source_table,source_id,data)"
                                + " VALUES(?,?,?,?)")) {
                for (int i = 0; i < 10000; i++) {
                    statement.setString(1, job);
                    statement.setString(2, "previous");
                    statement.setString(3, key(i + 100));
                    statement.setString(4, "{}");
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            assertEquals(
                    400,
                    assertThrows(
                                    Failure.class,
                                    () ->
                                            TransferJobs.step(
                                                    db,
                                                    job,
                                                    MigrationSource.TOKEN,
                                                    MigrationSource.origin()))
                            .status);
            db.update("DELETE FROM os_transfer_item WHERE job_id=? AND source_id=?", job, key(100));
            TransferJobs.step(db, job, MigrationSource.TOKEN, MigrationSource.origin());
            assertEquals(
                    10000, db.count("SELECT COUNT(*) FROM os_transfer_item WHERE job_id=?", job));
            db.connection.rollback(point);
            db.update(
                    "INSERT INTO os_transfer_item(job_id,source_table,source_id,data)"
                        + " VALUES(?,?,?,?)",
                    job,
                    "previous",
                    key(100),
                    JSON.writeValueAsString(map("body", "x".repeat(50 * 1024 * 1024))));
            assertTrue(
                    assertThrows(
                                    Failure.class,
                                    () ->
                                            TransferJobs.step(
                                                    db,
                                                    job,
                                                    MigrationSource.TOKEN,
                                                    MigrationSource.origin()))
                            .getMessage()
                            .contains("source JSON"));
            db.connection.rollback(point);
            var state = object(TransferJobs.job(db, job).get("detail"));
            state.put("phase", 1);
            TransferJobs.save(db, job, state);
            MigrationSource.tables.put(
                    "sys_attachment", List.of(map("sys_id", key(12), "size_bytes", "5")));
            MigrationSource.files.put(key(12), new byte[5]);
            var filePoint = db.connection.setSavepoint();
            db.update(
                    "INSERT INTO os_transfer_item(job_id,source_table,source_id,data,content)"
                        + " VALUES(?,?,?,?,?)",
                    job,
                    "previous",
                    key(100),
                    "{}",
                    new byte[50 * 1024 * 1024]);
            assertTrue(
                    assertThrows(
                                    Failure.class,
                                    () ->
                                            TransferJobs.step(
                                                    db,
                                                    job,
                                                    MigrationSource.TOKEN,
                                                    MigrationSource.origin()))
                            .getMessage()
                            .contains("50 MB"));
            db.connection.rollback(filePoint);
            db.update(
                    "INSERT INTO os_transfer_item(job_id,source_table,source_id,data,content)"
                        + " VALUES(?,?,?,?,?)",
                    job,
                    "previous",
                    key(100),
                    "{}",
                    new byte[50 * 1024 * 1024 - 5]);
            TransferJobs.step(db, job, MigrationSource.TOKEN, MigrationSource.origin());
            assertEquals(
                    50L * 1024 * 1024,
                    db.count(
                            "SELECT SUM(LENGTH(content)) FROM os_transfer_item WHERE job_id=?",
                            job));
            db.connection.rollback(filePoint);
            MigrationSource.tables.get("sys_attachment").getFirst().put("size_bytes", "6");
            assertTrue(
                    assertThrows(
                                    Failure.class,
                                    () ->
                                            TransferJobs.step(
                                                    db,
                                                    job,
                                                    MigrationSource.TOKEN,
                                                    MigrationSource.origin()))
                            .getMessage()
                            .contains("size changed"));
            assertEquals("", object(TransferJobs.job(db, job).get("detail")).get("cursor"));
            db.connection.rollback();
        }
        MigrationSource.reset();
    }
}
