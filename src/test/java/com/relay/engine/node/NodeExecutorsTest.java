package com.relay.engine.node;

import static com.relay.TestSupport.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relay.config.RelayProperties;
import com.relay.engine.EgressPolicy;
import com.relay.engine.ExternalHttpClient;
import com.relay.engine.NodeExecutionException;
import com.relay.support.FakeWorld;
import com.relay.support.StubServer;
import com.relay.support.StubServer.Reply;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The side-effect nodes and the outbound HTTP layer, against a local fake world and a scriptable stub. */
class NodeExecutorsTest {

    private FakeWorld world;
    private StubServer stub;
    private RelayProperties props;
    private ExternalHttpClient http;
    private WorldClient worldClient;

    @BeforeEach
    void start() throws Exception {
        world = FakeWorld.start();
        stub = new StubServer();
        props = new RelayProperties();
        props.getMockWorld().setUrl(world.url());
        props.getMockWorld().setTimeoutMs(1000);
        props.getHttp().setTimeoutMs(1000);
        props.getHttp().setMaxResponseBytes(2000);
        http = new ExternalHttpClient(props);
        worldClient = new WorldClient(http, props, MAPPER);
    }

    @AfterEach
    void stop() {
        world.close();
        stub.close();
    }

    private static ObjectNode params(Object... kv) {
        ObjectNode n = MAPPER.createObjectNode();
        for (int i = 0; i < kv.length; i += 2) {
            Object v = kv[i + 1];
            if (v instanceof Number num) n.put((String) kv[i], num.doubleValue());
            else if (v instanceof ObjectNode o) n.set((String) kv[i], o);
            else n.put((String) kv[i], v == null ? null : v.toString());
        }
        return n;
    }

    private static NodeExecutor.NodeInvocation inv(ObjectNode p, String key) {
        return new NodeExecutor.NodeInvocation("run_1", "n", p, key, 1);
    }

    // ------------------------------------------------------------------ notify

    @Test
    void notifySendsEmailAndChatWithTheIdempotencyKey() {
        NotifyExecutor notify = new NotifyExecutor(worldClient);
        assertThat(notify.type()).isEqualTo("notify");
        var email = notify.execute(inv(params("channel", "email", "to", "a@b.example", "subject", "S", "message", "hello"), "run_1:n")).output();
        assertThat(email.get("delivered").asBoolean()).isTrue();
        assertThat(email.get("notification_id").asText()).startsWith("ntf_");
        notify.execute(inv(params("channel", "chat", "to", "#ops", "message", "hi"), "run_1:m"));
        assertThat(world.executed("email.send")).isEqualTo(1);
        assertThat(world.executed("chat.message")).isEqualTo(1);
        assertThat(world.ledger().get(0).idempotencyKey()).isEqualTo("run_1:n");
    }

    @Test
    void notifyRefusesBlankRecipientsMessagesAndUnknownChannels() {
        NotifyExecutor notify = new NotifyExecutor(worldClient);
        assertThatThrownBy(() -> notify.execute(inv(params("channel", "chat", "to", "  ", "message", "hi"), "k")))
                .isInstanceOf(NodeExecutionException.class).hasMessageContaining("recipient");
        assertThatThrownBy(() -> notify.execute(inv(params("channel", "chat", "to", "#x", "message", ""), "k")))
                .isInstanceOf(NodeExecutionException.class).hasMessageContaining("message");
        assertThatThrownBy(() -> notify.execute(inv(params("channel", "sms", "to", "#x", "message", "m"), "k")))
                .isInstanceOf(NodeExecutionException.class).hasMessageContaining("Unsupported notify channel");
        assertThat(world.ledger()).isEmpty();
    }

    // ------------------------------------------------------------------ order_action

    @Test
    void orderActionRefundsReplacesAndValidatesItsInputs() {
        OrderActionExecutor orders = new OrderActionExecutor(worldClient, props);
        assertThat(orders.type()).isEqualTo("order_action");
        var refund = orders.execute(inv(params("action", "refund", "order_id", "ord_2001"), "run_1:r")).output();
        assertThat(refund.get("status").asText()).isEqualTo("refunded");
        var replacement = orders.execute(inv(params("action", "replacement", "order_id", "ord_2003"), "run_1:p")).output();
        assertThat(replacement.get("status").asText()).isEqualTo("replacement_created");

        assertThatThrownBy(() -> orders.execute(inv(params("action", "refund", "order_id", "../etc"), "k")))
                .isInstanceOf(NodeExecutionException.class).hasMessageContaining("unsupported characters");
        assertThatThrownBy(() -> orders.execute(inv(params("action", "explode", "order_id", "ord_2002"), "k")))
                .isInstanceOf(NodeExecutionException.class).hasMessageContaining("Unsupported order action");
        for (Object bad : new Object[] {-5, 0, 10_000.01, "ten"}) {
            assertThatThrownBy(() -> orders.execute(inv(params("action", "refund", "order_id", "ord_2002", "amount_usd", bad), "k")))
                    .as("amount %s", bad).isInstanceOf(NodeExecutionException.class)
                    .satisfies(e -> assertThat(((NodeExecutionException) e).isRetryable()).isFalse());
        }
        assertThat(world.executed("order.refund")).isEqualTo(1);
    }

    @Test
    void aRefundTheWorldRejectsSurfacesAsAPermanentFailure() {
        OrderActionExecutor orders = new OrderActionExecutor(worldClient, props);
        assertThatThrownBy(() -> orders.execute(inv(params("action", "refund", "order_id", "ord_2002", "amount_usd", 9999), "k")))
                .isInstanceOfSatisfying(NodeExecutionException.class, e -> {
                    assertThat(e.code()).isEqualTo("dependency_rejected");
                    assertThat(e.isRetryable()).isFalse();
                    assertThat(e.getMessage()).contains("Refund exceeds order total");
                });
    }

    // ------------------------------------------------------------------ world client

    @Test
    void anEmptyTwoHundredAndFourResponseIsAnEmptyObjectNotNull() {
        stub.respond(s -> new Reply(204, "", 0));
        props.getMockWorld().setUrl(stub.url());
        assertThat(new WorldClient(http, props, MAPPER).post("/x", MAPPER.createObjectNode(), "k").isEmpty()).isTrue();
        stub.respond(s -> Reply.ok(""));
        assertThat(new WorldClient(http, props, MAPPER).post("/x", MAPPER.createObjectNode(), null).isObject()).isTrue();
    }

    @Test
    void theWorldClientClassifiesFailures() {
        props.getMockWorld().setUrl(stub.url() + "/");
        WorldClient client = new WorldClient(http, props, MAPPER);
        stub.respond(s -> Reply.ok("not json"));
        assertThatThrownBy(() -> client.post("/x", MAPPER.createObjectNode(), "k"))
                .isInstanceOfSatisfying(NodeExecutionException.class, e -> assertThat(e.code()).isEqualTo("dependency_bad_response"));
        stub.respond(s -> Reply.status(503, "{\"error\":{\"message\":\"down\"}}"));
        assertThatThrownBy(() -> client.post("/x", MAPPER.createObjectNode(), "k"))
                .isInstanceOfSatisfying(NodeExecutionException.class, e -> {
                    assertThat(e.isRetryable()).isTrue();
                    assertThat(e.getMessage()).contains("down");
                });
        stub.respond(s -> Reply.status(500, "<html>" + "x".repeat(400) + "</html>"));
        assertThatThrownBy(() -> client.post("/x", MAPPER.createObjectNode(), "k")).isInstanceOf(NodeExecutionException.class);
        stub.respond(s -> Reply.status(404, "{\"error\":{\"message\":\"" + "y".repeat(500) + "\"}}"));
        assertThatThrownBy(() -> client.post("/x", MAPPER.createObjectNode(), "k"))
                .isInstanceOfSatisfying(NodeExecutionException.class, e -> {
                    assertThat(e.isRetryable()).isFalse();
                    assertThat(e.getMessage().length()).isLessThan(500);
                });
    }

    // ------------------------------------------------------------------ http_request

    private HttpRequestExecutor httpNode(String... allowed) {
        props.getHttp().setAllowedHosts(List.of(allowed));
        return new HttpRequestExecutor(http, new EgressPolicy(props), MAPPER, props);
    }

    @Test
    void httpRequestGetsPostsAndParsesJsonOrTextBodies() {
        HttpRequestExecutor node = httpNode("127.0.0.1");
        assertThat(node.type()).isEqualTo("http_request");
        stub.respond(s -> Reply.ok("{\"ok\":true}"));
        var get = node.execute(inv(params("method", "get", "url", stub.url() + "/a"), null)).output();
        assertThat(get.get("status").asInt()).isEqualTo(200);
        assertThat(get.get("body").get("ok").asBoolean()).isTrue();

        stub.respond(s -> Reply.ok("plain text"));
        assertThat(node.execute(inv(params("method", "GET", "url", stub.url() + "/b"), null)).output().get("body").asText()).isEqualTo("plain text");

        ObjectNode body = MAPPER.createObjectNode().put("q", 1);
        ObjectNode headers = MAPPER.createObjectNode().put("X-Trace", "t1").put("Idempotency-Key", "attacker-supplied");
        node.execute(inv(params("method", "POST", "url", stub.url() + "/c", "headers", headers, "body", body), "run_1:n"));
        StubServer.Seen seen = stub.requests().get(2);
        assertThat(seen.method()).isEqualTo("POST");
        assertThat(seen.headers().getFirst("Idempotency-Key")).as("the engine's key wins over a configured header").isEqualTo("run_1:n");
        assertThat(seen.headers().getFirst("Content-Type")).isEqualTo("application/json");
        assertThat(seen.headers().getFirst("X-Trace")).isEqualTo("t1");
        assertThat(seen.body()).isEqualTo("{\"q\":1}");
    }

    @Test
    void aCallerSuppliedContentTypeIsRespectedWhateverItsCase() {
        HttpRequestExecutor node = httpNode("127.0.0.1");
        ObjectNode headers = MAPPER.createObjectNode().put("content-type", "application/vnd.api+json");
        node.execute(inv(params("method", "POST", "url", stub.url() + "/c", "headers", headers, "body", MAPPER.createObjectNode()), "k"));
        assertThat(stub.requests().get(0).headers().get("Content-Type")).containsExactly("application/vnd.api+json");
    }

    @Test
    void aMutatingHttpRequestWithoutAnIdempotencyKeyIsRefused() {
        HttpRequestExecutor node = httpNode("127.0.0.1");
        for (String key : new String[] {null, " "}) {
            assertThatThrownBy(() -> node.execute(inv(params("method", "DELETE", "url", stub.url() + "/x"), key)))
                    .isInstanceOfSatisfying(NodeExecutionException.class, e -> assertThat(e.code()).isEqualTo("idempotency_key_missing"));
        }
        assertThat(stub.requests()).isEmpty();
    }

    @Test
    void httpRequestClassifiesStatusesAndEnforcesTheEgressPolicy() {
        HttpRequestExecutor node = httpNode("127.0.0.1");
        stub.respond(s -> Reply.status(502, "{}"));
        assertThatThrownBy(() -> node.execute(inv(params("method", "GET", "url", stub.url() + "/x"), null)))
                .isInstanceOfSatisfying(NodeExecutionException.class, e -> assertThat(e.isRetryable()).isTrue());
        stub.respond(s -> Reply.status(404, "{}"));
        assertThatThrownBy(() -> node.execute(inv(params("method", "GET", "url", stub.url() + "/x"), null)))
                .isInstanceOfSatisfying(NodeExecutionException.class, e -> assertThat(e.code()).isEqualTo("dependency_rejected"));
        assertThatThrownBy(() -> node.execute(inv(params("method", "GET", "url", "http://evil.example/x"), null)))
                .isInstanceOfSatisfying(NodeExecutionException.class, e -> assertThat(e.code()).isEqualTo("egress_blocked"));
    }

    // ------------------------------------------------------------------ external http client + egress

    @Test
    void anOversizedResponseIsRefusedWithoutBufferingItAll() {
        stub.respond(s -> Reply.ok("z".repeat(50_000)));
        assertThatThrownBy(() -> http.call("GET", java.net.URI.create(stub.url() + "/big"), Map.of(), null, Duration.ofSeconds(2)))
                .isInstanceOfSatisfying(NodeExecutionException.class, e -> assertThat(e.code()).isEqualTo("response_too_large"));
        stub.respond(s -> Reply.ok("{\"fits\":true}"));
        assertThat(http.call("GET", java.net.URI.create(stub.url() + "/small"), Map.of(), null, Duration.ofSeconds(2)).body()).contains("fits");
    }

    @Test
    void redirectsAreNeverFollowed() {
        stub.respond(s -> s.path().equals("/start") ? new Reply(302, "", 0) : Reply.ok("{\"followed\":true}"));
        var resp = http.call("GET", java.net.URI.create(stub.url() + "/start"), Map.of(), null, Duration.ofSeconds(2));
        assertThat(resp.status()).isEqualTo(302);
        assertThat(stub.requests()).hasSize(1);
    }

    @Test
    void slowAndUnreachableDependenciesFailTheStepAsRetryable() throws Exception {
        stub.respond(s -> Reply.slow(1500));
        assertThatThrownBy(() -> http.call("GET", java.net.URI.create(stub.url() + "/slow"), Map.of(), null, Duration.ofMillis(300)))
                .isInstanceOfSatisfying(NodeExecutionException.class, e -> {
                    assertThat(e.code()).isEqualTo("dependency_timeout");
                    assertThat(e.isRetryable()).isTrue();
                });
        String dead = stub.url();
        stub.close();
        assertThatThrownBy(() -> http.call("GET", java.net.URI.create(dead + "/x"), Map.of(), null, Duration.ofMillis(500)))
                .isInstanceOfSatisfying(NodeExecutionException.class, e -> assertThat(e.code()).isEqualTo("dependency_unavailable"));
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> http.call("GET", java.net.URI.create(dead + "/x"), Map.of(), null, Duration.ofMillis(500)))
                    .isInstanceOf(NodeExecutionException.class);
        } finally {
            Thread.interrupted();
        }
        assertThat(ExternalHttpClient.isTransientStatus(429)).isTrue();
        assertThat(ExternalHttpClient.isTransientStatus(404)).isFalse();
    }

    @Test
    void addressesThatAreNeverALegitimateTargetAreRefusedEvenWhenTheHostIsAllowed() {
        props.getHttp().setAllowedHosts(List.of("169.254.169.254", "0.0.0.0", "224.0.0.1", "127.0.0.1", "no-such-host.invalid"));
        EgressPolicy policy = new EgressPolicy(props);
        for (String host : List.of("169.254.169.254", "0.0.0.0", "224.0.0.1")) {
            assertThatThrownBy(() -> policy.check("http://" + host + "/x")).isInstanceOfSatisfying(NodeExecutionException.class,
                    e -> assertThat(e.code()).isEqualTo("egress_blocked"));
        }
        assertThat(policy.check("http://127.0.0.1:9210/x").getHost()).isEqualTo("127.0.0.1");
        assertThat(policy.check("http://no-such-host.invalid/x").getHost()).as("unresolvable: fails later as a dependency error").isNotNull();
        assertThatThrownBy(() -> policy.check("http://[bad")).isInstanceOf(NodeExecutionException.class);
        assertThatThrownBy(() -> policy.check("mailto:x@y.z")).isInstanceOf(NodeExecutionException.class);
        assertThat(policy.allowedHosts()).contains("127.0.0.1");
    }
}
