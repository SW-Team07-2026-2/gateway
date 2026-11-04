package com.indie.gateway.global.filter;

import com.indie.gateway.domain.auth.entity.TokenClaims;
import com.indie.gateway.domain.auth.exception.AuthErrorCode;
import com.indie.gateway.global.common.response.ErrorResponses;
import com.indie.gateway.global.common.util.GatewayAttributes;
import com.indie.gateway.global.common.util.PathRules;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * ④ jti 블랙리스트 — ★ fail-open.
 * Redis 장애 시 최악은 "로그아웃한 토큰이 30분(AT 수명) 남는 것", fail-closed 면 "서비스 전면 401".
 * 명세의 block() 예시는 이벤트 루프에서 IllegalStateException 이 나므로 리액티브 timeout 으로 같은 동작을 냅니다.
 */
@Component
public class BlacklistFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(BlacklistFilter.class);
    private static final Duration TIMEOUT = Duration.ofMillis(100);

    private final ReactiveStringRedisTemplate authRedis;
    private final MeterRegistry meterRegistry;

    public BlacklistFilter(@Qualifier("authRedis") ReactiveStringRedisTemplate authRedis, MeterRegistry meterRegistry) {
        this.authRedis = authRedis;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        TokenClaims claims = GatewayAttributes.claims(exchange);
        if (claims == null) {
            return chain.filter(exchange);
        }
        return authRedis.hasKey("member:blacklist:" + claims.jti())
                .timeout(TIMEOUT)
                .onErrorResume(e -> {
                    log.error("blacklist_check_failed error={}", e.toString());
                    meterRegistry.counter("gateway.blacklist.failure").increment();
                    return Mono.just(false);   // ★ 통과
                })
                .flatMap(revoked -> {
                    if (!revoked) return chain.filter(exchange);
                    meterRegistry.counter("gateway.auth.failure", "reason", "revoked").increment();
                    exchange.getAttributes().remove(GatewayAttributes.CLAIMS);
                    if (PathRules.isPublic(exchange.getRequest().getMethod(), exchange.getRequest().getPath().value())) {
                        return chain.filter(exchange);   // 공개 경로는 익명으로 계속
                    }
                    return ErrorResponses.write(exchange, AuthErrorCode.TOKEN_REVOKED);
                });
    }

    @Override
    public int getOrder() {
        return GatewayFilterOrder.BLACKLIST;
    }
}
