package ge.cynthia.openservice;

import static ge.cynthia.openservice.TypedFields.require;
import static ge.cynthia.openservice.Values.*;

import java.sql.SQLException;
import java.util.*;

/** Administrator-only, checkpointed staging. No source credential is stored. */
final class TransferJobs {
    private TransferJobs() {}

    static final int FILE_LIMIT = 5 * 1024 * 1024;
    static final String LIMITS =
            "Read-only source access; no passwords, arbitrary scripts, ACL translation, deleted"
                    + " source data or external traffic switching. Review role mappings, source"
                    + " completeness and custom automation before cutover. Imported accounts start"
                    + " disabled and records private.";
    static final Map<String, String> SUPPORT =
            Map.of(
                    "sys_user", "sys_id,user_name,active",
                    "sys_user_role", "sys_id,name",
                    "sys_user_has_role", "sys_id,user,role",
                    "sys_user_grmember", "sys_id,user,group",
                    "sys_journal_field",
                            "sys_id,name,element_id,element,value,sys_created_by,sys_created_on",
                    "sys_audit",
                            "sys_id,tablename,documentkey,fieldname,oldvalue,newvalue,user,sys_created_on",
                    "sys_attachment",
                            "sys_id,table_name,table_sys_id,file_name,content_type,size_bytes");

    static Map<String, Object> create(Store.Db db, Object body, String actor, String allowed)
            throws Exception {
        Service.requireAccountAdministrator(db, actor);
        var plan = new LinkedHashMap<>(object(body));
        require(
                Set.of(
                                "source",
                                "tables",
                                "identities",
                                "roles",
                                "history",
                                "attachments",
                                "configuration")
                        .containsAll(plan.keySet()),
                "Unknown migration plan property");
        String source = ServiceNowSource.origin(Objects.toString(plan.get("source"), ""), allowed);
        var tables = object(plan.get("tables"));
        require(!tables.isEmpty() && tables.size() <= 20, "Select 1–20 source tables");
        Set<String> targets = new HashSet<>();
        for (var entry : tables.entrySet()) {
            require(
                    entry.getKey().matches("[a-z][a-z0-9_]{0,79}")
                            && !SUPPORT.containsKey(entry.getKey()),
                    "Invalid or reserved source table");
            var mapping = object(entry.getValue());
            require(
                    mapping.keySet().equals(Set.of("target", "fields", "values")),
                    "Table mappings need target, fields and values");
            String target = Objects.toString(mapping.get("target"), "");
            require(
                    target.matches("[a-z][a-z0-9_]{0,79}") && targets.add(target),
                    "Use distinct valid destination tables");
            var fields = object(mapping.get("fields"));
            require(!fields.isEmpty() && fields.size() <= 100, "Map 1–100 fields per table");
            for (var field : fields.entrySet())
                require(
                        field.getKey().matches("[a-z][a-z0-9_]{0,63}")
                                && field.getValue() instanceof String name
                                && name.matches("[a-z][a-z0-9_]{0,63}"),
                        "Invalid field mapping");
            var translations = object(mapping.get("values"));
            require(
                    fields.keySet().containsAll(translations.keySet()),
                    "Value translations need a mapped destination field");
            for (Object value : translations.values())
                for (Object replacement : object(value).values())
                    require(replacement instanceof String, "Translated values must be strings");
        }
        for (String flag : List.of("identities", "history", "attachments")) {
            plan.putIfAbsent(flag, false);
            require(plan.get(flag) instanceof Boolean, "Migration options must be booleans");
        }
        plan.putIfAbsent("roles", map());
        for (Object role : object(plan.get("roles")).values())
            require(
                    role instanceof String && Set.of("reader", "agent", "admin").contains(role),
                    "Map source roles to reader, agent or admin");
        if (Boolean.TRUE.equals(plan.get("identities")))
            require(
                    "sys_user_group"
                            .equals(
                                    object(tables.getOrDefault("sys_user_group", map()))
                                            .get("target")),
                    "Identity migration requires a sys_user_group mapping");
        plan.put("source", source);
        String job = id();
        db.update(
                "INSERT INTO"
                        + " os_transfer_job(job_id,source_url,creator_id,definition,detail,created)"
                        + " VALUES(?,?,?,?,?,?)",
                job,
                source,
                actor,
                JSON.writeValueAsString(plan),
                JSON.writeValueAsString(
                        map(
                                "status",
                                "fetching",
                                "phase",
                                0,
                                "cursor",
                                "",
                                "generation",
                                generation(db, source))),
                now());
        SecurityEvents.record(
                db, actor, actor, "migration.created", map("job_id", job, "source", source));
        return describe(db, job);
    }

    static long generation(Store.Db db, String source) throws SQLException {
        long generation = 0;
        for (var job : db.all("SELECT detail FROM os_transfer_job WHERE source_url=?", source))
            if (object(job.get("detail")).containsKey("applied_at")) generation++;
        return generation;
    }

    static List<Map<String, Object>> list(Store.Db db) throws SQLException {
        return db.all(
                "SELECT job_id,source_url,created,detail FROM os_transfer_job ORDER BY created"
                        + " DESC,job_id DESC");
    }

    static Map<String, Object> job(Store.Db db, String id) throws SQLException {
        var job = db.one("SELECT * FROM os_transfer_job WHERE job_id=?", id);
        if (job == null) throw new Failure(404, "Migration job not found");
        return job;
    }

    static List<String> phases(Map<String, Object> plan) {
        var phases = new ArrayList<String>();
        if (Boolean.TRUE.equals(plan.get("identities")))
            phases.addAll(List.of("sys_user", "sys_user_role", "sys_user_has_role"));
        phases.addAll(object(plan.get("tables")).keySet());
        if (Boolean.TRUE.equals(plan.get("identities"))) phases.add("sys_user_grmember");
        if (Boolean.TRUE.equals(plan.get("history")))
            phases.addAll(List.of("sys_journal_field", "sys_audit"));
        if (Boolean.TRUE.equals(plan.get("attachments"))) phases.add("sys_attachment");
        return phases;
    }

    static Map<String, Object> describe(Store.Db db, String id) throws SQLException {
        var job = job(db, id);
        job.put(
                "counts",
                db.all(
                        "SELECT source_table,COUNT(*) AS count FROM os_transfer_item WHERE job_id=?"
                                + " GROUP BY source_table ORDER BY source_table",
                        id));
        job.put("phases", phases(object(job.get("definition"))));
        job.put("limitations", LIMITS);
        return job;
    }

    static Map<String, Object> step(Store.Db db, String id, String token, String allowed)
            throws Exception {
        var job = job(db, id);
        var state = object(job.get("detail"));
        expect(state, "fetching");
        var plan = object(job.get("definition"));
        var phases = phases(plan);
        int phase = ((Number) state.get("phase")).intValue();
        String table = phases.get(phase), fields = SUPPORT.get(table), filter = "";
        var tables = object(plan.get("tables"));
        if (fields == null) {
            var selection =
                    new TreeSet<>(
                            List.of(
                                    "sys_id",
                                    "number",
                                    "sys_created_on",
                                    "sys_created_by",
                                    "sys_updated_on",
                                    "sys_updated_by"));
            for (Object value : object(object(tables.get(table)).get("fields")).values())
                selection.add(value.toString());
            fields = String.join(",", selection);
        }
        if (table.equals("sys_user_grmember")) {
            var groups = new ArrayList<String>();
            for (var row : rows(db, id, "sys_user_group"))
                groups.add(row.get("source_id").toString());
            filter = groups.isEmpty() ? "sys_idISEMPTY" : "groupIN" + String.join(",", groups);
        }
        String tableList = String.join(",", tables.keySet());
        if (table.equals("sys_attachment")) filter = "table_nameIN" + tableList;
        if (table.equals("sys_journal_field")) filter = "nameIN" + tableList;
        if (table.equals("sys_audit")) filter = "tablenameIN" + tableList;
        String origin = ServiceNowSource.origin(job.get("source_url").toString(), allowed);
        var rows =
                ServiceNowSource.page(
                        origin, token, table, filter, state.get("cursor").toString(), fields);
        require(
                db.count("SELECT COUNT(*) FROM os_transfer_item WHERE job_id=?", id) + rows.size()
                        <= 10000,
                "Migration is limited to 10000 staged rows; narrow the source export");
        long characters =
                db.count(
                        "SELECT COALESCE(SUM(LENGTH(data)),0) FROM os_transfer_item WHERE job_id=?",
                        id);
        for (var row : rows) characters += JSON.writeValueAsString(row).length();
        require(
                characters <= 50L * 1024 * 1024,
                "Staged source JSON exceeds 50 million characters");
        long bytes =
                db.count(
                        "SELECT COALESCE(SUM(LENGTH(content)),0) FROM os_transfer_item WHERE"
                                + " job_id=?",
                        id);
        for (var row : rows) {
            byte[] content = null;
            if (table.equals("sys_attachment")) {
                content =
                        ServiceNowSource.get(
                                origin,
                                token,
                                "/api/now/attachment/" + row.get("sys_id") + "/file",
                                FILE_LIMIT);
                require(
                        Integer.toString(content.length)
                                .equals(ServiceNowSource.text(row, "size_bytes")),
                        "Attachment size changed; restart a consistent source snapshot");
                bytes += content.length;
                require(bytes <= 50L * 1024 * 1024, "Staged attachments exceed 50 MB");
            }
            db.update(
                    "INSERT INTO os_transfer_item(job_id,source_table,source_id,data,content)"
                            + " VALUES(?,?,?,?,?)",
                    id,
                    table,
                    row.get("sys_id"),
                    JSON.writeValueAsString(row),
                    content);
        }
        if (rows.isEmpty()) {
            state.put("phase", phase + 1);
            state.put("cursor", "");
        } else state.put("cursor", rows.getLast().get("sys_id"));
        if (((Number) state.get("phase")).intValue() == phases.size())
            state.put("status", "review");
        save(db, id, state);
        return describe(db, id);
    }

    static void expect(Map<String, Object> state, String expected) {
        if (!expected.equals(state.get("status")))
            throw new Failure(409, "Migration must be in " + expected + " state");
    }

    static void save(Store.Db db, String id, Map<String, Object> state) throws SQLException {
        db.update(
                "UPDATE os_transfer_job SET detail=? WHERE job_id=?",
                JSON.writeValueAsString(state),
                id);
    }

    static List<Map<String, Object>> rows(Store.Db db, String job, String table)
            throws SQLException {
        return db.all(
                "SELECT source_id,data,content FROM os_transfer_item WHERE job_id=? AND"
                        + " source_table=? ORDER BY source_id",
                job,
                table);
    }

    static Map<String, Object> synchronize(Store.Db db, String id, String actor, String allowed)
            throws Exception {
        var job = job(db, id);
        expect(object(job.get("detail")), "applied");
        var plan = new LinkedHashMap<>(object(job.get("definition")));
        plan.remove("configuration");
        var next = create(db, plan, actor, allowed);
        var state = object(next.get("detail"));
        state.put("previous_job", id);
        save(db, next.get("job_id").toString(), state);
        return next;
    }

    static Map<String, Object> cutover(Store.Db db, String id, Object input, String actor)
            throws Exception {
        var job = job(db, id);
        var state = object(job.get("detail"));
        expect(state, "applied");
        require(
                object(input)
                        .equals(
                                map(
                                        "source_frozen",
                                        true,
                                        "completeness_reviewed",
                                        true,
                                        "permissions_reviewed",
                                        true,
                                        "automation_reviewed",
                                        true)),
                "Confirm source freeze, completeness, permissions and automation review");
        // A final sync must have completed after the administrator froze source writes.
        require(state.containsKey("previous_job"), "Run a final synchronization before cutover");
        if (generation(db, job.get("source_url").toString())
                != ((Number) state.get("generation")).longValue() + 1)
            throw new Failure(
                    409, "A newer migration was applied; cut over the latest synchronization");
        TransferApply.verify(db, job);
        state.put("status", "cutover");
        state.put("cutover_at", now());
        save(db, id, state);
        SecurityEvents.record(db, actor, actor, "migration.cutover", map("job_id", id));
        return describe(db, id);
    }
}
