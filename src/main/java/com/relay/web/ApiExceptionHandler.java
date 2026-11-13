package com.relay.web;

import com.relay.domain.RelayException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * One error shape for every failure: {@code {"error": {"message", "code", "details"?}}}. Messages never include
 * stack traces, SQL, secrets or internal hostnames.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    public record ErrorBody(Error error) {
        public record Error(String message, String code, List<String> details) {}

        public static ErrorBody of(String code, String message, List<String> details) {
            return new ErrorBody(new Error(message, code, details == null || details.isEmpty() ? null : details));
        }
    }

    @ExceptionHandler(RelayException.class)
    public ResponseEntity<ErrorBody> relay(RelayException e) {
        return ResponseEntity.status(e.status()).body(ErrorBody.of(e.code(), e.getMessage(), e.details()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorBody> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(ErrorBody.of("invalid_json", "Request body is missing or is not valid JSON", null));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorBody> missingHeader(MissingRequestHeaderException e) {
        return ResponseEntity.badRequest().body(ErrorBody.of("missing_header", "Missing required header '" + e.getHeaderName() + "'", null));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorBody> typeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.badRequest().body(ErrorBody.of("invalid_parameter", "Invalid value for parameter '" + e.getName() + "'", null));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorBody> method(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(ErrorBody.of("method_not_allowed", "HTTP method not allowed on this route", null));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorBody> mediaType(HttpMediaTypeNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(ErrorBody.of("unsupported_media_type", "Content-Type must be application/json", null));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorBody> notFound(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorBody.of("not_found", "No such route", null));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ErrorBody> data(DataAccessException e) {
        log.error("Database error", e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ErrorBody.of("storage_unavailable", "The data store is temporarily unavailable", null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> unexpected(Exception e) {
        log.error("Unhandled error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ErrorBody.of("internal_error", "Unexpected server error", null));
    }
}
