package com.relay.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** A tiny scriptable HTTP server for unit tests of the outbound clients (status, body, delay, recorded requests). */
public final class StubServer implements AutoCloseable {

    public record Reply(int status, String body, long delayMs) {
        public static Reply ok(String body) { return new Reply(200, body, 0); }
        public static Reply status(int status, String body) { return new Reply(status, body, 0); }
        public static Reply slow(long delayMs) { return new Reply(200, "{}", delayMs); }
    }

    public record Seen(String method, String path, String body, com.sun.net.httpserver.Headers headers) {}

    private final HttpServer server;
    private final List<Seen> seen = new ArrayList<>();
    private volatile Function<Seen, Reply> handler = s -> Reply.ok("{}");

    public StubServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
    }

    public StubServer respond(Function<Seen, Reply> handler) {
        this.handler = handler;
        return this;
    }

    public String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    public synchronized List<Seen> requests() { return List.copyOf(seen); }

    private void handle(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Seen s = new Seen(ex.getRequestMethod(), ex.getRequestURI().getPath(), body, ex.getRequestHeaders());
        synchronized (this) { seen.add(s); }
        Reply r = handler.apply(s);
        try {
            if (r.delayMs() > 0) Thread.sleep(r.delayMs());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        byte[] bytes = r.body().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        if (r.status() == 204) {
            ex.sendResponseHeaders(204, -1);
        } else {
            ex.sendResponseHeaders(r.status(), bytes.length);
            ex.getResponseBody().write(bytes);
        }
        ex.close();
    }

    @Override
    public void close() { server.stop(0); }
}
