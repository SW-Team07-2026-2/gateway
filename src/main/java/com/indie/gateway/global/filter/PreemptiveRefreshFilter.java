package com.indie.gateway.global.filter;

import com.indie.gateway.domain.auth.service.AccessTokenVerifier;
import com.indie.gateway.domain.auth.service.CookieFactory;
import com.indie.gateway.domain.auth.client.MemberAuthClient;
import com.indie.gateway.global.config.GatewayProperties;
import com.indie.gateway.domain.auth.dto.response.RefreshResponse;
import com.indie.gateway.domain.auth.entity.TokenClaims;
import com.indie.gateway.global.common.util.GatewayAttributes;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.util.stream.Collectors;

/**
 * ③ 선제 갱신 — exp - now < 5분이면 RT 로 Member 에 갱신을 요청합니다.
 * <ul>
 *   <li>★ 갱신 실패가 요청 실패가 아닙니다 — 아직 만료 전 토큰이니 원래 요청을 그대로 진행</li>
 *   <li>동일 세션 동시 갱신 방지 — SETNX auth:refresh:lock:{jti} 5초. 못 잡으면 건너뜀</li>
 *   <li>★ 새 AT · RT 는 반드시 원래 응답의 Set-Cookie 에 싣습니다 (빠지면 매 요청 갱신 반복)</li>
 * </ul>
 */
@Component
public class PreemptiveRefreshFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(PreemptiveRefreshFilter.class);
    private static final Duration LOCK_TTL = Duration.ofSeconds(5);

    private final MemberAuthClient memberAuthClient;
    private final CookieFactory cookieFactory;
    private final ReactiveStringRedisTemplate authRedis;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final long thresholdSeconds;

    public PreemptiveRefreshFilter(MemberAuthClient memberAuthClient, CookieFactory cookieFactory,
                                   @Qualifier("authRedis") ReactiveStringRedisTemplate authRedis,
                                   MeterRegistry meterRegistry, Clock clock, GatewayProperties props) {
        this.memberAuthClient = memberAuthClient;
        this.cookieFactory = cookieFactory;
        this.authRedis = authRedis;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        this.thresholdSeconds = props.token().refreshThresholdSeconds();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        TokenClaims claims = GatewayAttributes.claims(exchange);
        HttpCookie rt = exchange.getRequest().getCookies().getFirst(CookieFactory.RT);
        if (claims == null || rt == null || rt.getValue().isBlank()
                || Duration.between(clock.instant(), claims.exp()).toSeconds() >= thresholdSeconds) {
            return chain.filter(exchange);
        }

        return acquireLock(claims.jti())
                .flatMap(locked -> locked ? refresh(exchange, rt.getValue()) : Mono.just(exchange))
                .flatMap(chain::filter);
    }

    private Mono<Boolean> acquireLock(String jti) {
        return authRedis.opsForValue().setIfAbsent("auth:refresh:lock:" + jti, "1", LOCK_TTL)
                .onErrorResume(e -> {
                    log.warn("refresh_lock_failed error={}", e.toString());
                    return Mono.just(false);
                })
                .doOnNext(locked -> { if (!locked) count("skipped_locked"); });
    }

    private Mono<ServerWebExchange> refresh(ServerWebExchange exchange, String refreshToken) {
        return memberAuthClient.refresh(refreshToken)
                .map(tokens -> apply(exchange, tokens))
                .doOnNext(e -> count("success"))
                .onErrorResume(e -> {
                    log.warn("preemptive_refresh_failed error={}", e.toString());
                    count("failure");
                    return Mono.just(exchange);   // ★ 통과
                });
    }

    /** 응답에 새 쿠키를 싣고, 하위로는 새 AT 를 실어 보냅니다 (어시스턴트 재진입이 이 쿠키를 재사용) */
    private ServerWebExchange apply(ServerWebExchange exchange, RefreshResponse tokens) {
        exchange.getResponse().addCookie(cookieFactory.at(tokens.accessToken()));
        exchange.getResponse().addCookie(cookieFactory.rt(tokens.refreshToken()));
        exchange.getAttributes().put(GatewayAttributes.CLAIMS, AccessTokenVerifier.readClaims(tokens.accessToken()));

        String cookieHeader = exchange.getRequest().getCookies().values().stream()
                .flatMap(list -> list.stream().limit(1))
                .map(c -> switch (c.getName()) {
                    case CookieFactory.AT -> CookieFactory.AT + "=" + tokens.accessToken();
                    case CookieFactory.RT -> CookieFactory.RT + "=" + tokens.refreshToken();
                    default -> c.getName() + "=" + c.getValue();
                })
                .collect(Collectors.joining("; "));
        return exchange.mutate().request(r -> r.headers(h -> h.set(HttpHeaders.COOKIE, cookieHeader))).build();
    }

    private void count(String result) {
        meterRegistry.counter("gateway.preemptive.refresh", "result", result).increment();
    }

    @Override
    public int getOrder() {
        return GatewayFilterOrder.PREEMPTIVE_REFRESH;
    }
}
