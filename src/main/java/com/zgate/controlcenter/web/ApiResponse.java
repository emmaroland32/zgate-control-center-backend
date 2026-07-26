package com.zgate.controlcenter.web;

/**
 * Uniform success envelope for every Control Center API response. Mirrors the error envelope
 * ({@code GlobalExceptionHandler.ErrorResponse}) so the frontend can handle both the same way:
 *
 * <pre>{ "code": "ORG_CREATED", "message": "Organization created", "data": { …payload } }</pre>
 *
 * <ul>
 *   <li><b>code</b> — stable, machine-readable ({@code "OK"} unless the endpoint declares one via
 *       {@link ResponseMessage}).</li>
 *   <li><b>message</b> — human-readable text the frontend surfaces verbatim on a toast; {@code null}
 *       for reads / endpoints that don't opt in, in which case the frontend shows no toast.</li>
 *   <li><b>data</b> — the actual payload the controller returned (entity, list, page, token, …).</li>
 * </ul>
 *
 * Applied centrally by {@link ApiResponseAdvice}; controllers keep returning their raw payload.
 */
public record ApiResponse<T>(String code, String message, T data) {

    public static <T> ApiResponse<T> of(String code, String message, T data) {
        return new ApiResponse<>(code, message, data);
    }
}
