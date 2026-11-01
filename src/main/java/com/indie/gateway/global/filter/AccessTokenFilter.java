package com.indie.gateway.global.filter;

import com.indie.gateway.domain.auth.exception.AuthErrorCode;
import com.indie.gateway.domain.auth.service.AccessTokenVerifier.TokenException;
import com.indie.gateway.domain.auth.service.AccessTokenVerifier;
import com.indie.gateway.domain.auth.service.CookieFactory;
import com.indie.gateway.global.common.response.ErrorResponses;
import com.indie.gateway.global.common.util.GatewayAttributes;
import com.indie.gateway.global.common.util.PathRules;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.core.Ordered;
import org.springframework.http.HttpCookie;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * ② AT 서명 · 만료 검증 (공개키).
 * 토큰 없음 — 공개 경로면 통과 · 보호 경로면 401. 공개 경로의 깨진 토큰은 익명으로 취급합니다.
 */
@Component
public class AccessTokenFilter implements WebFilter, Ordered {

    private static final String PASS = "";

    private final AccessTokenVerifier verifier;
    private final MeterRegistry meterRegistry;

    public AccessTokenFilter(AccessTokenVerifier verifier, MeterRegistry meterRegistry) {
        this.verifier = verifier;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();
        if (!PathRules.isKnown(path)) {
            return chain.filter(exchange);   // 라우트가 없는 경로 → 404 (/internal/** 를 401 로 노출하지 않음)
        }
        boolean publicPath = PathRules.isPublic(request.getMethod(), path);
        HttpCookie cookie = request.getCookies().getFirst(CookieFactory.AT);
        if (cookie == null || cookie.getValue().isBlank()) {
            if (publicPath) return chain.filter(exchange);
            meterRegistry.counter("gateway.auth.failure", "reason", "missing").increment();
            return reject(exchange, "TOKEN_INVALID");
        }

        return verifier.verify(cookie.getValue())
                .map(claims -> {
                    exchange.getAttributes().put(GatewayAttributes.CLAIMS, claims);
                    return PASS;
                })
                .onErrorResume(TokenException.class, e -> {
                    meterRegistry.counter("gateway.auth.failure", "reason",
                            e.code().equals("TOKEN_EXPIRED") ? "expired" : "invalid").increment();
                    return Mono.just(publicPath ? PASS : e.code());
                })
                .flatMap(outcome -> outcome.equals(PASS) ? chain.filter(exchange) : reject(exchange, outcome));
    }

    private static Mono<Void> reject(ServerWebExchange exchange, String code) {
        return ErrorResponses.write(exchange, AuthErrorCode.valueOf(code));
    }

    @Override
    public int getOrder() {
        return GatewayFilterOrder.ACCESS_TOKEN;
    }
}
