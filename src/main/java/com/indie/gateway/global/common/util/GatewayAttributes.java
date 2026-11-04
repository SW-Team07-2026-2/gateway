package com.indie.gateway.global.common.util;

import com.indie.gateway.domain.auth.entity.TokenClaims;
import org.springframework.web.server.ServerWebExchange;

/** exchange attribute 키와 접근자 — 필터 사이에서 주고받는 값 */
public final class GatewayAttributes {

    public static final String CLAIMS = "indie.claims";
    public static final String CORRELATION_ID = "indie.correlationId";

    public static TokenClaims claims(ServerWebExchange exchange) {
        return exchange.getAttribute(CLAIMS);
    }

    public static String correlationId(ServerWebExchange exchange) {
        return exchange.getAttribute(CORRELATION_ID);
    }

    private GatewayAttributes() {}
}
