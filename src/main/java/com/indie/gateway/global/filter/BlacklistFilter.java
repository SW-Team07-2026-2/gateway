package com.indie.gateway.global.filter;

import com.indie.gateway.domain.auth.entity.TokenClaims;
import com.indie.gateway.domain.auth.client.MemberAuthClient;
import com.indie.gateway.global.common.exception.CommonErrorCode;
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
import java.util.List;

/**
 * 인증용 Redis 에서 로그아웃한 토큰(member:blacklist:{jti})과 탈퇴한 회원(member:withdrawn:{userId})을 한 번에(MGET) 확인합니다.
 * 두 키는 Member가 씁니다. 표시가 없으면 회원 DB로 확인하고 Redis 장애 시 보호 요청을 차단합니다.
 */
@Component
public class BlacklistFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(BlacklistFilter.class);
    private static final Duration TIMEOUT = Duration.ofMillis(100);

    private final ReactiveStringRedisTemplate authRedis;
    private final MeterRegistry meterRegistry;
    private final MemberAuthClient member;

    public BlacklistFilter(@Qualifier("authRedis") ReactiveStringRedisTemplate authRedis, MeterRegistry meterRegistry, MemberAuthClient member) {
        this.authRedis = authRedis;
        this.meterRegistry = meterRegistry;
        this.member = member;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        TokenClaims claims = GatewayAttributes.claims(exchange);
        if (claims == null) {
            return chain.filter(exchange);
        }
        return authRedis.opsForValue().multiGet(List.of("member:blacklist:" + claims.jti(), "member:withdrawn:" + claims.sub()))
                .map(values -> present(values, 0) ? "revoked" : present(values, 1) ? "withdrawn" : "")
                .timeout(TIMEOUT)
                .onErrorResume(e -> {
                    log.error("blacklist_check_failed error={}", e.toString());
                    meterRegistry.counter("gateway.blacklist.failure").increment();
                    return Mono.just("unavailable");
                })
                .flatMap(reason -> reason.isEmpty()
                        ? member.exists(claims.sub()).timeout(Duration.ofSeconds(2))
                            .map(exists -> exists ? "" : "withdrawn").defaultIfEmpty("unavailable")
                            .onErrorReturn("unavailable")
                        : Mono.just(reason))
                .flatMap(reason -> {
                    if (reason.isEmpty()) return chain.filter(exchange);
                    meterRegistry.counter("gateway.auth.failure", "reason", reason).increment();
                    exchange.getAttributes().remove(GatewayAttributes.CLAIMS);
                    if (PathRules.isPublic(exchange.getRequest().getMethod(), exchange.getRequest().getPath().value())) {
                        return chain.filter(exchange);   // 공개 경로는 익명으로 계속
                    }
                    return ErrorResponses.write(exchange, reason.equals("unavailable")
                            ? CommonErrorCode.SERVICE_UNAVAILABLE : AuthErrorCode.TOKEN_REVOKED);
                });
    }

    /** MGET 은 없는 키를 null 이나 빈 값으로 돌려줍니다. Member 는 "1" 을 씁니다 */
    private static boolean present(List<String> values, int index) {
        String value = index < values.size() ? values.get(index) : null;
        return value != null && !value.isEmpty();
    }

    @Override
    public int getOrder() {
        return GatewayFilterOrder.BLACKLIST;
    }
}
