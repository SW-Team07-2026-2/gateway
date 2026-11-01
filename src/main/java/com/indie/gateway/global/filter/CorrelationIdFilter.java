package com.indie.gateway.global.filter;

import com.indie.gateway.global.common.util.GatewayAttributes;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * ⓪' X-Correlation-Id — 요청 헤더 · 응답 헤더 · Reactor Context(→ MDC) 에 올립니다.
 * 클라이언트 값은 길이 64 이하 · [A-Za-z0-9-] 만 통과 (로그 인젝션 방지).
 */
@Component
public class CorrelationIdFilter implements WebFilter, Ordered {

    public static final String HEADER = "X-Correlation-Id";
    public static final String CONTEXT_KEY = "correlationId";
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9-]{1,64}");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String incoming = exchange.getRequest().getHeaders().getFirst(HEADER);
        String id = incoming != null && VALID.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString();

        ServerWebExchange mutated = exchange.mutate()
                .request(r -> r.headers(h -> h.set(HEADER, id)))
                .build();
        mutated.getAttributes().put(GatewayAttributes.CORRELATION_ID, id);
        mutated.getResponse().getHeaders().set(HEADER, id);
        return chain.filter(mutated).contextWrite(Context.of(CONTEXT_KEY, id));
    }

    @Override
    public int getOrder() {
        return GatewayFilterOrder.CORRELATION_ID;
    }
}
