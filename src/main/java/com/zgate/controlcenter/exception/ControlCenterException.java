package com.zgate.controlcenter.exception;

import org.springframework.http.HttpStatus;

/**
 * Base application exception. Every business failure carries a stable, machine-readable {@code code}
 * and the {@link HttpStatus} it should surface as, so {@link GlobalExceptionHandler} can render a
 * uniform error envelope and the frontend never has to hardcode failure text.
 *
 * <p>The legacy single-arg constructor defaults to {@code 400 CONTROL_CENTER_ERROR} so existing
 * call sites keep working unchanged; new throwers should pass an explicit code + status.
 */
public class ControlCenterException extends RuntimeException {

    /** Default code used by the legacy constructors. */
    public static final String DEFAULT_CODE = "CONTROL_CENTER_ERROR";

    private final String code;
    private final HttpStatus status;

    public ControlCenterException(String message) {
        this(message, DEFAULT_CODE, HttpStatus.BAD_REQUEST);
    }

    public ControlCenterException(String message, Throwable cause) {
        super(message, cause);
        this.code = DEFAULT_CODE;
        this.status = HttpStatus.BAD_REQUEST;
    }

    public ControlCenterException(String message, String code, HttpStatus status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String getCode() { return code; }

    public HttpStatus getStatus() { return status; }
}
