package com.zgate.controlcenter.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Shared rate-limit counters.
 *
 * <p>Kept in the database rather than in memory, because per-replica buckets are worse than none:
 * the effective limit silently multiplies by the replica count, and an attacker only has to
 * reconnect until the load balancer hands them a fresh one. The main product's
 * {@code RateLimitInterceptor} says as much in its own class comment.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RateLimitService {

    private final JdbcTemplate jdbc;

    @Value("${controlcenter.rateLimit.enabled:true}")
    private boolean enabled;

    /** Outcome of one counted request. */
    public record Decision(boolean allowed, int limit, int remaining, long retryAfterSeconds) {
        static Decision allow(int limit, int hits) {
            return new Decision(true, limit, Math.max(0, limit - hits), 0);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Count this request against its bucket and decide whether it may proceed.
     *
     * <p>The count and the increment are ONE statement. Doing it as read-then-write would be
     * check-then-act: two replicas both read a bucket under the limit, both allow, and the limit
     * is whatever the concurrency happens to be. {@code ON CONFLICT ... DO UPDATE ... RETURNING}
     * makes the increment atomic and hands back the resulting count in the same round trip.
     */
    public Decision record(String bucketKey, int limit, int windowSeconds) {
        if (!enabled) return Decision.allow(limit, 0);

        long now = Instant.now().getEpochSecond();
        long windowStartEpoch = now - Math.floorMod(now, (long) windowSeconds);
        LocalDateTime windowStart = LocalDateTime.ofEpochSecond(windowStartEpoch, 0, ZoneOffset.UTC);

        Integer hits;
        try {
            hits = jdbc.queryForObject(
                "INSERT INTO rate_limit_buckets (bucket_key, window_start, hits) VALUES (?, ?, 1) "
              + "ON CONFLICT (bucket_key, window_start) "
              + "DO UPDATE SET hits = rate_limit_buckets.hits + 1 RETURNING hits",
                Integer.class, bucketKey, windowStart);
        } catch (RuntimeException e) {
            // Fail OPEN, deliberately. Every endpoint behind this limiter needs the database to do
            // its actual work — sign-in reads the operator row — so failing closed would convert a
            // transient database blip into a total lockout of the console without protecting
            // anything that was still reachable.
            log.warn("Rate limit check failed, allowing the request: {}", e.toString());
            return Decision.allow(limit, 0);
        }

        int count = hits == null ? 1 : hits;
        if (count > limit) {
            long retryAfter = Math.max(1, (windowStartEpoch + windowSeconds) - now);
            return new Decision(false, limit, 0, retryAfter);
        }
        return Decision.allow(limit, count);
    }

    /**
     * Drop windows that have elapsed. Without this the table grows one row per bucket per window
     * forever — slow, but on a long-lived control plane it is unbounded.
     */
    @Scheduled(fixedDelayString = "${controlcenter.rateLimit.sweepMs:3600000}", initialDelay = 300_000)
    @SchedulerLock(name = "rateLimitSweep", lockAtMostFor = "PT10M")
    public void sweepElapsedWindows() {
        try {
            int deleted = jdbc.update("DELETE FROM rate_limit_buckets WHERE window_start < ?",
                                      LocalDateTime.now(ZoneOffset.UTC).minusHours(2));
            if (deleted > 0) log.debug("Swept {} elapsed rate-limit windows", deleted);
        } catch (RuntimeException e) {
            log.warn("Rate-limit sweep failed: {}", e.toString());
        }
    }
}
