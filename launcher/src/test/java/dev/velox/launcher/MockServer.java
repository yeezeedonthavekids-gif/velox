package dev.velox.launcher;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/** In-process HTTP server standing in for Mojang, Fabric, Modrinth and Microsoft in tests. */
public final class MockServer implements AutoCloseable {
    public record Reply(int status, byte[] body) {
        public static Reply ok(String s) { return new Reply(200, s.getBytes(StandardCharsets.UTF_8)); }
        public static Reply ok(byte[] b) { return new Reply(200, b); }
        public static Reply status(int code, String s) { return new Reply(code, s.getBytes(StandardCharsets.UTF_8)); }
    }

    public record Request(String method, String path, String query, String body, Map<String, List<String>> headers) {}

    private final HttpServer server;
    private final Map<String, Function<Request, Reply>> routes = new ConcurrentHashMap<>();
    public final List<Request> requests = new CopyOnWriteArrayList<>();
    public final AtomicInteger hits = new AtomicInteger();

    public MockServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    public String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public MockServer route(String path, Function<Request, Reply> h) {
        routes.put(path, h);
        return this;
    }

    public MockServer json(String path, String json) {
        return route(path, r -> Reply.ok(json));
    }

    public MockServer bytes(String path, byte[] data) {
        return route(path, r -> Reply.ok(data));
    }

    private void handle(HttpExchange ex) throws IOException {
        hits.incrementAndGet();
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Request req = new Request(ex.getRequestMethod(), ex.getRequestURI().getPath(), ex.getRequestURI().getRawQuery(), body,
                Map.copyOf(ex.getRequestHeaders()));
        requests.add(req);
        Function<Request, Reply> h = routes.get(req.path());
        Reply reply = h == null ? Reply.status(404, "not found: " + req.path()) : h.apply(req);
        ex.sendResponseHeaders(reply.status(), reply.body().length == 0 ? -1 : reply.body().length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(reply.body());
        }
    }

    public static String sha1(byte[] b) { return hash("SHA-1", b); }
    public static String sha512(byte[] b) { return hash("SHA-512", b); }

    static String hash(String alg, byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(alg).digest(b));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
