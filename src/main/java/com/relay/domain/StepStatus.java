package com.relay.domain;

public enum StepStatus {
    RUNNING("running"),
    WAITING("waiting"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    SKIPPED("skipped");

    private final String value;

    StepStatus(String value) { this.value = value; }

    public String value() { return value; }

    public static StepStatus fromValue(String v) {
        for (StepStatus s : values()) {
            if (s.value.equals(v)) return s;
        }
        throw new IllegalArgumentException("Unknown step status: " + v);
    }
}
