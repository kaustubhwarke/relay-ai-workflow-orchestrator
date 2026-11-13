package com.relay.domain;

import java.util.Set;

/** The documented run state machine vocabulary. Wire format is the lower-case {@link #value()}. */
public enum RunStatus {
    QUEUED("queued"),
    RUNNING("running"),
    WAITING_APPROVAL("waiting_approval"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    CANCELLED("cancelled");

    private final String value;

    RunStatus(String value) { this.value = value; }

    public String value() { return value; }

    public boolean isTerminal() { return this == SUCCEEDED || this == FAILED || this == CANCELLED; }

    public static final Set<RunStatus> TERMINAL = Set.of(SUCCEEDED, FAILED, CANCELLED);

    public static RunStatus fromValue(String v) {
        for (RunStatus s : values()) {
            if (s.value.equals(v)) return s;
        }
        throw new IllegalArgumentException("Unknown run status: " + v);
    }
}
