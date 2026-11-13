package com.relay.security;

import com.relay.config.RelayProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Maps presented bearer tokens to principals. A single demo token carries all three roles (builder, operator,
 * approver); additional tokens configured under {@code relay.security.tokens} enable real role separation.
 * Comparison is constant-time.
 */
public class PrincipalRegistry {

    private record Entry(byte[] tokenDigest, RelayPrincipal principal) {}

    private final List<Entry> entries = new ArrayList<>();

    public PrincipalRegistry(RelayProperties props) {
        String demo = props.getSecurity().getApiToken();
        if (demo != null && !demo.isBlank()) {
            entries.add(new Entry(digest(demo), new RelayPrincipal("demo",
                    Set.of(RelayPrincipal.BUILDER, RelayPrincipal.OPERATOR, RelayPrincipal.APPROVER))));
        }
        for (RelayProperties.TokenPrincipal t : props.getSecurity().getTokens()) {
            if (t.getToken() == null || t.getToken().isBlank()) continue;
            Set<String> roles = t.getRoles().stream().map(r -> r.toUpperCase(Locale.ROOT)).collect(Collectors.toSet());
            entries.add(new Entry(digest(t.getToken()), new RelayPrincipal(t.getName() == null ? "token" : t.getName(), roles)));
        }
    }

    public Optional<RelayPrincipal> authenticate(String presentedToken) {
        byte[] d = digest(presentedToken);
        RelayPrincipal match = null;
        for (Entry e : entries) { // no early exit: constant work regardless of which token matches
            if (MessageDigest.isEqual(e.tokenDigest(), d)) match = e.principal();
        }
        return Optional.ofNullable(match);
    }

    private static byte[] digest(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
