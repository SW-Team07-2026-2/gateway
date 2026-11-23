package com.indie.gateway.global.filter;

import com.indie.gateway.domain.auth.entity.TokenClaims;
import com.indie.gateway.global.common.util.ClientIpResolver;
import com.indie.gateway.global.common.util.GatewayAttributes;
import com.indie.gateway.global.config.RateLimiterConfig;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.support.ConfigurationService;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** ⑥ 호출 한도 — 메트릭 수집 (member docs/v2_member_service_re.md §6.5 · 회원당 분당 30회) · 11/23 검색 (회원당 분당 500회) */
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

    /** 11/23 검색 — 검색 · 팔로우 탭 4종과 검색어를 붙인 목록은 회원당 한 묶음 */
    @Test
    void searchIsLimitedPerUser() {
        allow(true);

        run(MockServerHttpRequest.get("/api/catalog/search/goods").queryParam("q", "혁오").build());
        run(MockServerHttpRequest.get("/api/catalog/search/artists").build());
        run(MockServerHttpRequest.get("/api/catalog/following/physical-albums").build());
        run(MockServerHttpRequest.get("/api/concerts/search").queryParam("genre", "EMO").build());
        run(MockServerHttpRequest.get("/api/concerts/following").build());
        run(MockServerHttpRequest.get("/api/catalog/search").queryParam("q", "혁").build());
        run(MockServerHttpRequest.get("/api/catalog/artists").queryParam("q", "혁").build());   // 가입 화면 아티스트 검색

        verify(limiter, times(7)).isAllowed(RateLimiterConfig.SEARCH, RateLimiterConfig.SEARCH + ":user:42");
    }

    /** 비로그인 검색은 클라이언트 IP(CF-Connecting-IP) 단위 */
    @Test
    void anonymousSearchIsLimitedPerIp() {
        allow(true);
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/concerts/search")
                .header("CF-Connecting-IP", "203.0.113.7").build());

        filter.filter(exchange, e -> Mono.empty()).block(Duration.ofSeconds(5));

        verify(limiter).isAllowed(RateLimiterConfig.SEARCH, RateLimiterConfig.SEARCH + ":ip:203.0.113.7");
    }

    /** 검색어 없는 목록 · 상세 · 검색 경로의 다른 메서드는 대상이 아닙니다 */
    @Test
    void browsingIsNotLimitedAsSearch() {
        run(MockServerHttpRequest.get("/api/catalog/artists").build());
        run(MockServerHttpRequest.get("/api/catalog/artists/7").build());
        run(MockServerHttpRequest.get("/api/concerts/12").build());
        run(MockServerHttpRequest.get("/api/concerts/12/queue/status").build());
        run(MockServerHttpRequest.post("/api/concerts/12/bookings").build());

        verify(limiter, never()).isAllowed(anyString(), anyString());
    }

    /** 분당 500회(타자 500타/분 · 1타 = 1요청) · 한 번에 30회 */
    @Test
    void searchLimitIs500PerMinuteWithBurst30() {
        RedisRateLimiter real = new RateLimiterConfig().redisRateLimiter(mock(ReactiveStringRedisTemplate.class),
                mock(RedisScript.class), mock(ConfigurationService.class));
        RedisRateLimiter.Config search = real.getConfig().get(RateLimiterConfig.SEARCH);

        assertThat(search.getReplenishRate() * 60 / search.getRequestedTokens()).isEqualTo(500);
        assertThat(search.getBurstCapacity() / search.getRequestedTokens()).isEqualTo(30);
    }
}
