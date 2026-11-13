package com.relay.engine;

import com.relay.repository.StepRepository;

/**
 * A node failed. {@code retryable} distinguishes transient faults (timeouts, 5xx, 429), which the engine
 * retries with exponential backoff, from permanent ones (4xx, bad templates, schema violations), which fail
 * the step immediately.
 */
public class NodeExecutionException extends RuntimeException {

    private final boolean retryable;
    private final String code;
    private final StepRepository.AiUsage usage;

    public NodeExecutionException(String code, String message, boolean retryable) {
        this(code, message, retryable, StepRepository.AiUsage.NONE, null);
    }

    public NodeExecutionException(String code, String message, boolean retryable, Throwable cause) {
        this(code, message, retryable, StepRepository.AiUsage.NONE, cause);
    }

    public NodeExecutionException(String code, String message, boolean retryable,
                                  StepRepository.AiUsage usage, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.retryable = retryable;
        this.usage = usage == null ? StepRepository.AiUsage.NONE : usage;
    }

    public boolean isRetryable() { return retryable; }
    public String code() { return code; }
    public StepRepository.AiUsage usage() { return usage; }
}
