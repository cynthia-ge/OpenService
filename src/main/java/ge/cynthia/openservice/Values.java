package ge.cynthia.openservice;

import org.springframework.http.HttpStatus;

import tools.jackson.databind.json.JsonMapper;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

public final class Values {
    private Values() {}

    public static final JsonMapper JSON = JsonMapper.builder().build();

    public static Map<String, Object> map(Object... pairs) {
        var value = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) value.put((String) pairs[i], pairs[i + 1]);
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?>)) throw new Failure(400, "A JSON object is required");
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> objects(Object value) {
        return (List<Map<String, Object>>) value;
    }

    public static String now() {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
    }

    public static String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static class Failure extends RuntimeException {
        public final int status;

        public Failure(int status, String message) {
            super(message);
            this.status = status;
        }

        public String title() {
            return HttpStatus.valueOf(status).getReasonPhrase();
        }
    }
}
