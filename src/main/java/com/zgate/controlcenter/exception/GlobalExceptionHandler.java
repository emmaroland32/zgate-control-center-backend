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
@lombok.RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final com.zgate.controlcenter.service.AuditService audit;
    private final com.zgate.controlcenter.security.ClientIpResolver clientIpResolver;

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

    /**
     * Every sign-in failure the authentication manager can raise — wrong password, disabled
     * account, locked account — is the same 401 to the caller. Only {@code BadCredentialsException}
     * was mapped before, so a disabled operator's sign-in surfaced as a 500 and told them their
     * account state in the process.
     */
    @ExceptionHandler({BadCredentialsException.class,
                       org.springframework.security.core.AuthenticationException.class})
    public ResponseEntity<ErrorResponse> handleBadCredentials(
            org.springframework.security.core.AuthenticationException ex) {
        return respond(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid email or password");
    }

    /**
     * A signed-in operator calling an endpoint above their role. Audited: an ADMIN probing the
     * super-admin-only endpoints is exactly the kind of thing the activity monitor exists to show,
     * and method security refuses it before any controller-level audit could run.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccess(AccessDeniedException ex,
                                                      jakarta.servlet.http.HttpServletRequest http) {
        try {
            var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated()
                    && !(auth instanceof org.springframework.security.authentication.AnonymousAuthenticationToken)) {
                String actor = com.zgate.controlcenter.service.IdentityEvents.safeActor(auth.getName());
                String roles = auth.getAuthorities().stream().map(Object::toString)
                    .collect(Collectors.joining(","));
                String endpoint = http.getMethod() + " " + http.getRequestURI();
                audit.log(actor, actor, com.zgate.controlcenter.service.IdentityEvents.ACCESS_DENIED, "Endpoint",
                          endpoint.length() > 100 ? endpoint.substring(0, 100) : endpoint, null,
                          clientIpResolver.resolve(http), "role=" + roles,
                          com.zgate.controlcenter.domain.AuditLog.Status.FAILURE);
            }
        } catch (RuntimeException e) {
            log.warn("Could not audit an access denial: {}", e.toString());
        }
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

    /** A required header (the M2M org header, mostly) left out — a client mistake, so 400 not 500. */
    @ExceptionHandler(org.springframework.web.bind.MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(org.springframework.web.bind.MissingRequestHeaderException ex) {
        return respond(HttpStatus.BAD_REQUEST, "MISSING_HEADER",
            "Required header '" + ex.getHeaderName() + "' is missing");
    }

    /**
     * A database constraint caught what no validation did: a duplicate key (409), a missing
     * NOT NULL column or a dangling reference (400). Several controllers still persist raw
     * entities, so without this every such mistake surfaced as a 500 with the SQL in the log.
     */
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(org.springframework.dao.DataIntegrityViolationException ex) {
        String sqlState = null;
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof java.sql.SQLException sql) { sqlState = sql.getSQLState(); break; }
        }
        if ("23505".equals(sqlState)) {
            return respond(HttpStatus.CONFLICT, "DUPLICATE",
                "A record with the same unique value already exists");
        }
        if ("23502".equals(sqlState)) {
            return respond(HttpStatus.BAD_REQUEST, "MISSING_FIELD",
                "A required field is missing");
        }
        if ("23503".equals(sqlState)) {
            return respond(HttpStatus.BAD_REQUEST, "INVALID_REFERENCE",
                "The request refers to a record that does not exist or is still referenced");
        }
        log.warn("Data integrity violation (SQLSTATE {}): {}", sqlState, ex.getMostSpecificCause().getMessage());
        return respond(HttpStatus.BAD_REQUEST, "DATA_INTEGRITY", "The request violates a data constraint");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(Exception ex) {
        log.error("Unhandled exception [{}]: {}", ex.getClass().getSimpleName(), ex.getMessage(), ex);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
            "An unexpected error occurred. Please try again or contact support.");
    }
}
