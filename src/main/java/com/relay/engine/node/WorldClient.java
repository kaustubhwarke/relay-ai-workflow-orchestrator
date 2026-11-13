package com.relay.engine.node;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.relay.config.RelayProperties;
import com.relay.engine.ExternalHttpClient;
import com.relay.engine.NodeExecutionException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Calls the external "world" (mock world): email, chat, orders, refunds, shipments. All side-effect calls go
 * through here so the {@code Idempotency-Key} header and the timeout are applied in exactly one place.
 */
@Component
public class WorldClient {

    private final ExternalHttpClient http;
    private final RelayProperties props;
    private final ObjectMapper mapper;

    public WorldClient(ExternalHttpClient http, RelayProperties props, ObjectMapper mapper) {
        this.http = http;
        this.props = props;
        this.mapper = mapper;
    }

    /** POSTs a JSON body to {@code <world>/<path>} and returns the parsed JSON response. */
    public JsonNode post(String path, JsonNode body, String idempotencyKey) {
        String base = props.getMockWorld().getUrl();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        URI uri = URI.create(base + path);
        Map<String, String> headers = idempotencyKey == null
                ? Map.of()
                : Map.of("Idempotency-Key", idempotencyKey);
        ExternalHttpClient.Response resp = http.call("POST", uri, headers, serialize(body),
                Duration.ofMillis(props.getMockWorld().getTimeoutMs()));
        return interpret(resp, uri);
    }

    private JsonNode interpret(ExternalHttpClient.Response resp, URI uri) {
        int s = resp.status();
        if (s >= 200 && s < 300) {
            try {
                return mapper.readTree(resp.body());
            } catch (JsonProcessingException e) {
                throw new NodeExecutionException("dependency_bad_response", "World returned a non-JSON response", false, e);
            }
        }
        String detail = errorDetail(resp.body());
        if (ExternalHttpClient.isTransientStatus(s)) {
            throw new NodeExecutionException("dependency_unavailable",
                    "World call " + uri.getPath() + " failed with HTTP " + s + detail, true);
        }
        throw new NodeExecutionException("dependency_rejected",
                "World rejected " + uri.getPath() + " with HTTP " + s + detail, false);
    }

    private String errorDetail(String body) {
        try {
            JsonNode n = mapper.readTree(body);
            String msg = n.path("error").path("message").asText("");
            if (!msg.isBlank()) return ": " + truncate(msg, 300);
        } catch (JsonProcessingException ignored) {
            // fall through
        }
        return "";
    }

    private String serialize(JsonNode body) {
        try {
            return mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new NodeExecutionException("invalid_body", "Could not serialize request body", false, e);
        }
    }

    static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
