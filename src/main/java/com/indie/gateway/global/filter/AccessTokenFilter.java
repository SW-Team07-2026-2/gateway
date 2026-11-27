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
 * 접근 토큰의 서명과 만료를 검증합니다.
 * 토큰이 없거나 잘못됐으면 공개 경로는 익명으로 처리하고 보호 경로는 401을 반환합니다.
 */
@Component
public class AccessTokenFilter implements WebFilter, Ordered {

    private static final String PASS = "";
    private static final String AUTH_ME = "/api/auth/me";

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
            return chain.filter(exchange);   // 라우트가 없는 경로 -> 404 (/internal/** 를 401 로 노출하지 않음)
        }
        boolean publicPath = PathRules.isPublic(request.getMethod(), path);
        HttpCookie cookie = request.getCookies().getFirst(CookieFactory.AT);
        if (cookie == null || cookie.getValue().isBlank()) {
            if (publicPath) return chain.filter(exchange);
            // 비로그인 화면의 로그인 확인 (G3) 은 401 이 정상 응답이라 실패로 세지 않음
            if (!path.equals(AUTH_ME)) {
                meterRegistry.counter("gateway.auth.failure", "reason", "missing").increment();
            }
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
