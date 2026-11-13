package com.relay.engine;

import com.relay.config.RelayProperties;
import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * SSRF guard for {@code http_request} nodes. A workflow author (or a compromised template value) must not
 * be able to make the engine call arbitrary internal addresses such as cloud metadata endpoints. Only
 * http/https, and only hosts on the allow-list plus the configured mock-world host, are permitted.
 */
@Component
public class EgressPolicy {

    private final Set<String> allowedHosts;

    public EgressPolicy(RelayProperties props) {
        String worldHost = URI.create(props.getMockWorld().getUrl()).getHost();
        this.allowedHosts = java.util.stream.Stream.concat(
                        props.getHttp().getAllowedHosts().stream(),
                        java.util.stream.Stream.of(worldHost))
                .filter(h -> h != null && !h.isBlank())
                .map(h -> h.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    /** @throws NodeExecutionException (non-retryable) if the URL is not allowed */
    public URI check(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new NodeExecutionException("invalid_url", "http_request url is not a valid URL", false);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new NodeExecutionException("egress_blocked", "http_request only supports http and https URLs", false);
        }
        if (uri.getUserInfo() != null) {
            throw new NodeExecutionException("egress_blocked", "http_request URLs may not embed credentials", false);
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!allowedHosts.contains(host)) {
            throw new NodeExecutionException("egress_blocked",
                    "Host '" + host + "' is not on the http_request allow-list (relay.http.allowed-hosts)", false);
        }
        rejectUnsafeAddresses(host);
        return uri;
    }

    /**
     * The host name is on the allow-list, but what it resolves to is checked too: addresses that are never a
     * legitimate target (the cloud metadata link-local range, wildcard, multicast) are refused even for an
     * allowed name, so a DNS record cannot point the engine at them. Loopback and private ranges stay
     * reachable because the allow-list is operator-controlled and the mock world itself lives there.
     * Redirects are disabled in {@link ExternalHttpClient}, so a response cannot move the call elsewhere.
     */
    private static void rejectUnsafeAddresses(String host) {
        java.net.InetAddress[] addresses;
        try {
            addresses = java.net.InetAddress.getAllByName(host);
        } catch (java.net.UnknownHostException e) {
            return; // not resolvable: the call itself fails as a dependency error
        }
        for (java.net.InetAddress a : addresses) {
            if (a.isLinkLocalAddress() || a.isAnyLocalAddress() || a.isMulticastAddress()) {
                throw new NodeExecutionException("egress_blocked",
                        "Host '" + host + "' resolves to a link-local, wildcard or multicast address, which is never allowed", false);
            }
        }
    }

    public Set<String> allowedHosts() { return allowedHosts; }
}
