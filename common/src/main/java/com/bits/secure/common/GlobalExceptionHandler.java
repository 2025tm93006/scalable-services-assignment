package com.bits.secure.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<Object> conflict(ConflictException ex, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<Object> notFound(NotFoundException ex, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Object> badRequest(IllegalArgumentException ex, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(
                field -> fields.putIfAbsent(field.getField(), field.getDefaultMessage()));
        return new ResponseEntity<>(body(status, "Request validation failed",
                ((ServletWebRequest) request).getRequest(), fields), headers, status);
    }

    // Keep Spring's 400/405/415 statuses while using the same JSON error model.
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object ignored, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        String message = ex instanceof HttpMessageNotReadableException
                ? "Malformed JSON or invalid field type" : ex.getMessage();
        return new ResponseEntity<>(body(status, message,
                ((ServletWebRequest) request).getRequest(), Map.of()), headers, status);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception ex, HttpServletRequest request) {
        log.error("request_failed path={}", request.getRequestURI(), ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", request);
    }

    private ResponseEntity<Object> error(HttpStatus status, String message, HttpServletRequest request) {
        return ResponseEntity.status(status).body(body(status, message, request, Map.of()));
    }

    private ApiError body(HttpStatusCode status, String message, HttpServletRequest request,
                          Map<String, String> fields) {
        HttpStatus known = HttpStatus.resolve(status.value());
        return new ApiError(Instant.now(), status.value(),
                known == null ? "Request failed" : known.getReasonPhrase(), message,
                request.getRequestURI(), (String) request.getAttribute(CorrelationIdFilter.HEADER), fields);
    }
}
