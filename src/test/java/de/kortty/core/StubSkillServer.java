package de.kortty.core;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Loopback HTTP server for the external AI-skill client tests: canned replies by path and query. */
final class StubSkillServer implements AutoCloseable {

    record Reply(int status, String body, Map<String, String> headers) {
        static Reply json(String body) {
            return new Reply(200, body, Map.of("Content-Type", "application/json"));
        }
    }

    final List<String> requests = new CopyOnWriteArrayList<>();
    final List<String> authorizations = new CopyOnWriteArrayList<>();
    private final Map<String, Reply> replies = new ConcurrentHashMap<>();
    private final HttpServer server;

    StubSkillServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String target = exchange.getRequestURI().getRawPath()
                + (exchange.getRequestURI().getRawQuery() != null ? "?" + exchange.getRequestURI().getRawQuery() : "");
            requests.add(target);
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            authorizations.add(authorization != null ? authorization : "");
            Reply reply = replies.getOrDefault(target, new Reply(404, "{\"message\":\"Not Found\"}", Map.of()));
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            reply.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
            exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    StubSkillServer reply(String target, Reply reply) {
        replies.put(target, reply);
        return this;
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
