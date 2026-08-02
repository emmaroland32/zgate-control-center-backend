package com.zgate.controlcenter.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an endpoint as requiring a RECENT re-authentication, on top of a valid session.
 *
 * <p>For the actions where a hijacked or unattended session does the most damage: destroying a
 * customer's deployment, rolling a release across the fleet, restoring the control-plane database,
 * exporting a customer's backup, storing a cloud credential, resetting another operator's MFA. A
 * valid JWT proves someone signed in at some point in the last 24 hours; it does not prove the
 * person at the keyboard right now is that operator.
 *
 * <p>The caller supplies {@code X-StepUp-Ticket}, obtained from {@code POST /api/v1/auth/step-up}
 * by re-entering their password (and MFA code, when enabled). Missing or stale gets a 403 with
 * {@code code: STEP_UP_REQUIRED}, which the console turns into a re-authentication prompt.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequiresStepUp {

    /** Maximum age of an acceptable ticket. Short by design — this is "prove it again, now". */
    int maxAgeSeconds() default 300;
}
