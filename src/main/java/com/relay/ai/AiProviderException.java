package com.relay.ai;

import java.util.regex.Pattern;

/**
 * A model provider call failed. The message is sanitised on construction, so it is safe to log and to store
 * in a trace even if a caller passes provider text: credentials are masked and the length is bounded.
 */
public class AiProviderException extends RuntimeException {

    private static final int MAX_MESSAGE_CHARS = 300;
    private static final Pattern SECRET_LIKE = Pattern.compile(
            "(?i)(bearer\\s+[A-Za-z0-9._~+/=-]+|\\bsk-[A-Za-z0-9_-]{6,}|(api[_-]?key|authorization|token|secret)\\s*[=:]\\s*\\S+)");

    private final boolean retryable;
    private final String code;

    public AiProviderException(String code, String message, boolean retryable) {
        this(code, message, retryable, null);
    }

    public AiProviderException(String code, String message, boolean retryable, Throwable cause) {
        super(sanitize(message), cause);
        this.code = code;
        this.retryable = retryable;
    }

    /** Masks credential-looking text, collapses line breaks and bounds the length. */
    static String sanitize(String message) {
        if (message == null) return "";
        String clean = SECRET_LIKE.matcher(message).replaceAll("[REDACTED]").replaceAll("\\s+", " ").trim();
        return clean.length() > MAX_MESSAGE_CHARS ? clean.substring(0, MAX_MESSAGE_CHARS) + "…" : clean;
    }

    public boolean isRetryable() { return retryable; }
    public String code() { return code; }
}
