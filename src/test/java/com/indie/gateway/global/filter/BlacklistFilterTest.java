package com.indie.gateway.global.filter;

import com.indie.gateway.domain.auth.entity.TokenClaims;
import com.indie.gateway.domain.auth.client.MemberAuthClient;
import com.indie.gateway.global.common.util.GatewayAttributes;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 로그아웃한 토큰과 탈퇴한 회원의 토큰 차단 - 키는 Member 가 인증용 Redis 에 씁니다 */
class BlacklistFilterTest {

    @SuppressWarnings("unchecked")
    private final ReactiveValueOperations<String, String> values = mock(ReactiveValueOperations.class);
    private final ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MemberAuthClient member = mock(MemberAuthClient.class);
    private final BlacklistFilter filter = new BlacklistFilter(redis, meters, member);

    {
        when(redis.opsForValue()).thenReturn(values);
        when(member.exists("42")).thenReturn(Mono.just(true));
    }

    private MockServerWebExchange request(String path, String jti) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path));
        exchange.getAttributes().put(GatewayAttributes.CLAIMS, new TokenClaims("42", List.of("USER"), jti, Instant.now().plusSeconds(1200)));
        return exchange;
    }

    private void redisHas(String jti, String blacklist, String withdrawn) {
        when(values.multiGet(List.of("member:blacklist:" + jti, "member:withdrawn:42"))).thenReturn(Mono.just(Arrays.asList(blacklist, withdrawn)));
    }

    /** 탈퇴하면 그 회원의 다른 기기 토큰도 jti 와 상관없이 막힙니다 */
    @Test
    void withdrawnMemberIsBlockedOnEveryDevice() {
        for (String device : List.of("device1", "device2")) {
            redisHas(device, null, "1");
            MockServerWebExchange exchange = request("/api/cart", device);
            filter.filter(exchange, e -> Mono.error(new AssertionError("withdrawn member passed"))).block();
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(exchange.getResponse().getBodyAsString().block()).contains("TOKEN_REVOKED");
        }
        assertThat(meters.counter("gateway.auth.failure", "reason", "withdrawn").count()).isEqualTo(2);
    }

    @Test
    void loggedOutTokenIsBlocked() {
        redisHas("device1", "1", null);
        MockServerWebExchange exchange = request("/api/cart", "device1");
        filter.filter(exchange, e -> Mono.error(new AssertionError("revoked token passed"))).block();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(meters.counter("gateway.auth.failure", "reason", "revoked").count()).isEqualTo(1);
    }

    @Test
    void activeMemberPasses() {
        redisHas("device1", null, null);
        AtomicBoolean passed = new AtomicBoolean();
        filter.filter(request("/api/cart", "device1"), e -> { passed.set(true); return Mono.empty(); }).block();
        assertThat(passed).isTrue();
    }

    /** 공개 경로는 익명으로 계속 - 탈퇴 회원도 공연, 아티스트는 볼 수 있습니다 */
    @Test
    void withdrawnMemberReadsPublicPathsAnonymously() {
        redisHas("device1", null, "1");
        AtomicBoolean passed = new AtomicBoolean();
        filter.filter(request("/api/catalog/artists", "device1"), e -> {
            assertThat(GatewayAttributes.claims(e)).isNull();
            passed.set(true);
            return Mono.empty();
        }).block();
        assertThat(passed).isTrue();
    }

    /** Redis 장애 시 보호 요청을 차단합니다. */
    @Test
    void redisFailureBlocks() {
        when(values.multiGet(List.of("member:blacklist:device1", "member:withdrawn:42"))).thenReturn(Mono.error(new IllegalStateException("redis down")));
        AtomicBoolean passed = new AtomicBoolean();
        var exchange = request("/api/cart", "device1");
        filter.filter(exchange, e -> { passed.set(true); return Mono.empty(); }).block();
        assertThat(passed).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(meters.counter("gateway.blacklist.failure").count()).isEqualTo(1);
    }

    @Test
    void lostWithdrawalMarkerStillBlocksEveryDevice() {
        when(member.exists("42")).thenReturn(Mono.just(false));
        for (String device : List.of("device1", "device2")) {
            redisHas(device, null, null);
            var exchange = request("/api/cart", device);
            filter.filter(exchange, e -> Mono.error(new AssertionError("deleted member passed"))).block();
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Test
    void memberFailureBlocksWhenMarkerIsMissing() {
        redisHas("device1", null, null);
        when(member.exists("42")).thenReturn(Mono.error(new IllegalStateException("down")));
        var exchange = request("/api/cart", "device1");
        filter.filter(exchange, e -> Mono.error(new AssertionError("passed"))).block();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void anonymousRequestSkipsRedis() {
        filter.filter(MockServerWebExchange.from(MockServerHttpRequest.get("/api/catalog/artists")), e -> Mono.empty()).block();
        verifyNoInteractions(redis);
    }
}
