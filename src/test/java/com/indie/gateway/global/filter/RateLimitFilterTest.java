package com.indie.gateway.global.filter;

import com.indie.gateway.domain.auth.entity.TokenClaims;
import com.indie.gateway.global.common.util.ClientIpResolver;
import com.indie.gateway.global.common.util.GatewayAttributes;
import com.indie.gateway.global.config.RateLimiterConfig;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** ⑥ 호출 한도 — 메트릭 수집 (member docs/v2_member_service_re.md §6.5 · 회원당 분당 30회) */
class RateLimitFilterTest {

    private static final String METRICS = "/api/members/me/metrics";

    private final RedisRateLimiter limiter = mock(RedisRateLimiter.class);
    private final RateLimitFilter filter = new RateLimitFilter(limiter, new ClientIpResolver());
    private final AtomicBoolean passed = new AtomicBoolean();

    private MockServerWebExchange run(MockServerHttpRequest request) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put(GatewayAttributes.CLAIMS,
                new TokenClaims("42", List.of("USER"), "test-jti", Instant.now().plusSeconds(1800)));
        passed.set(false);
        filter.filter(exchange, e -> {
            passed.set(true);
            return Mono.empty();
        }).block(Duration.ofSeconds(5));
        return exchange;
    }

    private void allow(boolean allowed) {
        when(limiter.isAllowed(anyString(), anyString())).thenReturn(Mono.just(new RateLimiter.Response(allowed, Map.of())));
    }

    /** 한도는 IP 가 아니라 회원 단위 — 같은 공유기 뒤의 다른 회원끼리 서로의 한도를 쓰지 않습니다 */
    @Test
    void metricsCollectIsLimitedPerUser() {
        allow(true);

        run(MockServerHttpRequest.post(METRICS).build());

        assertThat(passed).isTrue();
        verify(limiter).isAllowed(RateLimiterConfig.MEMBER_METRICS, RateLimiterConfig.MEMBER_METRICS + ":user:42");
    }

    @Test
    void overLimitIs429() {
        allow(false);

        MockServerWebExchange exchange = run(MockServerHttpRequest.post(METRICS).build());

        assertThat(passed).isFalse();
        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(429);
        assertThat(exchange.getResponse().getBodyAsString().block(Duration.ofSeconds(1))).contains("TOO_MANY_REQUESTS");
    }

    /** 다른 회원 API 는 한도 대상이 아닙니다 */
    @Test
    void otherMemberApisAreNotLimited() {
        run(MockServerHttpRequest.get("/api/members/me").build());
        run(MockServerHttpRequest.post("/api/members/notifications/read").build());
        run(MockServerHttpRequest.delete("/api/members/me").build());

        assertThat(passed).isTrue();
        verify(limiter, never()).isAllowed(anyString(), anyString());
    }
}
