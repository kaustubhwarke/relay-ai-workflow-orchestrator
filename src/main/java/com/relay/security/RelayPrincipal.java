package com.relay.security;

import java.util.Set;
import org.springframework.security.core.Authentication;

/** An authenticated caller. {@code name} is recorded as the decider on approvals. */
public record RelayPrincipal(String name, Set<String> roles) {

    public static final String BUILDER = "BUILDER";
    public static final String OPERATOR = "OPERATOR";
    public static final String APPROVER = "APPROVER";

    public static String nameOf(Authentication auth) {
        return auth != null && auth.getPrincipal() instanceof RelayPrincipal p ? p.name() : "unknown";
    }
}
