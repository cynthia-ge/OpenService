package ge.cynthia.openservice;

import static ge.cynthia.openservice.Values.*;

import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** Read-only source transport. Credentials are supplied per request and never persisted. */
final class ServiceNowSource {
    private ServiceNowSource() {}

    static final int PAGE = 100;

    static String origin(String value, String allowed) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new Failure(400, "Invalid source origin");
        }
        String canonical = uri.getScheme() + "://" + uri.getRawAuthority();
        if (!Set.of("https", "http").contains(Objects.toString(uri.getScheme(), ""))
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null
                || !Set.of("", "/").contains(uri.getPath()))
            throw new Failure(400, "Use an origin without credentials, path, query or fragment");
        boolean standard =
                "https".equals(uri.getScheme())
                        && uri.getPort() == -1
                        && uri.getHost().matches("[a-z0-9-]+\\.service-now\\.com");
        if (!standard && !Arrays.asList(allowed.split(",")).contains(canonical))
            throw new Failure(400, "Source origin is not allowed by this deployment");
        return canonical;
    }

    static byte[] get(String origin, String token, String path, int limit) {
        if (!token.matches("[A-Za-z0-9._~+/=-]{1,4096}"))
            throw new Failure(400, "Supply a source OAuth bearer token");
        var request =
                HttpRequest.newBuilder(URI.create(origin + path))
                        .timeout(Duration.ofSeconds(30))
                        .header("Authorization", "Bearer " + token)
                        .header("Accept", "application/json")
                        .GET()
                        .build();
        try (var client =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build()) {
            var response =
                    client.send(
                            request,
                            HttpResponse.BodyHandlers.limiting(
                                    HttpResponse.BodyHandlers.ofByteArray(), limit));
            if (response.statusCode() != 200)
                throw new Failure(
                        502,
                        "Source returned HTTP "
                                + response.statusCode()
                                + "; no checkpoint advanced");
            return response.body();
        } catch (IOException e) {
            throw new Failure(502, "Source connection failed; retry the saved checkpoint");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failure(502, "Source request interrupted; retry the saved checkpoint");
        }
    }

    static List<Map<String, Object>> page(
            String origin,
            String token,
            String table,
            String filter,
            String cursor,
            String fields) {
        int pageSize = table.equals("sys_attachment") ? 1 : PAGE;
        String query =
                (filter.isEmpty() ? "" : filter + "^")
                        + (cursor.isEmpty() ? "" : "sys_id>" + cursor + "^")
                        + "ORDERBYsys_id";
        String path =
                "/api/now/table/"
                        + table
                        + "?sysparm_limit="
                        + pageSize
                        + "&sysparm_display_value=false&sysparm_exclude_reference_link=true&sysparm_query="
                        + URLEncoder.encode(query, StandardCharsets.UTF_8)
                        + "&sysparm_fields="
                        + URLEncoder.encode(fields, StandardCharsets.UTF_8);
        Object value;
        try {
            value = JSON.readValue(get(origin, token, path, 6 * 1024 * 1024), Object.class);
        } catch (tools.jackson.core.JacksonException e) {
            throw new Failure(502, "Source returned invalid JSON");
        }
        if (!(value instanceof Map<?, ?> document))
            throw new Failure(502, "Source must return a result object");
        var result = document.get("result");
        if (!(result instanceof List<?> rows) || rows.size() > pageSize)
            throw new Failure(502, "Source must return a bounded result array");
        var output = new ArrayList<Map<String, Object>>();
        String previous = cursor;
        for (Object item : rows) {
            if (!(item instanceof Map<?, ?>)) throw new Failure(502, "Source rows must be objects");
            var row = object(item);
            if (!(row.get("sys_id") instanceof String id)
                    || !id.matches("[a-f0-9]{32}")
                    || id.compareTo(previous) <= 0)
                throw new Failure(502, "Source IDs must be unique and strictly ordered");
            previous = id;
            output.add(row);
        }
        return output;
    }

    static String text(Map<String, Object> row, String key) {
        Object value = row.getOrDefault(key, "");
        if (value instanceof Map<?, ?> reference) value = reference.get("value");
        return value == null ? "" : value.toString();
    }
}
