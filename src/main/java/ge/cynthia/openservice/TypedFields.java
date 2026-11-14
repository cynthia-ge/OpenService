package ge.cynthia.openservice;

import static ge.cynthia.openservice.Values.*;

import java.sql.SQLException;
import java.time.*;
import java.util.*;

/** String wire values with explicit server-side types; no executable schema expressions. */
final class TypedFields {
    private TypedFields() {}

    static final Set<String> TYPES =
            Set.of(
                    "string",
                    "integer",
                    "decimal",
                    "boolean",
                    "date",
                    "datetime",
                    "choice",
                    "reference",
                    "account");

    static Map<String, Object> rules(Map<String, Object> definition) {
        return object(definition.getOrDefault("field_rules", map()));
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new Failure(400, message);
    }

    static void check(Store.Db db, Map<String, Object> definition, Map<String, Object> data)
            throws SQLException {
        for (var entry : rules(definition).entrySet()) {
            String field = entry.getKey();
            var rule = object(entry.getValue());
            Object raw = data.getOrDefault(field, "");
            require(raw instanceof String, field + " must be a string wire value");
            String value = (String) raw;
            require(
                    value.codePointCount(0, value.length())
                            <= (int) rule.getOrDefault("max_length", 65536),
                    field + " exceeds its maximum length");
            if (value.isBlank()) {
                require(!Boolean.TRUE.equals(rule.get("required")), field + " is required");
                continue;
            }
            String type = (String) rule.get("type");
            try {
                switch (type) {
                    case "integer" -> Long.parseLong(value);
                    case "decimal" ->
                            require(
                                    value.matches("-?[0-9]+(?:\\.[0-9]+)?"),
                                    field + " must be a decimal");
                    case "boolean" ->
                            require(
                                    Set.of("true", "false").contains(value),
                                    field + " must be true or false");
                    case "date" -> LocalDate.parse(value);
                    case "datetime" -> Instant.parse(value);
                    case "choice" ->
                            require(
                                    ((List<?>) rule.get("choices")).contains(value),
                                    field + " has an unsupported choice");
                    case "reference" -> {
                        var parameters = new ArrayList<Object>(List.of(rule.get("target"), value));
                        require(
                                db.one(
                                                "SELECT sys_id FROM os_record WHERE \"table\"=? AND"
                                                    + " sys_id=? AND "
                                                        + RecordAccess.filter(
                                                                db, "os_record.sys_id", parameters),
                                                parameters.toArray())
                                        != null,
                                field + " requires an accessible record in its target table");
                    }
                    case "account" ->
                            require(
                                    db.one(
                                                    "SELECT user_id FROM os_user WHERE user_id=?"
                                                        + " AND active=1",
                                                    value)
                                            != null,
                                    field + " requires an active account ID");
                    default -> {
                        /* string */
                    }
                }
            } catch (NumberFormatException | java.time.format.DateTimeParseException invalid) {
                throw new Failure(400, field + " has an invalid " + type + " value");
            }
        }
        var assignment = object(definition.getOrDefault("assignment", map()));
        if (!assignment.isEmpty()) {
            String user = data.getOrDefault(assignment.get("user_field"), "").toString();
            String group = data.getOrDefault(assignment.get("group_field"), "").toString();
            if (!user.isEmpty()) {
                require(
                        db.one(
                                        "SELECT user_id FROM os_user WHERE user_id=? AND active=1"
                                            + " AND role IN ('agent','admin')",
                                        user)
                                != null,
                        "Assignment requires an active agent or administrator");
                require(
                        db.one(
                                        "SELECT user_id FROM os_group_member WHERE user_id=? AND"
                                            + " group_id=?",
                                        user,
                                        group)
                                != null,
                        "Assignee must belong to the selected assignment group");
            }
        }
        var policy =
                object(
                        object(definition.getOrDefault("transition_rules", map()))
                                .getOrDefault(data.getOrDefault("state", ""), map()));
        for (Object field : (List<?>) policy.getOrDefault("required_fields", List.of()))
            require(
                    !data.getOrDefault(field, "").toString().isBlank(),
                    field + " is required in this state");
    }

    static void transition(Map<String, Object> definition, Object state, String role) {
        var rule =
                object(
                        object(definition.getOrDefault("transition_rules", map()))
                                .getOrDefault(state, map()));
        if (rule.containsKey("roles") && !((List<?>) rule.get("roles")).contains(role))
            throw new Failure(403, "Your role cannot enter this state");
    }

    static void deleting(Store.Db db, Map<String, Object> item) throws SQLException {
        for (var schema : db.all("SELECT name,definition FROM os_schema")) {
            for (var field : rules(object(schema.get("definition"))).entrySet()) {
                var rule = object(field.getValue());
                if (!"reference".equals(rule.get("type"))
                        || !item.get("table").equals(rule.get("target"))) continue;
                for (var other :
                        db.all(
                                "SELECT sys_id,data FROM os_record WHERE \"table\"=? AND sys_id<>?",
                                schema.get("name"),
                                item.get("sys_id")))
                    if (item.get("sys_id").equals(object(other.get("data")).get(field.getKey())))
                        throw new Failure(
                                409, "Record is referenced; remove dependent relationships first");
            }
        }
    }
}
