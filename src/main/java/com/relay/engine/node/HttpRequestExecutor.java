package com.relay.engine.node;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relay.config.RelayProperties;
import com.relay.engine.EgressPolicy;
import com.relay.engine.ExternalHttpClient;
import com.relay.engine.NodeExecutionException;
import com.relay.engine.TemplateResolver;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code http_request}: calls an allow-listed HTTP endpoint. Non-GET requests are side effects and always
 * carry the engine's {@code Idempotency-Key} (a caller-supplied header of that name is ignored).
 */
@Component
public class HttpRequestExecutor implements NodeExecutor {

    private final ExternalHttpClient http;
    private final EgressPolicy egress;
    private final ObjectMapper mapper;
    private final RelayProperties props;

    public HttpRequestExecutor(ExternalHttpClient http, EgressPolicy egress, ObjectMapper mapper, RelayProperties props) {
        this.http = http;
        this.egress = egress;
        this.mapper = mapper;
        this.props = props;
    }

    @Override
    public String type() { return "http_request"; }

    @Override
    public NodeOutcome execute(NodeInvocation inv) {
        ObjectNode p = inv.params();
        String method = p.path("method").asText().toUpperCase(Locale.ROOT);
        URI uri = egress.check(TemplateResolver.render(p.get("url")));

        Map<String, String> headers = new LinkedHashMap<>();
        JsonNode h = p.get("headers");
        if (h != null && h.isObject()) {
            h.fields().forEachRemaining(e -> {
                if (!"idempotency-key".equalsIgnoreCase(e.getKey())) {
                    headers.put(e.getKey(), TemplateResolver.render(e.getValue()));
                }
            });
        }
        boolean mutating = !"GET".equals(method);
        if (mutating) {
            if (inv.idempotencyKey() == null || inv.idempotencyKey().isBlank()) {
                throw new NodeExecutionException("idempotency_key_missing",
                        "A mutating http_request must carry an idempotency key; refusing to send it without one", false);
            }
            headers.put("Idempotency-Key", inv.idempotencyKey());
        }

        String body = null;
        if (mutating && p.has("body") && !p.get("body").isNull()) {
            try {
                body = mapper.writeValueAsString(p.get("body"));
            } catch (JsonProcessingException e) {
                throw new NodeExecutionException("invalid_body", "Could not serialize request body", false, e);
            }
        }

        ExternalHttpClient.Response resp = http.call(method, uri, headers, body,
                Duration.ofMillis(props.getHttp().getTimeoutMs()));
        int status = resp.status();
        if (status >= 200 && status < 300) {
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            out.put("status", status);
            out.set("body", parseBody(resp.body()));
            return NodeOutcome.of(out);
        }
        if (ExternalHttpClient.isTransientStatus(status)) {
            throw new NodeExecutionException("dependency_unavailable",
                    method + " " + uri.getPath() + " failed with HTTP " + status, true);
        }
        throw new NodeExecutionException("dependency_rejected",
                method + " " + uri.getPath() + " was rejected with HTTP " + status, false);
    }

    private JsonNode parseBody(String body) {
        try {
            return mapper.readTree(body);
        } catch (JsonProcessingException e) {
            return JsonNodeFactory.instance.textNode(body);
        }
    }
}
