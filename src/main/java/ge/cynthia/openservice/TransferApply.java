package ge.cynthia.openservice;

import static ge.cynthia.openservice.ServiceNowSource.text;
import static ge.cynthia.openservice.TypedFields.require;
import static ge.cynthia.openservice.Values.*;

import org.apache.commons.codec.digest.DigestUtils;

import java.sql.SQLException;
import java.util.*;

/** Atomic promotion of reviewed snapshots, with optimistic destination conflict checks. */
final class TransferApply {
    private TransferApply() {}

    static Map<String, Object> apply(Store.Db db, String id, String actor, boolean preview)
            throws Exception {
        Service.requireAccountAdministrator(db, actor);
        var job = TransferJobs.job(db, id);
        var state = object(job.get("detail"));
        TransferJobs.expect(state, "review");
        if (TransferJobs.generation(db, job.get("source_url").toString())
                != ((Number) state.get("generation")).longValue())
            throw new Failure(
                    409,
                    "Another migration was applied after this job started; create a fresh"
                            + " synchronization");
        var checkpoint = db.connection.setSavepoint();
        var plan = object(job.get("definition"));
        var counts = map("records", 0, "accounts", 0, "memberships", 0, "attachments", 0);
        if (plan.containsKey("configuration"))
            Configuration.install(db, plan.get("configuration"), actor, false);
        if (Boolean.TRUE.equals(plan.get("identities"))) accounts(db, job, counts);
        for (var table : object(plan.get("tables")).entrySet()) {
            var mapping = object(table.getValue());
            for (var row : TransferJobs.rows(db, id, table.getKey())) {
                var data = object(row.get("data"));
                for (var field :
                        Map.of(
                                        "sys_created_on",
                                        40,
                                        "sys_updated_on",
                                        40,
                                        "sys_created_by",
                                        80,
                                        "sys_updated_by",
                                        80,
                                        "number",
                                        65536)
                                .entrySet()) {
                    if (data.containsKey(field.getKey()))
                        require(
                                data.get(field.getKey()) instanceof String value
                                        && value.length() <= field.getValue(),
                                "Source metadata exceeds its supported text limit: "
                                        + field.getKey());
                }
                var signature = map("source", data, "mapping", mapping);
                var link = link(db, job, table.getKey(), text(data, "sys_id"), signature, "record");
                if (link == null) continue;
                String target = mapping.get("target").toString();
                var definition = Service.schema(db, target);
                var values = new LinkedHashMap<String, Object>();
                for (String field : object(definition.get("fields")).keySet())
                    values.put(field, "");
                var translations = object(mapping.get("values"));
                for (var field : object(mapping.get("fields")).entrySet()) {
                    String value = text(data, field.getValue().toString());
                    values.put(
                            field.getKey(),
                            object(translations.getOrDefault(field.getKey(), map()))
                                    .getOrDefault(value, value));
                }
                Service.validate(definition, values);
                require(
                        !values.get("short_description").toString().isBlank(),
                        "Mapped records need a summary");
                TypedFields.writing(definition, map(), values, "admin");
                String recordId = text(data, "sys_id");
                var existing = db.one("SELECT * FROM os_record WHERE sys_id=?", recordId);
                values.put(
                        "number",
                        data.getOrDefault(
                                "number",
                                definition.get("prefix")
                                        + recordId.substring(0, 8).toUpperCase(Locale.ROOT)));
                if (existing == null) {
                    Service.insert(
                            db,
                            map(
                                    "sys_id",
                                    recordId,
                                    "table",
                                    target,
                                    "data",
                                    values,
                                    "original",
                                    map(),
                                    "created",
                                    data.getOrDefault("sys_created_on", now()),
                                    "updated",
                                    data.getOrDefault("sys_updated_on", now()),
                                    "creator",
                                    text(data, "sys_created_by"),
                                    "updater",
                                    text(data, "sys_updated_by"),
                                    "revision",
                                    0));
                    db.update(
                            "INSERT INTO os_record_access(record_id,visibility,owner_id)"
                                    + " VALUES(?,'private',?)",
                            recordId,
                            actor);
                } else {
                    require(existing.get("table").equals(target), "Migration target table changed");
                    existing.put("data", values);
                    existing.put("revision", ((Number) existing.get("revision")).intValue() + 1);
                    existing.put("updated", data.getOrDefault("sys_updated_on", now()));
                    existing.put("updater", text(data, "sys_updated_by"));
                    Service.save(db, existing);
                }
                remember(db, job, table.getKey(), recordId, link, "record");
                counts.put("records", (int) counts.get("records") + 1);
            }
        }
        // Validate references after all selected tables have been staged in this transaction.
        for (var table : object(plan.get("tables")).entrySet()) {
            String target = object(table.getValue()).get("target").toString();
            for (var row : TransferJobs.rows(db, id, table.getKey())) {
                var record = Service.record(db, target, row.get("source_id").toString());
                TypedFields.check(db, Service.schema(db, target), object(record.get("data")));
            }
        }
        if (Boolean.TRUE.equals(plan.get("identities"))) members(db, job, counts);
        if (Boolean.TRUE.equals(plan.get("attachments"))) attachments(db, job, counts);
        var unmapped = new TreeSet<String>();
        for (var role : TransferJobs.rows(db, id, "sys_user_role")) {
            String name = text(object(role.get("data")), "name");
            if (!object(plan.get("roles")).containsKey(name)) unmapped.add(name);
        }
        var missing = missing(db, job);
        var result =
                map(
                        "preview",
                        preview,
                        "changed",
                        counts,
                        "missing_source_items",
                        missing,
                        "unmapped_source_roles",
                        unmapped,
                        "history_rows",
                        db.count(
                                "SELECT COUNT(*) FROM os_transfer_item WHERE job_id=? AND"
                                        + " source_table IN ('sys_audit','sys_journal_field')",
                                id),
                        "limitations",
                        TransferJobs.LIMITS);
        if (preview) db.connection.rollback(checkpoint);
        else {
            state.put("status", "applied");
            state.put("report", result);
            state.put("applied_at", now());
            TransferJobs.save(db, id, state);
            SecurityEvents.record(
                    db, actor, actor, "migration.applied", map("job_id", id, "changed", counts));
        }
        db.connection.releaseSavepoint(checkpoint);
        return result;
    }

    static Map<String, Object> snapshot(Store.Db db, String kind, String id) throws SQLException {
        var value =
                switch (kind) {
                    case "account" ->
                            db.one(
                                    "SELECT user_id,name,role,active,auth_revision FROM os_user"
                                            + " WHERE user_id=?",
                                    id);
                    case "member" ->
                            db.one(
                                    "SELECT group_id,user_id FROM os_group_member WHERE group_id=?"
                                            + " AND user_id=?",
                                    id.substring(0, 32),
                                    id.substring(32));
                    case "attachment" -> {
                        var attachment =
                                db.one(
                                        "SELECT record_id,name,content_type,content FROM"
                                                + " os_attachment WHERE sys_id=?",
                                        id);
                        if (attachment != null)
                            attachment.put(
                                    "content",
                                    DigestUtils.sha256Hex((byte[]) attachment.get("content")));
                        yield attachment;
                    }
                    default -> {
                        var record =
                                db.one(
                                        "SELECT sys_id,\"table\",data,revision FROM os_record WHERE"
                                                + " sys_id=?",
                                        id);
                        if (record != null) record.put("access", RecordAccess.status(db, id));
                        yield record;
                    }
                };
        return value == null
                ? null
                : object(JSON.readValue(JSON.writeValueAsString(value), Object.class));
    }

    static Map<String, Object> link(
            Store.Db db,
            Map<String, Object> job,
            String table,
            String sourceId,
            Object source,
            String kind)
            throws SQLException {
        String targetId = sourceId;
        if (kind.equals("member")) {
            var data = object(source);
            targetId = text(data, "group") + text(data, "user");
            require(
                    targetId.matches("[a-f0-9]{64}"),
                    "Membership requires source user and group IDs");
        }
        var current = snapshot(db, kind, targetId);
        var previous =
                db.one(
                        "SELECT detail FROM os_transfer_link WHERE source_url=? AND source_table=?"
                                + " AND source_id=?",
                        job.get("source_url"),
                        table,
                        sourceId);
        if (previous == null) {
            if (current != null)
                throw new Failure(409, "Destination identifier already exists: " + sourceId);
        } else {
            var detail = object(previous.get("detail"));
            if (!Objects.equals(detail.get("destination"), current))
                throw new Failure(409, "Destination changed since migration: " + sourceId);
            if (Objects.equals(detail.get("source"), source)) return null;
        }
        return map("source", source, "kind", kind, "target", targetId);
    }

    static void remember(
            Store.Db db,
            Map<String, Object> job,
            String table,
            String sourceId,
            Map<String, Object> detail,
            String kind)
            throws SQLException {
        detail.put("destination", snapshot(db, kind, detail.get("target").toString()));
        db.update(
                "INSERT INTO os_transfer_link(source_url,source_table,source_id,target_id,detail)"
                    + " VALUES(?,?,?,?,?) ON CONFLICT(source_url,source_table,source_id) DO UPDATE"
                    + " SET target_id=excluded.target_id,detail=excluded.detail",
                job.get("source_url"),
                table,
                sourceId,
                detail.get("target"),
                JSON.writeValueAsString(detail));
    }

    static void accounts(Store.Db db, Map<String, Object> job, Map<String, Object> counts)
            throws Exception {
        String id = job.get("job_id").toString();
        var plan = object(job.get("definition"));
        var names = new HashMap<String, String>();
        for (var row : TransferJobs.rows(db, id, "sys_user_role"))
            names.put(row.get("source_id").toString(), text(object(row.get("data")), "name"));
        var roles = new HashMap<String, String>();
        var rank = List.of("reader", "agent", "admin");
        for (var row : TransferJobs.rows(db, id, "sys_user_has_role")) {
            var data = object(row.get("data"));
            String user = text(data, "user"),
                    role =
                            object(plan.get("roles"))
                                    .getOrDefault(
                                            names.getOrDefault(text(data, "role"), ""), "reader")
                                    .toString();
            if (rank.indexOf(role) > rank.indexOf(roles.getOrDefault(user, "reader")))
                roles.put(user, role);
        }
        for (var row : TransferJobs.rows(db, id, "sys_user")) {
            var data = object(row.get("data"));
            String user = text(data, "sys_id"),
                    role = roles.getOrDefault(user, "reader"),
                    name = text(data, "user_name");
            if (!name.matches("[A-Za-z0-9]{1,80}")) name = "sn" + user;
            var link =
                    link(db, job, "sys_user", user, map("source", data, "role", role), "account");
            if (link == null) continue;
            var collision =
                    db.one("SELECT user_id FROM os_user WHERE name=? AND user_id<>?", name, user);
            require(collision == null, "Destination username already exists: " + name);
            if (snapshot(db, "account", user) == null)
                db.update(
                        "INSERT INTO os_user(user_id,name,password,role,active) VALUES(?,?,?,?,0)",
                        user,
                        name,
                        Passwords.hash(id() + id()),
                        role);
            else
                db.update(
                        "UPDATE os_user SET name=?,role=?,active=0,auth_revision=auth_revision+1"
                                + " WHERE user_id=?",
                        name,
                        role,
                        user);
            remember(db, job, "sys_user", user, link, "account");
            counts.put("accounts", (int) counts.get("accounts") + 1);
        }
    }

    static void members(Store.Db db, Map<String, Object> job, Map<String, Object> counts)
            throws SQLException {
        for (var row : TransferJobs.rows(db, job.get("job_id").toString(), "sys_user_grmember")) {
            var data = object(row.get("data"));
            var link = link(db, job, "sys_user_grmember", text(data, "sys_id"), data, "member");
            if (link == null) continue;
            require(
                    db.one(
                                    "SELECT user_id FROM os_user WHERE user_id=? AND EXISTS (SELECT"
                                            + " 1 FROM os_transfer_item WHERE job_id=? AND"
                                            + " source_table='sys_user' AND source_id=?)",
                                    text(data, "user"),
                                    job.get("job_id"),
                                    text(data, "user"))
                            != null,
                    "Membership user was not migrated");
            require(
                    db.one(
                                    "SELECT sys_id FROM os_record WHERE sys_id=? AND"
                                        + " \"table\"='sys_user_group' AND EXISTS (SELECT 1 FROM"
                                        + " os_transfer_item WHERE job_id=? AND"
                                        + " source_table='sys_user_group' AND source_id=?)",
                                    text(data, "group"),
                                    job.get("job_id"),
                                    text(data, "group"))
                            != null,
                    "Membership group was not migrated");
            db.update(
                    "INSERT INTO os_group_member(group_id,user_id) VALUES(?,?) ON"
                            + " CONFLICT(group_id,user_id) DO NOTHING",
                    text(data, "group"),
                    text(data, "user"));
            remember(db, job, "sys_user_grmember", text(data, "sys_id"), link, "member");
            counts.put("memberships", (int) counts.get("memberships") + 1);
        }
    }

    static void attachments(Store.Db db, Map<String, Object> job, Map<String, Object> counts)
            throws SQLException {
        for (var row : TransferJobs.rows(db, job.get("job_id").toString(), "sys_attachment")) {
            var data = object(row.get("data"));
            String id = text(data, "sys_id"),
                    parent = text(data, "table_sys_id"),
                    name = text(data, "file_name"),
                    type = text(data, "content_type");
            require(
                    object(object(job.get("definition")).get("tables"))
                            .containsKey(text(data, "table_name")),
                    "Attachment table was not selected");
            require(
                    db.one(
                                    "SELECT source_id FROM os_transfer_item WHERE job_id=? AND"
                                            + " source_table=? AND source_id=?",
                                    job.get("job_id"),
                                    text(data, "table_name"),
                                    parent)
                            != null,
                    "Attachment parent was not staged");
            require(
                    !name.isBlank()
                            && name.length() <= 255
                            && name.chars()
                                    .noneMatch(c -> c < 32 || c == 127 || c == '/' || c == '\\'),
                    "Invalid attachment filename");
            require(
                    type.length() <= 255
                            && type.matches("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+"),
                    "Invalid attachment media type");
            var link =
                    link(
                            db,
                            job,
                            "sys_attachment",
                            id,
                            map(
                                    "metadata",
                                    data,
                                    "sha256",
                                    DigestUtils.sha256Hex((byte[]) row.get("content"))),
                            "attachment");
            if (link == null) continue;
            db.update(
                    "INSERT INTO os_attachment(sys_id,record_id,name,content,content_type)"
                        + " VALUES(?,?,?,?,?) ON CONFLICT(sys_id) DO UPDATE SET"
                        + " record_id=excluded.record_id,name=excluded.name,content=excluded.content,content_type=excluded.content_type",
                    id,
                    parent,
                    name,
                    row.get("content"),
                    type);
            remember(db, job, "sys_attachment", id, link, "attachment");
            counts.put("attachments", (int) counts.get("attachments") + 1);
        }
    }

    static List<Map<String, Object>> missing(Store.Db db, Map<String, Object> job)
            throws SQLException {
        return db.all(
                "SELECT l.source_table,l.source_id FROM os_transfer_link l WHERE l.source_url=? AND"
                        + " NOT EXISTS (SELECT 1 FROM os_transfer_item i WHERE i.job_id=? AND"
                        + " i.source_table=l.source_table AND i.source_id=l.source_id) ORDER BY"
                        + " l.source_table,l.source_id",
                job.get("source_url"),
                job.get("job_id"));
    }

    static void verify(Store.Db db, Map<String, Object> job) throws SQLException {
        require(
                missing(db, job).isEmpty(),
                "Source items are missing; reconcile source deletions or permissions before"
                        + " cutover");
        for (var row :
                db.all(
                        "SELECT detail FROM os_transfer_link WHERE source_url=?",
                        job.get("source_url"))) {
            var detail = object(row.get("detail"));
            if (!Objects.equals(
                    detail.get("destination"),
                    snapshot(db, detail.get("kind").toString(), detail.get("target").toString())))
                throw new Failure(409, "Destination changed after synchronization");
        }
    }
}
