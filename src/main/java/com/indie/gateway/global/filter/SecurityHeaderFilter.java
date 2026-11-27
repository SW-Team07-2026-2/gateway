package com.indie.gateway.global.filter;

import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** 응답 커밋 전에 보안 헤더를 설정합니다. 가장 먼저 등록해 거절 응답에도 적용합니다. */
@Component
public class SecurityHeaderFilter implements WebFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        exchange.getResponse().beforeCommit(() -> {
            HttpHeaders headers = exchange.getResponse().getHeaders();
            headers.set("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
            headers.set("X-Content-Type-Options", "nosniff");
            headers.set("X-Frame-Options", "DENY");
            headers.set("Referrer-Policy", "strict-origin-when-cross-origin");
            headers.set("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
            if (path.startsWith("/api/auth/")) {
                headers.set(HttpHeaders.CACHE_CONTROL, "no-store");
            }
            headers.remove(HttpHeaders.SERVER);
            headers.remove("X-Application-Context");
            return Mono.empty();
        });
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return GatewayFilterOrder.SECURITY_HEADER;
    }
}
