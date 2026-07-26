package com.zgate.controlcenter.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the success {@code code} + {@code message} a controller method returns, so the message
 * the user sees is owned by the backend (never hardcoded in the UI). {@link ApiResponseAdvice} reads
 * it and places it in the {@link ApiResponse} envelope; the frontend surfaces {@code message} on a
 * toast.
 *
 * <p>Put it on genuine user-facing actions (create/update/delete/state-change). Endpoints WITHOUT
 * this annotation return {@code message = null} and therefore produce no success toast — that is the
 * intended way to keep reads, auth, health-checks, telemetry ingest, etc. quiet.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ResponseMessage {

    /** Human-readable success message, shown verbatim by the frontend. */
    String value();

    /** Stable machine-readable code (e.g. {@code ORG_CREATED}). */
    String code() default "OK";
}
