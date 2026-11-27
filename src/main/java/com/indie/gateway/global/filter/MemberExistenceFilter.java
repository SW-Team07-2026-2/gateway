package com.indie.gateway.global.filter;

import com.indie.gateway.domain.auth.client.MemberAuthClient;
import com.indie.gateway.domain.auth.exception.AuthErrorCode;
import com.indie.gateway.global.common.exception.CommonErrorCode;
import com.indie.gateway.global.common.response.ErrorResponses;
import com.indie.gateway.global.common.util.GatewayAttributes;
import com.indie.gateway.global.common.util.PathRules;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.time.Duration;

/** Gateway -> Member 회원 존재 확인. 탈퇴 회원은 공개 경로에서 익명 처리하고 보호 경로에서 차단합니다. */
@Component
public class MemberExistenceFilter implements WebFilter, Ordered {
    private final MemberAuthClient member;

    public MemberExistenceFilter(MemberAuthClient member) {
        this.member = member;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var claims = GatewayAttributes.claims(exchange);
        if (claims == null) return chain.filter(exchange);
        boolean publicPath = PathRules.isPublic(exchange.getRequest().getMethod(), exchange.getRequest().getPath().value());
        return member.exists(claims.sub()).timeout(Duration.ofSeconds(2))
                .map(exists -> exists ? 1 : 0)
                .defaultIfEmpty(-1)
                .onErrorReturn(-1)
                .flatMap(status -> {
                    if (status == 1) return chain.filter(exchange);
                    exchange.getAttributes().remove(GatewayAttributes.CLAIMS);
                    if (publicPath) return chain.filter(exchange);
                    return ErrorResponses.write(exchange, status == 0
                            ? AuthErrorCode.TOKEN_REVOKED : CommonErrorCode.SERVICE_UNAVAILABLE);
                });
    }

    @Override
    public int getOrder() {
        return GatewayFilterOrder.MEMBER_EXISTENCE;
    }
}
