package ge.cynthia.openservice;

import static ge.cynthia.openservice.Values.*;

import com.sun.net.httpserver.HttpServer;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Synthetic source protocol fixture; never a claim of vendor-instance conformance. */
final class MigrationSource {
    static final Map<String, List<Map<String, Object>>> tables = new ConcurrentHashMap<>();
    static final Map<String, byte[]> files = new ConcurrentHashMap<>();
    static volatile String raw;
    static volatile int status = 200;
    static volatile CountDownLatch entered, release;
    static final HttpServer server = start();
    static final String TOKEN = "synthetic-source-token";
    static final List<String> queries = new CopyOnWriteArrayList<>();

    static String origin() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    static HttpServer start() {
        try {
            var result = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            result.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            result.createContext(
                    "/",
                    exchange -> {
                        var waiting = release;
                        if (waiting != null) {
                            entered.countDown();
                            try {
                                waiting.await(10, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }
                        int code = status;
                        byte[] body;
                        if (!Objects.equals(
                                "Bearer " + TOKEN,
                                exchange.getRequestHeaders().getFirst("Authorization"))) {
                            code = 401;
                            body =
                                    "secret-source-error-must-not-leak"
                                            .getBytes(StandardCharsets.UTF_8);
                        } else if (raw != null) body = raw.getBytes(StandardCharsets.UTF_8);
                        else if (exchange.getRequestURI()
                                .getPath()
                                .startsWith("/api/now/attachment/"))
                            body =
                                    files.getOrDefault(
                                            exchange.getRequestURI().getPath().split("/")[4],
                                            new byte[0]);
                        else {
                            String table =
                                    exchange.getRequestURI()
                                            .getPath()
                                            .substring("/api/now/table/".length());
                            String query =
                                    URLDecoder.decode(
                                            exchange.getRequestURI().getRawQuery(),
                                            StandardCharsets.UTF_8);
                            queries.add(query);
                            var matcher =
                                    java.util.regex.Pattern.compile("sys_id>([0-9a-f]{32})")
                                            .matcher(query);
                            String cursor = matcher.find() ? matcher.group(1) : "";
                            var rows =
                                    tables.getOrDefault(table, List.of()).stream()
                                            .filter(row ->
                                                    row.get("sys_id").toString().compareTo(cursor)
                                                            > 0)
                                            .sorted(
                                                    Comparator.comparing(
                                                            row -> row.get("sys_id").toString()))
                                            .limit(100)
                                            .toList();
                            body = JSON.writeValueAsBytes(map("result", rows));
                        }
                        exchange.getResponseHeaders().set("Location", origin() + "/redirected");
                        exchange.sendResponseHeaders(code, body.length);
                        try (var out = exchange.getResponseBody()) {
                            out.write(body);
                        }
                    });
            result.start();
            return result;
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static String key(int value) {
        return String.format(Locale.ROOT, "%032x", value);
    }

    static void reset() {
        tables.clear();
        files.clear();
        queries.clear();
        raw = null;
        status = 200;
        entered = null;
        release = null;
    }
}
