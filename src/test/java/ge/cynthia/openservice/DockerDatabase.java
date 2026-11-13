package ge.cynthia.openservice;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

final class DockerDatabase {
    private static String url;

    static synchronized String url() {
        if (url != null) return url;
        String name = "openservice-java-test-" + UUID.randomUUID().toString().replace("-", "");
        try {
            command(
                    "docker",
                    "run",
                    "--rm",
                    "--detach",
                    "--name",
                    name,
                    "-e",
                    "POSTGRES_USER=openservice",
                    "-e",
                    "POSTGRES_DB=openservice",
                    "-e",
                    "POSTGRES_PASSWORD=disposable-integration-password",
                    "-p",
                    "127.0.0.1::5432",
                    "postgres:17-alpine");
            Runtime.getRuntime()
                    .addShutdownHook(
                            new Thread(() -> {
                                try {
                                    command("docker", "stop", name);
                                } catch (Exception error) {
                                    System.err.println("Test database cleanup failed for " + name);
                                }
                            }));
            String address = command("docker", "port", name, "5432/tcp").strip();
            int port = Integer.parseInt(address.substring(address.lastIndexOf(':') + 1));
            boolean ready = false;
            for (int i = 0; i < 60; i++) {
                try {
                    command(
                            "docker",
                            "exec",
                            name,
                            "pg_isready",
                            "-U",
                            "openservice",
                            "-d",
                            "openservice");
                    ready = true;
                    break;
                } catch (Exception waiting) {
                    Thread.sleep(250);
                }
            }
            if (!ready)
                throw new IllegalStateException("Disposable PostgreSQL did not become ready");
            url =
                    "postgresql+psycopg://openservice:disposable-integration-password@127.0.0.1:"
                            + port
                            + "/openservice";
            return url;
        } catch (Exception error) {
            throw new IllegalStateException(
                    "Docker is required for the PostgreSQL integration tests", error);
        }
    }

    private static String command(String... args) throws Exception {
        Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Docker command timed out");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.exitValue() != 0) throw new IllegalStateException(output);
        return output;
    }
}
