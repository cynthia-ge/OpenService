package ge.cynthia.openservice;

import static ge.cynthia.openservice.Values.*;

import java.sql.SQLException;
import java.util.*;

/** Current caller's mutation authorization; validation and workflow rules remain separate. */
final class Permissions {
    private Permissions() {}

    static Map<String, Object> inspect(Store.Db db, String table, String id) throws SQLException {
        Service.record(db, table, id);
        var definition = Service.schema(db, table);
        var update = decision(definition, "update", db.viewerRole);
        var fields = new LinkedHashMap<String, Object>();
        for (String field : object(definition.get("fields")).keySet()) {
            var rule = object(TypedFields.rules(definition).getOrDefault(field, map()));
            String reason = update.get("reason").toString();
            if (Boolean.TRUE.equals(update.get("allowed"))
                    && rule.containsKey("write_roles")
                    && !((List<?>) rule.get("write_roles")).contains(db.viewerRole))
                reason = "field_roles";
            fields.put(field, result(reason));
        }
        return map(
                "record_id",
                id,
                "role",
                db.viewerRole,
                "update",
                update,
                "delete",
                decision(definition, "delete", db.viewerRole),
                "fields",
                fields);
    }

    private static Map<String, Object> decision(
            Map<String, Object> definition, String operation, String role) {
        if (role.equals("reader") || operation.equals("delete") && !role.equals("admin"))
            return result("account_role");
        return result(
                RecordPolicy.allows(definition, operation, role) ? "allowed" : "record_roles");
    }

    private static Map<String, Object> result(String reason) {
        return map("allowed", reason.equals("allowed"), "reason", reason);
    }
}
