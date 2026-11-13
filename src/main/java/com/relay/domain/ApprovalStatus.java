package com.relay.domain;

public enum ApprovalStatus {
    PENDING("pending"),
    APPROVED("approved"),
    REJECTED("rejected"),
    CANCELLED("cancelled");

    private final String value;

    ApprovalStatus(String value) { this.value = value; }

    public String value() { return value; }

    public static ApprovalStatus fromValue(String v) {
        for (ApprovalStatus s : values()) {
            if (s.value.equals(v)) return s;
        }
        throw new IllegalArgumentException("Unknown approval status: " + v);
    }
}
