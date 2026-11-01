package com.indie.gateway.global.filter;

import com.indie.gateway.domain.auth.entity.TokenClaims;
import com.indie.gateway.global.common.util.GatewayAttributes;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * ⑦ X-User-Id · X-User-Roles 주입 (X-Correlation-Id 는 ⓪' 에서 이미 실렸습니다).
 * 비로그인 요청에는 X-User-Id 를 넣지 않습니다 — 빈 문자열도 안 됩니다 (서비스가 "" 를 사용자로 오인).
 * X-Internal-Token 은 /internal/** 로 가는 호출에만 붙는데, Gateway 는 /internal/** 을 라우팅하지 않으므로 여기서 붙일 일이 없습니다.
 */
@Component
public class UserHeaderInjectFilter implements WebFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        TokenClaims claims = GatewayAttributes.claims(exchange);
        if (claims == null) {
            return chain.filter(exchange);
        }
        ServerWebExchange mutated = exchange.mutate()
                .request(r -> r.headers(h -> {
                    h.set("X-User-Id", claims.sub());
                    h.set("X-User-Roles", String.join(",", claims.roles()));
                }))
                .build();
        return chain.filter(mutated);
    }

    @Override
    public int getOrder() {
        return GatewayFilterOrder.USER_HEADER_INJECT;
    }
}
