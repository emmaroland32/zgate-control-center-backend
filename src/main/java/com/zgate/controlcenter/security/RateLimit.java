package com.zgate.controlcenter.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Caps how often the annotated controller method may be called inside a fixed window, counted in
 * the database so the limit holds across replicas.
 *
 * <pre>
 *   &#64;RateLimit(limit = 10, windowSeconds = 60)
 *   &#64;PostMapping("/login")
 * </pre>
 *
 * <p>Returns 429 with {@code Retry-After} once the bucket is full.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RateLimit {

    int limit();

    int windowSeconds();

    /**
     * How requests are grouped into buckets. {@code AUTO} uses the authenticated principal when
     * there is one and the client IP otherwise; {@code IP} forces IP keying even when
     * authenticated; {@code USER} requires a principal and refuses without one.
     */
    KeyStrategy keyBy() default KeyStrategy.AUTO;

    enum KeyStrategy { AUTO, IP, USER }
}
