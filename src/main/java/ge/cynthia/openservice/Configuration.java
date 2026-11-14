package ge.cynthia.openservice;

import static ge.cynthia.openservice.TypedFields.require;
import static ge.cynthia.openservice.Values.*;

import java.sql.SQLException;
import java.util.*;

/** Atomic, optimistic configuration promotion. This format is not a ServiceNow update set. */
final class Configuration {
    private Configuration() {}

    static final String FORMAT = "openservice.configuration.v1";

    static Map<String, Object> export(Store.Db db) throws SQLException {
        var tables = new LinkedHashMap<String, Object>();
        for (var row : db.all("SELECT name,definition FROM os_schema ORDER BY name")) {
            var definition = object(row.get("definition"));
            definition.putIfAbsent("version", 0);
            tables.put(row.get("name").toString(), definition);
        }
        return map("format", FORMAT, "tables", tables);
    }

    static Map<String, Object> install(Store.Db db, Object body, String actor, boolean dryRun)
            throws SQLException {
        Service.requireAccountAdministrator(db, actor);
        var document = object(body);
        require(document.keySet().equals(Set.of("format", "tables")), "Provide format and tables");
        require(FORMAT.equals(document.get("format")), "Unsupported configuration format");
        var tables = object(document.get("tables"));
        require(!tables.isEmpty() && tables.size() <= 50, "Provide 1–50 table definitions");
        var checkpoint = db.connection.setSavepoint();
        var versions = new LinkedHashMap<String, Object>();
        for (var table : tables.entrySet()) {
            var definition = new LinkedHashMap<>(object(table.getValue()));
            validate(definition);
            var previous = db.one("SELECT definition FROM os_schema WHERE name=?", table.getKey());
            int version = (int) definition.get("version");
            if (previous == null) {
                require(
                        table.getKey().matches("u_[a-z][a-z0-9_]{0,60}"),
                        "New table names must start with u_ and use lowercase identifiers");
                if (version != 0) throw new Failure(409, "New tables require version 0");
            } else {
                var old = object(previous.get("definition"));
                if (!Objects.equals(old.getOrDefault("version", 0), version))
                    throw new Failure(
                            409,
                            "Configuration version changed; export and reconcile before"
                                    + " installing");
                require(
                        object(definition.get("fields"))
                                .keySet()
                                .containsAll(object(old.get("fields")).keySet()),
                        "Removing existing fields requires a separate data migration");
            }
            definition.put("version", version + 1);
            db.update(
                    "INSERT INTO os_schema(name,definition) VALUES(?,"
                            + db.jsonParameter()
                            + ") ON CONFLICT(name) DO UPDATE SET definition=excluded.definition",
                    table.getKey(),
                    JSON.writeValueAsString(definition));
            versions.put(table.getKey(), version + 1);
        }
        // Resolve dependencies after staging the complete package; order does not matter.
        for (String table : tables.keySet()) {
            var definition = Service.schema(db, table);
            for (Object value : TypedFields.rules(definition).values()) {
                var rule = object(value);
                if (rule.get("type").equals("reference"))
                    require(
                            db.one("SELECT name FROM os_schema WHERE name=?", rule.get("target"))
                                    != null,
                            "Reference target table is not installed");
            }
            for (var item : db.all("SELECT data FROM os_record WHERE \"table\"=?", table)) {
                var data = new LinkedHashMap<>(object(item.get("data")));
                data.remove("number");
                Service.validate(definition, data);
                TypedFields.check(db, definition, data);
            }
        }
        if (dryRun) db.connection.rollback(checkpoint);
        else
            SecurityEvents.record(
                    db, actor, actor, "configuration.installed", map("versions", versions));
        db.connection.releaseSavepoint(checkpoint);
        return map("dry_run", dryRun, "versions", versions);
    }

    static void validate(Map<String, Object> definition) {
        require(
                Set.of(
                                "version",
                                "label",
                                "prefix",
                                "fields",
                                "states",
                                "flow",
                                "field_rules",
                                "transition_rules",
                                "assignment")
                        .containsAll(definition.keySet()),
                "Unsupported table configuration property");
        require(definition.get("version") instanceof Integer, "Provide an integer version");
        int version = (int) definition.get("version");
        require(version >= 0 && version < Integer.MAX_VALUE, "Version is out of range");
        label(definition.get("label"));
        require(definition.get("prefix") instanceof String, "Provide a prefix");
        require(
                definition.get("prefix").toString().matches("[A-Z]{1,12}"),
                "Prefix must contain 1–12 uppercase letters");
        var fields = object(definition.get("fields"));
        require(
                fields.containsKey("short_description") && fields.size() <= 100,
                "Provide a summary and at most 100 fields");
        for (var field : fields.entrySet()) {
            require(
                    field.getKey().matches("[a-z][a-z0-9_]{0,63}")
                            && !field.getKey().startsWith("sys_")
                            && !field.getKey().equals("number"),
                    "Invalid or reserved field identifier");
            label(field.getValue());
        }
        var states = object(definition.get("states"));
        require(states.size() <= 50, "At most 50 states are supported");
        require(
                fields.containsKey("state") == !states.isEmpty(),
                "A state field requires a nonempty state model");
        for (var state : states.entrySet()) {
            require(state.getKey().matches("[A-Za-z0-9_-]{1,40}"), "Invalid state identifier");
            label(state.getValue());
        }
        var flow = object(definition.get("flow"));
        require(states.keySet().containsAll(flow.keySet()), "Unknown source state");
        for (Object targets : flow.values()) choices(targets, states.keySet());
        var rules = TypedFields.rules(definition);
        require(
                fields.keySet().containsAll(rules.keySet()),
                "Field rule references an unknown field");
        for (Object value : rules.values()) {
            var rule = object(value);
            require(rule.get("type") instanceof String, "Provide a field type");
            require(
                    TypedFields.TYPES.contains(rule.getOrDefault("type", "")),
                    "Unsupported field type");
            var allowed = new HashSet<>(Set.of("type", "required", "max_length", "write_roles"));
            if (rule.containsKey("write_roles"))
                choices(rule.get("write_roles"), Set.of("admin", "agent"));
            switch (rule.get("type").toString()) {
                case "choice" -> {
                    allowed.add("choices");
                    choices(rule.get("choices"), null);
                }
                case "reference" -> {
                    allowed.add("target");
                    require(
                            rule.get("target") instanceof String,
                            "Provide a reference target table");
                }
                default -> {}
            }
            require(allowed.containsAll(rule.keySet()), "Unsupported field rule property");
            require(
                    rule.getOrDefault("required", false) instanceof Boolean,
                    "required must be boolean");
            require(
                    rule.getOrDefault("max_length", 65536) instanceof Integer,
                    "max_length must be an integer");
            int length = (int) rule.getOrDefault("max_length", 65536);
            require(length >= 1 && length <= 65536, "max_length must be 1–65536");
        }
        var transitions = object(definition.getOrDefault("transition_rules", map()));
        require(states.keySet().containsAll(transitions.keySet()), "Unknown policy state");
        for (Object value : transitions.values()) {
            var rule = object(value);
            require(
                    Set.of("roles", "required_fields").containsAll(rule.keySet()),
                    "Unsupported transition policy");
            if (rule.containsKey("roles")) choices(rule.get("roles"), Set.of("admin", "agent"));
            if (rule.containsKey("required_fields"))
                choices(rule.get("required_fields"), fields.keySet());
        }
        var assignment = object(definition.getOrDefault("assignment", map()));
        if (!assignment.isEmpty()) {
            require(
                    assignment.keySet().equals(Set.of("user_field", "group_field")),
                    "Assignment requires user_field and group_field");
            var userRule = object(rules.getOrDefault(assignment.get("user_field"), map()));
            var groupRule = object(rules.getOrDefault(assignment.get("group_field"), map()));
            require(
                    "account".equals(userRule.get("type")),
                    "Assignment user field must have account type");
            require(
                    "reference".equals(groupRule.get("type"))
                            && "sys_user_group".equals(groupRule.get("target")),
                    "Assignment group field must reference sys_user_group");
        }
    }

    private static void label(Object value) {
        require(value instanceof String, "Labels must be strings");
        String text = (String) value;
        require(!text.isBlank() && text.length() <= 120, "Labels must contain 1–120 characters");
    }

    private static void choices(Object value, Set<String> allowed) {
        require(value instanceof List<?>, "Choices must be a list");
        var choices = (List<?>) value;
        require(choices.size() <= 100, "At most 100 choices are supported");
        require(new HashSet<>(choices).size() == choices.size(), "Duplicate choice");
        for (Object choice : choices) {
            label(choice);
            if (allowed != null) require(allowed.contains(choice), "Unknown choice");
        }
    }
}
