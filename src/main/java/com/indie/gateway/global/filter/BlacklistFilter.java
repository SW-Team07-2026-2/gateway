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
 * 인증용 Redis에서 접근 토큰의 jti 블랙리스트를 확인합니다.
 * Redis 장애 시 요청을 허용하며 이벤트 루프를 막지 않도록 리액티브 타임아웃을 사용합니다.
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
                    return Mono.just(false);   // 통과
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
