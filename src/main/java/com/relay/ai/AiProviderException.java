package com.relay.ai;

/** A model provider call failed. Messages never contain API keys or raw provider payloads. */
public class AiProviderException extends RuntimeException {

    private final boolean retryable;
    private final String code;

    public AiProviderException(String code, String message, boolean retryable) {
        this(code, message, retryable, null);
    }

    public AiProviderException(String code, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.retryable = retryable;
    }

    public boolean isRetryable() { return retryable; }
    public String code() { return code; }
}
