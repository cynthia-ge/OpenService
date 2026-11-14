package ge.cynthia.openservice;

import static ge.cynthia.openservice.Values.*;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.*;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;

class ConfigurationTest {
    @TestFactory
    Stream<DynamicTest> rejectsMalformedDefinitionsBeforeTheyReachStorage() {
        var cases = new LinkedHashMap<String, Consumer<Map<String, Object>>>();
        cases.put(
                "unknown record operation",
                d -> d.put("record_roles", map("execute", List.of("admin"))));
        cases.put("invalid record roles", d -> d.put("record_roles", map("create", "admin")));
        cases.put(
                "unknown record role", d -> d.put("record_roles", map("update", List.of("root"))));
        cases.put("unknown property", d -> d.put("script", "unsafe"));
        cases.put("missing version", d -> d.remove("version"));
        cases.put("negative version", d -> d.put("version", -1));
        cases.put("overflow version", d -> d.put("version", Integer.MAX_VALUE));
        cases.put("nonstring label", d -> d.put("label", 42));
        cases.put("blank label", d -> d.put("label", " "));
        cases.put("long label", d -> d.put("label", "x".repeat(121)));
        cases.put("missing prefix", d -> d.remove("prefix"));
        cases.put("invalid prefix", d -> d.put("prefix", "abc!"));
        cases.put("missing summary", d -> object(d.get("fields")).remove("short_description"));
        cases.put(
                "too many fields",
                d -> {
                    for (int i = 0; i < 101; i++) object(d.get("fields")).put("f" + i, "Field");
                });
        for (String name : List.of("bad'field", "sys_created_on", "number"))
            cases.put("reserved field " + name, d -> object(d.get("fields")).put(name, "Field"));
        cases.put(
                "too many states",
                d -> {
                    for (int i = 0; i < 51; i++) object(d.get("states")).put("s" + i, "State");
                });
        cases.put("states without field", d -> d.put("states", map("new", "New")));
        cases.put("field without states", d -> object(d.get("fields")).put("state", "State"));
        cases.put(
                "bad state id",
                d -> {
                    object(d.get("fields")).put("state", "State");
                    d.put("states", map("<bad>", "Bad"));
                });
        cases.put("unknown source", d -> d.put("flow", map("bad", List.of())));
        cases.put(
                "unknown target",
                d -> {
                    state(d);
                    d.put("flow", map("new", List.of("unknown")));
                });
        cases.put(
                "invalid flow list",
                d -> {
                    state(d);
                    d.put("flow", map("new", "bad"));
                });
        cases.put(
                "duplicate transition",
                d -> {
                    state(d);
                    d.put("flow", map("new", List.of("new", "new")));
                });
        cases.put(
                "unknown field rule",
                d -> object(d.get("field_rules")).put("bad", map("type", "string")));
        cases.put(
                "invalid field roles",
                d -> rules(d).put("quantity", map("type", "integer", "write_roles", "admin")));
        cases.put(
                "unknown field role",
                d ->
                        rules(d).put(
                                        "quantity",
                                        map("type", "integer", "write_roles", List.of("root"))));
        cases.put("unsupported type", d -> rules(d).put("quantity", map("type", "script")));
        cases.put("null type", d -> rules(d).put("quantity", map("type", null)));
        cases.put(
                "invalid required",
                d -> rules(d).put("quantity", map("type", "integer", "required", "yes")));
        cases.put(
                "invalid length",
                d -> rules(d).put("quantity", map("type", "integer", "max_length", "10")));
        cases.put(
                "zero length",
                d -> rules(d).put("quantity", map("type", "integer", "max_length", 0)));
        cases.put(
                "oversized length",
                d -> rules(d).put("quantity", map("type", "integer", "max_length", 65537)));
        cases.put(
                "reference without target",
                d -> rules(d).put("quantity", map("type", "reference")));
        cases.put(
                "extraneous type properties",
                d -> rules(d).put("quantity", map("type", "integer", "target", "incident")));
        cases.put(
                "too many choices",
                d ->
                        rules(d).put(
                                        "quantity",
                                        map(
                                                "type",
                                                "choice",
                                                "choices",
                                                Collections.nCopies(101, "a"))));
        cases.put("unknown policy state", d -> d.put("transition_rules", map("bad", map())));
        cases.put(
                "unknown policy key",
                d -> {
                    state(d);
                    d.put("transition_rules", map("new", map("script", "bad")));
                });
        cases.put(
                "unknown role",
                d -> {
                    state(d);
                    d.put("transition_rules", map("new", map("roles", List.of("root"))));
                });
        cases.put(
                "unknown required field",
                d -> {
                    state(d);
                    d.put(
                            "transition_rules",
                            map("new", map("required_fields", List.of("unknown"))));
                });
        cases.put(
                "invalid assignment fields",
                d -> d.put("assignment", map("user_field", "quantity")));
        cases.put(
                "invalid assignee type",
                d -> d.put("assignment", map("user_field", "quantity", "group_field", "category")));
        cases.put(
                "invalid group type",
                d -> {
                    rules(d).put("quantity", map("type", "account"));
                    d.put("assignment", map("user_field", "quantity", "group_field", "category"));
                });
        cases.put(
                "invalid group target",
                d -> {
                    rules(d).put("quantity", map("type", "account"));
                    rules(d).put("category", map("type", "reference", "target", "incident"));
                    d.put("assignment", map("user_field", "quantity", "group_field", "category"));
                });
        return cases.entrySet().stream()
                .map(entry ->
                        DynamicTest.dynamicTest(
                                entry.getKey(),
                                () -> {
                                    var definition = ConfigurationAcceptance.definition();
                                    entry.getValue().accept(definition);
                                    assertEquals(
                                            400,
                                            assertThrows(
                                                            Failure.class,
                                                            () ->
                                                                    Configuration.validate(
                                                                            definition))
                                                    .status);
                                }));
    }

    static Map<String, Object> rules(Map<String, Object> definition) {
        return object(definition.get("field_rules"));
    }

    static void state(Map<String, Object> definition) {
        object(definition.get("fields")).put("state", "State");
        definition.put("states", map("new", "New"));
    }

    @Test
    void valuesAreValidatedWithoutCoercingTypesOrIgnoringRequiredWhitespace() throws Exception {
        var definition = ConfigurationAcceptance.definition();
        assertEquals(
                400,
                assertThrows(
                                Failure.class,
                                () ->
                                        TypedFields.check(
                                                null, definition, map("short_description", 42)))
                        .status);
        assertEquals(
                400,
                assertThrows(
                                Failure.class,
                                () ->
                                        TypedFields.check(
                                                null, definition, map("short_description", "  ")))
                        .status);
        rules(definition).clear();
        state(definition);
        definition.put("transition_rules", map("new", map()));
        Configuration.validate(definition);
        definition.put(
                "transition_rules",
                map("new", map("required_fields", List.of("short_description"))));
        Configuration.validate(definition);
        TypedFields.transition(definition, "new", "agent");
        definition.put("transition_rules", map("new", map("roles", List.of("agent"))));
        Configuration.validate(definition);
        TypedFields.transition(definition, "new", "agent");
    }
}
