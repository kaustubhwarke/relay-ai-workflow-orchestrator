package com.relay.domain;

import java.util.List;
import org.springframework.http.HttpStatus;

/** Base type for errors that map to a clear HTTP response. */
public class RelayException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final List<String> details;

    public RelayException(HttpStatus status, String code, String message) {
        this(status, code, message, List.of());
    }

    public RelayException(HttpStatus status, String code, String message, List<String> details) {
        super(message);
        this.status = java.util.Objects.requireNonNull(status, "status");
        this.code = java.util.Objects.requireNonNull(code, "code");
        this.details = details == null ? List.of() : List.copyOf(details);
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public List<String> details() { return details; }

    public static RelayException notFound(String what, String id) {
        return new RelayException(HttpStatus.NOT_FOUND, "not_found", what + " '" + id + "' was not found");
    }

    public static RelayException conflict(String code, String message) {
        return new RelayException(HttpStatus.CONFLICT, code, message);
    }

    public static RelayException badRequest(String code, String message) {
        return new RelayException(HttpStatus.BAD_REQUEST, code, message);
    }
}
