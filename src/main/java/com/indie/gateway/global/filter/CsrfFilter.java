package com.indie.gateway.global.filter;

import com.indie.gateway.domain.auth.exception.AuthErrorCode;
import com.indie.gateway.domain.auth.service.CookieFactory;
import com.indie.gateway.global.common.response.ErrorResponses;
import com.indie.gateway.global.common.util.PathRules;
import org.springframework.core.Ordered;
import org.springframework.http.HttpCookie;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 상태 변경 요청의 XSRF-TOKEN 쿠키와 X-XSRF-TOKEN 헤더를 상수 시간으로 비교합니다.
 * 웹훅과 OAuth 경로는 각각 서명과 state 검증을 사용하므로 제외합니다.
 */
@Component
public class CsrfFilter implements WebFilter, Ordered {

    public static final String HEADER = "X-XSRF-TOKEN";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        if (!PathRules.isStateChanging(request.getMethod()) || PathRules.isCsrfExempt(request.getPath().value())) {
            return chain.filter(exchange);
        }
        HttpCookie cookie = request.getCookies().getFirst(CookieFactory.XSRF);
        String header = request.getHeaders().getFirst(HEADER);
        if (cookie == null || cookie.getValue().isEmpty()) {
            return ErrorResponses.write(exchange, AuthErrorCode.CSRF_COOKIE_MISSING);
        }
        if (header == null) {
            return ErrorResponses.write(exchange, AuthErrorCode.CSRF_HEADER_MISSING);
        }
        if (!MessageDigest.isEqual(cookie.getValue().getBytes(StandardCharsets.UTF_8), header.getBytes(StandardCharsets.UTF_8))) {
            return ErrorResponses.write(exchange, AuthErrorCode.CSRF_MISMATCH);
        }
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return GatewayFilterOrder.CSRF;
    }
}
