package com.zgate.controlcenter.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Opts a controller method OUT of the {@link ApiResponse} envelope — its body is returned raw.
 *
 * <p>Use on machine-to-machine endpoints whose response is parsed by non-browser clients that have
 * no unwrapping interceptor (e.g. ZGATE org instances pulling their signed licence bundles, or a
 * deployment agent requesting an image-pull token). Wrapping those would silently break the
 * cross-service contract. Web-facing endpoints must NOT use this — they rely on the envelope.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RawResponse {
}
