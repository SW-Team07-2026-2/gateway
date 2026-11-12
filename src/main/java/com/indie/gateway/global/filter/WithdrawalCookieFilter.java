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
 * 회원 탈퇴(DELETE /api/members/me)가 성공하면 인증 쿠키(AT · RT · XSRF)를 지웁니다.
 * 쿠키를 굽는 주체가 Gateway 라서 Member 는 회원 삭제 · RT 폐기까지만 하고, 브라우저의 로그아웃은 여기서 끝냅니다.
 * 🔴 성공(2xx)일 때만 — 탈퇴가 안 됐는데(4xx · 5xx · 폴백 503) 로그아웃되면 안 됩니다.
 * set 으로 넣습니다 — 같은 요청에서 ③ 선제 갱신이 붙인 새 AT · RT 가 있어도 만료 쿠키만 남습니다.
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
