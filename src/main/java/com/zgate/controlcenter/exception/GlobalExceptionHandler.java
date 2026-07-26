package com.zgate.controlcenter.exception;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Single source of truth for API error responses. Every failure — validation, bad enum, business
 * rule, auth, or an unhandled bug — is rendered as ONE uniform {@link ErrorResponse} envelope:
 *
 * <pre>{ "status": 409, "code": "ORG_SLUG_TAKEN", "message": "...", "fieldErrors": {...}, "timestamp": ... }</pre>
 *
 * <ul>
 *   <li><b>status</b> — HTTP status int.</li>
 *   <li><b>code</b> — stable, machine-readable string the frontend can switch on.</li>
 *   <li><b>message</b> — human-readable and safe to show directly in a toast (never a stack trace).</li>
 *   <li><b>fieldErrors</b> — per-field messages for validation failures (else null).</li>
 * </ul>
 *
 * The frontend reads {@code message}/{@code fieldErrors} instead of hardcoding failure text.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    public record ErrorResponse(int status, String code, String message,
                                Map<String, String> fieldErrors, LocalDateTime timestamp) {
        static ErrorResponse of(HttpStatus status, String code, String message) {
            return new ErrorResponse(status.value(), code, message, null, LocalDateTime.now());
        }
    }

    private static ResponseEntity<ErrorResponse> respond(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(ErrorResponse.of(status, code, message));
    }

    /** Business-rule failures — carry their own code + status (e.g. 409 ORG_SLUG_TAKEN). */
    @ExceptionHandler(ControlCenterException.class)
    public ResponseEntity<ErrorResponse> handleControlCenter(ControlCenterException ex) {
        HttpStatus status = ex.getStatus() != null ? ex.getStatus() : HttpStatus.BAD_REQUEST;
        if (status.is5xxServerError()) {
            log.error("ControlCenterException [{}]: {}", ex.getCode(), ex.getMessage(), ex);
        }
        return respond(status, ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleBadCredentials(BadCredentialsException ex) {
        return respond(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid email or password");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccess(AccessDeniedException ex) {
        return respond(HttpStatus.FORBIDDEN, "ACCESS_DENIED",
            "You don't have permission to perform this action");
    }

    /**
     * Malformed / unparseable request body. The common case is an enum value the frontend sent that
     * the backend doesn't define (this is exactly what silently broke org creation) — surface the
     * field and the allowed values so it is obvious, not a generic "invalid body".
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        if (ex.getCause() instanceof InvalidFormatException ife
                && ife.getTargetType() != null && ife.getTargetType().isEnum()) {
            String field = ife.getPath().isEmpty() ? "value"
                : ife.getPath().get(ife.getPath().size() - 1).getFieldName();
            String allowed = Arrays.stream(ife.getTargetType().getEnumConstants())
                .map(Object::toString).collect(Collectors.joining(", "));
            return respond(HttpStatus.BAD_REQUEST, "INVALID_ENUM_VALUE",
                "Invalid value '" + ife.getValue() + "' for '" + field + "'. Allowed values: " + allowed);
        }
        return respond(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "The request body could not be read");
    }

    /** Bean-validation failures (@NotBlank/@NotNull/@Email/…) — one entry per invalid field. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(fe.getField(),
                fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "is invalid");
        }
        String summary = fieldErrors.entrySet().stream()
            .map(e -> e.getKey() + " " + e.getValue())
            .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(new ErrorResponse(400, "VALIDATION_ERROR",
            summary.isBlank() ? "Validation failed" : summary, fieldErrors, LocalDateTime.now()));
    }

    /** A path/query param of the wrong type — bad UUID, unknown enum in @RequestParam, etc. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String allowed = "";
        Class<?> t = ex.getRequiredType();
        if (t != null && t.isEnum()) {
            allowed = ". Allowed values: " + Arrays.stream(t.getEnumConstants())
                .map(Object::toString).collect(Collectors.joining(", "));
        }
        return respond(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER",
            "Invalid value '" + ex.getValue() + "' for parameter '" + ex.getName() + "'" + allowed);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException ex) {
        return respond(HttpStatus.BAD_REQUEST, "MISSING_PARAMETER",
            "Required parameter '" + ex.getParameterName() + "' is missing");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(Exception ex) {
        log.error("Unhandled exception [{}]: {}", ex.getClass().getSimpleName(), ex.getMessage(), ex);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
            "An unexpected error occurred. Please try again or contact support.");
    }
}
