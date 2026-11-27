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
 * 검증된 토큰의 사용자 ID와 권한을 하위 서비스 요청 헤더에 넣습니다.
 * 비로그인 요청에는 사용자 ID를 넣지 않습니다.
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
