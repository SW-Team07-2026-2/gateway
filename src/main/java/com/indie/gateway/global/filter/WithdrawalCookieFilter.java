package com.indie.gateway.global.filter;

import com.indie.gateway.domain.auth.service.CookieFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Member의 회원 탈퇴가 2xx로 끝나면 Gateway에서 인증 쿠키를 만료시킵니다.
 * 선제 갱신 쿠키가 있어도 만료 쿠키로 덮어씁니다.
 */
@Component
public class WithdrawalCookieFilter implements WebFilter, Ordered {

    private static final String PATH = "/api/members/me";
    private static final List<String> COOKIES = List.of(CookieFactory.AT, CookieFactory.RT, CookieFactory.XSRF);

    private final CookieFactory cookieFactory;

    public WithdrawalCookieFilter(CookieFactory cookieFactory) {
        this.cookieFactory = cookieFactory;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!HttpMethod.DELETE.equals(exchange.getRequest().getMethod())
                || !PATH.equals(exchange.getRequest().getPath().value())) {
            return chain.filter(exchange);
        }
        ServerHttpResponse response = exchange.getResponse();
        response.beforeCommit(() -> {
            HttpStatusCode status = response.getStatusCode();
            if (status != null && status.is2xxSuccessful()) {
                COOKIES.forEach(name -> response.getCookies().set(name, cookieFactory.expire(name)));
            }
            return Mono.empty();
        });
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return GatewayFilterOrder.WITHDRAWAL_COOKIE;
    }
}
