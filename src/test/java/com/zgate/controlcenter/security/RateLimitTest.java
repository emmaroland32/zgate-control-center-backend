package com.zgate.controlcenter.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zgate.controlcenter.service.RateLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.method.HandlerMethod;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The rate limiter.
 *
 * <p>Two properties carry the value. It must actually stop a burst — the account lockout caps
 * attempts against ONE account, but nothing capped an attacker walking a password list across many
 * accounts from a single source. And it must count in shared storage: per-replica buckets multiply
 * the effective limit by the replica count, so an attacker just reconnects until the load balancer
 * hands them a fresh one.
 */
class RateLimitTest {

    private JdbcTemplate jdbc;
    private RateLimitService service;

    /** Stands in for the shared table: (bucket_key, window_start) -> hits, incremented atomically. */
    private Map<String, Integer> table;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        table = new HashMap<>();
        jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any()))
            .thenAnswer(i -> {
                String key = i.getArgument(2) + "@" + i.getArgument(3);
                return table.merge(key, 1, Integer::sum);
            });
        service = new RateLimitService(jdbc);
        ReflectionTestUtils.setField(service, "enabled", true);
    }

    @Nested
    @DisplayName("counting")
    class Counting {
        @Test
        @DisplayName("allows up to the limit, then refuses with a usable Retry-After")
        void refusesPastTheLimit() {
            for (int i = 1; i <= 3; i++) {
                assertThat(service.record("k", 3, 60).allowed())
                    .describedAs("request %d of 3 should be allowed", i).isTrue();
            }
            RateLimitService.Decision d = service.record("k", 3, 60);
            assertThat(d.allowed()).isFalse();
            assertThat(d.retryAfterSeconds()).isBetween(1L, 60L);
            assertThat(d.remaining()).isZero();
        }

        @Test
        @DisplayName("separate buckets do not consume each other's quota")
        void bucketsAreIndependent() {
            for (int i = 0; i < 3; i++) service.record("attacker", 3, 60);
            assertThat(service.record("attacker", 3, 60).allowed()).isFalse();
            // A real operator on a different IP must be unaffected.
            assertThat(service.record("operator", 3, 60).allowed()).isTrue();
        }

        @Test
        @DisplayName("remaining counts down so a client can back off before being refused")
        void reportsRemaining() {
            assertThat(service.record("k", 3, 60).remaining()).isEqualTo(2);
            assertThat(service.record("k", 3, 60).remaining()).isEqualTo(1);
            assertThat(service.record("k", 3, 60).remaining()).isZero();
        }

        @Test
        @DisplayName("counting is ONE statement, so two replicas cannot both see room")
        void incrementIsAtomic() {
            // Read-then-write would be check-then-act: both replicas read a bucket under the limit,
            // both allow, and the effective limit becomes whatever the concurrency happens to be.
            service.record("k", 5, 60);
            verify(jdbc, times(1)).queryForObject(anyString(), eq(Integer.class), any(), any());
            verify(jdbc, never()).queryForList(anyString(), any(Class.class), any());
        }

        @Test
        @DisplayName("disabled means every request passes")
        void disabledAllowsEverything() {
            ReflectionTestUtils.setField(service, "enabled", false);
            for (int i = 0; i < 50; i++) {
                assertThat(service.record("k", 1, 60).allowed()).isTrue();
            }
            verifyNoInteractions(jdbc);
        }

        @Test
        @DisplayName("a database failure fails OPEN, not closed")
        void databaseFailureAllowsTheRequest() {
            // Everything behind this limiter needs the database anyway — sign-in reads the operator
            // row — so failing closed would turn a transient blip into a total console lockout
            // without protecting anything that was still reachable.
            when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("down"));
            assertThat(service.record("k", 1, 60).allowed()).isTrue();
        }
    }

    @Nested
    @DisplayName("bucket keys")
    class Keys {
        private RateLimitInterceptor interceptor;
        private MockHttpServletRequest request;
        private MockHttpServletResponse response;

        @BeforeEach
        void setUp() {
            interceptor = new RateLimitInterceptor(new ObjectMapper(), service, new ClientIpResolver(""));
            request = new MockHttpServletRequest();
            request.setRemoteAddr("203.0.113.9");
            response = new MockHttpServletResponse();
        }

        private HandlerMethod handler(String name) throws Exception {
            return new HandlerMethod(new Sample(), Sample.class.getMethod(name));
        }

        @Test
        @DisplayName("an unauthenticated caller is bucketed by IP and refused past the limit")
        void ipKeyed() throws Exception {
            for (int i = 0; i < 2; i++) {
                assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), handler("ipLimited")))
                    .isTrue();
            }
            assertThat(interceptor.preHandle(request, response, handler("ipLimited"))).isFalse();
            assertThat(response.getStatus()).isEqualTo(429);
            assertThat(response.getHeader("Retry-After")).isNotNull();
            // A body, not a bare status: Spring rewrites a pre-auth sendError into an empty 401,
            // which the console reads as an expired session and loops the operator through sign-in.
            assertThat(response.getContentAsString()).contains("RATE_LIMITED");
        }

        @Test
        @DisplayName("X-Forwarded-For cannot be varied for a fresh bucket per request")
        void forgedForwardedForDoesNotResetTheBucket() throws Exception {
            // The whole limit collapses if a client-controlled header picks the bucket.
            for (int i = 0; i < 2; i++) {
                MockHttpServletRequest r = new MockHttpServletRequest();
                r.setRemoteAddr("203.0.113.9");
                r.addHeader("X-Forwarded-For", "10.0.0." + i);
                interceptor.preHandle(r, new MockHttpServletResponse(), handler("ipLimited"));
            }
            MockHttpServletRequest r = new MockHttpServletRequest();
            r.setRemoteAddr("203.0.113.9");
            r.addHeader("X-Forwarded-For", "10.0.0.99");
            assertThat(interceptor.preHandle(r, response, handler("ipLimited"))).isFalse();
        }

        @Test
        @DisplayName("USER strategy refuses an anonymous caller rather than sharing one bucket")
        void userStrategyFailsClosedWhenAnonymous() throws Exception {
            assertThat(interceptor.preHandle(request, response, handler("userLimited"))).isFalse();
            assertThat(response.getStatus()).isEqualTo(401);
        }

        @Test
        @DisplayName("two operators do not consume each other's quota under USER keying")
        void userKeyedBucketsArePerOperator() throws Exception {
            authenticateAs("alice@example.com");
            for (int i = 0; i < 2; i++) {
                interceptor.preHandle(request, new MockHttpServletResponse(), handler("userLimited"));
            }
            assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), handler("userLimited")))
                .describedAs("alice is over her limit").isFalse();

            authenticateAs("bob@example.com");
            assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), handler("userLimited")))
                .describedAs("bob must be unaffected").isTrue();
        }

        @Test
        @DisplayName("one client cannot hold two buckets by switching IP stack")
        void oneClientGetsOneBucket() throws Exception {
            // A dual-stacked host sees the same caller in several spellings. Two spellings would
            // mean two buckets and twice the limit — found by reading bucket_key on a live run.
            for (String spelling : new String[]{"127.0.0.1", "0:0:0:0:0:0:0:1"}) {
                MockHttpServletRequest r = new MockHttpServletRequest();
                r.setRemoteAddr(spelling);
                interceptor.preHandle(r, new MockHttpServletResponse(), handler("ipLimited"));
            }
            MockHttpServletRequest third = new MockHttpServletRequest();
            third.setRemoteAddr("::1");
            assertThat(interceptor.preHandle(third, response, handler("ipLimited")))
                .describedAs("the third request from the SAME machine must be refused").isFalse();
        }

        @Test
        @DisplayName("an IPv4-mapped IPv6 address shares a bucket with its plain IPv4 form")
        void ipv4MappedSharesBucket() {
            assertThat(RateLimitInterceptor.normalize("::ffff:203.0.113.9"))
                .isEqualTo(RateLimitInterceptor.normalize("203.0.113.9"));
        }

        @Test
        @DisplayName("distinct addresses still get distinct buckets — normalising must not widen")
        void normalisationDoesNotMergeDifferentClients() {
            assertThat(RateLimitInterceptor.normalize("203.0.113.9"))
                .isNotEqualTo(RateLimitInterceptor.normalize("203.0.113.10"));
        }

        @Test
        @DisplayName("an unannotated method is untouched")
        void unannotatedPassesThrough() throws Exception {
            assertThat(interceptor.preHandle(request, response, handler("notLimited"))).isTrue();
            verifyNoInteractions(jdbc);
        }

        private void authenticateAs(String email) {
            SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(email, "x", List.of()));
        }
    }

    /** Handler methods carrying the annotations under test. */
    static class Sample {
        @RateLimit(limit = 2, windowSeconds = 60, keyBy = RateLimit.KeyStrategy.IP)
        public void ipLimited() {}

        @RateLimit(limit = 2, windowSeconds = 60, keyBy = RateLimit.KeyStrategy.USER)
        public void userLimited() {}

        public void notLimited() {}
    }
}
