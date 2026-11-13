package com.relay.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An in-JVM stand-in for the capstone's mock world with the same contract: side-effect endpoints, an
 * idempotency-aware ledger, business rules and live failure injection. Lets integration tests assert
 * exactly-once behaviour without Python.
 */
public final class FakeWorld implements AutoCloseable {

    public record Entry(int seq, String action, String idempotencyKey, boolean replayed, int status, JsonNode payload) {}

    private static final ObjectMapper M = new ObjectMapper();

    private final HttpServer server;
    private final List<Entry> ledger = new CopyOnWriteArrayList<>();
    private final Map<String, int[]> idempotentStatus = new ConcurrentHashMap<>();
    private final Map<String, String> idempotentBody = new ConcurrentHashMap<>();
    private final Map<String, ObjectNode> orders = new ConcurrentHashMap<>();
    private final AtomicInteger seq = new AtomicInteger();
    private final AtomicInteger failuresLeft = new AtomicInteger();
    private volatile int failStatus = 503;
    private volatile boolean down;
    private volatile long latencyMs;

    public static FakeWorld start() {
        try {
            return new FakeWorld();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private FakeWorld() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", this::handle);
        server.start();
        resetOrders();
    }

    public String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    public void reset() {
        ledger.clear();
        idempotentStatus.clear();
        idempotentBody.clear();
        seq.set(0);
        failuresLeft.set(0);
        down = false;
        latencyMs = 0;
        resetOrders();
    }

    private void resetOrders() {
        orders.clear();
        orders.put("ord_2001", order("ord_2001", "processing", 89.0));
        orders.put("ord_2002", order("ord_2002", "delivered", 45.5));
        orders.put("ord_2003", order("ord_2003", "processing", 210.0));
    }

    private static ObjectNode order(String id, String status, double amount) {
        ObjectNode o = M.createObjectNode();
        o.put("order_id", id).put("status", status).put("amount_usd", amount);
        return o;
    }

    /** The next {@code count} side-effect calls answer with {@code status} instead of executing. */
    public void failNext(int count, int status) {
        failStatus = status;
        failuresLeft.set(count);
    }

    public void setDown(boolean down) { this.down = down; }
    public void setLatencyMs(long ms) { this.latencyMs = ms; }
    public List<Entry> ledger() { return List.copyOf(ledger); }

    public long executed(String action) {
        return ledger.stream().filter(e -> e.action().equals(action) && !e.replayed() && e.status() < 300).count();
    }

    public long replayed(String action) {
        return ledger.stream().filter(e -> e.action().equals(action) && e.replayed()).count();
    }

    public String orderStatus(String id) { return orders.get(id).get("status").asText(); }

    // ---------------------------------------------------------------- HTTP

    private void handle(HttpExchange ex) throws IOException {
        try {
            if (latencyMs > 0) Thread.sleep(latencyMs);
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if (method.equals("GET") && path.startsWith("/orders/")) {
                ObjectNode o = orders.get(path.split("/")[2]);
                if (o == null) respond(ex, 404, "{\"error\":{\"message\":\"Unknown order\"}}");
                else respond(ex, 200, o.toString());
                return;
            }
            if (method.equals("GET") && path.equals("/health")) {
                respond(ex, 200, "{\"status\":\"ok\"}");
                return;
            }
            byte[] raw = ex.getRequestBody().readAllBytes();
            JsonNode payload = raw.length == 0 ? M.createObjectNode() : M.readTree(raw);
            if (down) {
                respond(ex, 503, "{\"error\":{\"message\":\"world is down\"}}");
                return;
            }
            if (failuresLeft.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
                respond(ex, failStatus, "{\"error\":{\"message\":\"injected failure\"}}");
                return;
            }
            String key = ex.getRequestHeaders().getFirst("Idempotency-Key");
            String action = actionFor(path);
            if (action == null) {
                respond(ex, 404, "{\"error\":{\"message\":\"no such route\"}}");
                return;
            }
            synchronized (this) {
                if (key != null && idempotentBody.containsKey(key)) {
                    ledger.add(new Entry(seq.incrementAndGet(), action, key, true, idempotentStatus.get(key)[0], payload));
                    respond(ex, idempotentStatus.get(key)[0], idempotentBody.get(key));
                    return;
                }
                int[] status = new int[1];
                String body = execute(action, path, payload, status);
                ledger.add(new Entry(seq.incrementAndGet(), action, key, false, status[0], payload));
                if (key != null && status[0] < 300) {
                    idempotentStatus.put(key, new int[] {status[0]});
                    idempotentBody.put(key, body);
                }
                respond(ex, status[0], body);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            respond(ex, 500, "{\"error\":{\"message\":\"fake world error\"}}");
        }
    }

    private static String actionFor(String path) {
        if (path.equals("/email/send")) return "email.send";
        if (path.equals("/chat/message")) return "chat.message";
        if (path.equals("/shipments")) return "shipment.create";
        if (path.startsWith("/orders/") && path.endsWith("/refund")) return "order.refund";
        if (path.startsWith("/orders/") && path.endsWith("/replacement")) return "order.replacement";
        return null;
    }

    private String execute(String action, String path, JsonNode payload, int[] status) {
        switch (action) {
            case "email.send", "chat.message" -> {
                status[0] = 200;
                return "{\"delivered\":true,\"notification_id\":\"ntf_" + UUID.randomUUID().toString().substring(0, 8) + "\"}";
            }
            case "shipment.create" -> {
                if (!orders.containsKey(payload.path("order_id").asText())) {
                    status[0] = 404;
                    return "{\"error\":{\"message\":\"Unknown order\"}}";
                }
                status[0] = 201;
                return "{\"shipment_id\":\"shp_" + UUID.randomUUID().toString().substring(0, 8) + "\",\"status\":\"created\"}";
            }
            case "order.refund" -> {
                ObjectNode o = orders.get(path.split("/")[2]);
                if (o == null) {
                    status[0] = 404;
                    return "{\"error\":{\"message\":\"Unknown order\"}}";
                }
                if (o.get("status").asText().equals("refunded")) {
                    status[0] = 409;
                    return "{\"error\":{\"message\":\"already refunded\"}}";
                }
                double amount = payload.has("amount_usd") ? payload.get("amount_usd").asDouble() : o.get("amount_usd").asDouble();
                if (amount > o.get("amount_usd").asDouble()) {
                    status[0] = 400;
                    return "{\"error\":{\"message\":\"Refund exceeds order total\"}}";
                }
                o.put("status", "refunded");
                status[0] = 200;
                return "{\"status\":\"refunded\",\"reference_id\":\"ref_" + UUID.randomUUID().toString().substring(0, 8) + "\"}";
            }
            default -> {
                status[0] = 201;
                return "{\"status\":\"replacement_created\",\"reference_id\":\"rpl_" + UUID.randomUUID().toString().substring(0, 8) + "\"}";
            }
        }
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    @Override
    public void close() { server.stop(0); }
}
