package com.indie.gateway.global.common.util;

import com.indie.gateway.domain.auth.entity.TokenClaims;
import org.springframework.web.server.ServerWebExchange;

/** exchange attribute 키와 접근자 - 필터 사이에서 주고받는 값 */
public final class GatewayAttributes {

    public static final String CLAIMS = "indie.claims";
    public static final String CORRELATION_ID = "indie.correlationId";

    /**
     * 요청에 저장된 검증된 토큰 클레임을 반환합니다.
     *
     * @param exchange 현재 HTTP 요청과 응답
     */
    public static TokenClaims claims(ServerWebExchange exchange) {
        return exchange.getAttribute(CLAIMS);
    }

    /**
     * 요청 추적 ID를 반환합니다.
     *
     * @param exchange 현재 HTTP 요청과 응답
     */
    public static String correlationId(ServerWebExchange exchange) {
        return exchange.getAttribute(CORRELATION_ID);
    }

    private GatewayAttributes() {}
}
