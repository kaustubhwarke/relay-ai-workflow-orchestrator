package com.relay.engine;

import com.relay.domain.AiUsage;

/**
 * A node failed. {@code retryable} distinguishes transient faults (timeouts, 5xx, 429), which the engine
 * retries with exponential backoff, from permanent ones (4xx, bad templates, schema violations), which fail
 * the step immediately.
 */
public class NodeExecutionException extends RuntimeException {

    private final boolean retryable;
    private final String code;
    private final AiUsage usage;

    public NodeExecutionException(String code, String message, boolean retryable) {
        this(code, message, retryable, AiUsage.NONE, null);
    }

    public NodeExecutionException(String code, String message, boolean retryable, Throwable cause) {
        this(code, message, retryable, AiUsage.NONE, cause);
    }

    public NodeExecutionException(String code, String message, boolean retryable,
                                  AiUsage usage, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.retryable = retryable;
        this.usage = usage == null ? AiUsage.NONE : usage;
    }

    public boolean isRetryable() { return retryable; }
    public String code() { return code; }
    public AiUsage usage() { return usage; }
}
