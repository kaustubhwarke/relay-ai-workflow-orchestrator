package com.relay.engine;

/** A {@code {{path}}} reference could not be resolved. Never retryable: the data will not change. */
public class TemplateException extends RuntimeException {
    public TemplateException(String message) {
        super(message);
    }
}
