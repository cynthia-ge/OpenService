package ge.cynthia.openservice;

import static ge.cynthia.openservice.Values.*;

import java.util.*;

/** Additional table constraints; existing account-level capabilities still apply. */
final class RecordPolicy {
    private RecordPolicy() {}

    static boolean allows(Map<String, Object> definition, String operation, String role) {
        var rules = object(definition.getOrDefault("record_roles", map()));
        return !rules.containsKey(operation) || ((List<?>) rules.get(operation)).contains(role);
    }

    static void require(Map<String, Object> definition, String operation, String role) {
        if (!allows(definition, operation, role))
            throw new Failure(403, "Your role cannot " + operation + " records in this table");
    }
}
