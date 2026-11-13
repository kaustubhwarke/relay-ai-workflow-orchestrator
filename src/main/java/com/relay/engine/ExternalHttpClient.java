package com.relay.engine;

import com.relay.config.RelayProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The single place the engine makes outbound HTTP calls to the "world". Every call has a connect and a
 * request timeout, so a hung dependency fails the step (and triggers retry/backoff), never the engine.
 */
@Component
public class ExternalHttpClient {

    public record Response(int status, String body) {}

    private final HttpClient client;
    private final int maxResponseBytes;

    public ExternalHttpClient(RelayProperties props) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getMockWorld().getTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER) // redirects could bypass the egress allow-list
                .build();
        this.maxResponseBytes = props.getHttp().getMaxResponseBytes();
    }

    /**
     * @param body JSON text, or {@code null} for no body
     * @throws NodeExecutionException (retryable) on connection failures and timeouts
     */
    public Response call(String method, URI uri, Map<String, String> headers, String body, Duration timeout) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(timeout);
        headers.forEach(b::header);
        if (body != null && headers.keySet().stream().noneMatch("Content-Type"::equalsIgnoreCase)) {
            b.header("Content-Type", "application/json");
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        try {
            HttpResponse<java.io.InputStream> resp = client.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
            // read at most maxResponseBytes + 1 bytes, so an oversized or endless body never sits in memory
            try (java.io.InputStream in = resp.body()) {
                byte[] raw = in.readNBytes(maxResponseBytes + 1);
                if (raw.length > maxResponseBytes) {
                    throw new NodeExecutionException("response_too_large",
                            "Response exceeded " + maxResponseBytes + " bytes", false);
                }
                return new Response(resp.statusCode(), new String(raw, StandardCharsets.UTF_8));
            }
        } catch (HttpTimeoutException e) {
            throw new NodeExecutionException("dependency_timeout",
                    "Timed out calling " + uri.getHost() + ":" + uri.getPort() + " after " + timeout.toMillis() + " ms", true, e);
        } catch (IOException e) {
            throw new NodeExecutionException("dependency_unavailable",
                    "Could not reach " + uri.getHost() + ":" + uri.getPort() + ": " + e.getClass().getSimpleName(), true, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NodeExecutionException("interrupted", "Interrupted while calling external service", true, e);
        }
    }

    /** 408/429/5xx are transient; every other non-2xx status is a permanent rejection. */
    public static boolean isTransientStatus(int status) {
        return status == 408 || status == 429 || status >= 500;
    }
}
