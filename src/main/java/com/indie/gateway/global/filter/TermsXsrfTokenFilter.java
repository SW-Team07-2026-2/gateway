package com.indie.gateway.global.filter;

import com.indie.gateway.domain.auth.service.CookieFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** 로컬 프론트는 API 도메인의 쿠키를 읽을 수 없어 약관 조회 응답으로 같은 CSRF 토큰을 전달합니다. */
@Component
public class TermsXsrfTokenFilter implements WebFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!HttpMethod.GET.equals(exchange.getRequest().getMethod())
                || !"/api/members/terms".equals(exchange.getRequest().getPath().value())) {
            return chain.filter(exchange);
        }
        HttpCookie cookie = exchange.getRequest().getCookies().getFirst(CookieFactory.XSRF);
        exchange.getResponse().beforeCommit(() -> {
            HttpHeaders headers = exchange.getResponse().getHeaders();
            headers.set(HttpHeaders.CACHE_CONTROL, "no-store");
            headers.remove(CsrfFilter.HEADER);
            var status = exchange.getResponse().getStatusCode();
            if ((status == null || status.is2xxSuccessful()) && cookie != null && !cookie.getValue().isBlank()) {
                headers.set(CsrfFilter.HEADER, cookie.getValue());
            }
            return Mono.empty();
        });
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return GatewayFilterOrder.TERMS_XSRF;
    }
}
