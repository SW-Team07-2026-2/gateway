package com.indie.gateway.global.filter;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Set;

/**
 * ① 클라이언트가 보낸 신뢰 헤더 제거 — 🔴 반드시 ② AccessTokenFilter 보다 먼저.
 * 뒤바뀌면 공개 경로에서 클라이언트의 X-User-Id 가 그대로 하위 서비스에 전달되어 사칭이 됩니다.
 * 차단하지 않고 제거만 합니다 (정상 브라우저의 실수 · 공격자에게 탐지 신호를 주지 않음).
 * CF-Connecting-IP 는 제거 대상이 아닙니다 — ALB SG 가 Cloudflare 대역만 받습니다.
 */
@Component
public class ClientHeaderStripFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(ClientHeaderStripFilter.class);

    static final Set<String> STRIP = Set.of(
            "X-User-Id", "X-User-Roles", "X-Internal-Token", "X-Entry-Token-Verified", "X-Reentry-Depth");

    private final MeterRegistry meterRegistry;

    public ClientHeaderStripFilter(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        HttpHeaders headers = exchange.getRequest().getHeaders();
        List<String> present = STRIP.stream().filter(headers::containsKey).toList();
        if (present.isEmpty()) {
            return chain.filter(exchange);
        }
        present.forEach(h -> {
            log.warn("stripped_trusted_header header={}", h);
            meterRegistry.counter("gateway.stripped.header", "header", h).increment();
        });
        ServerWebExchange mutated = exchange.mutate()
                .request(r -> r.headers(h -> present.forEach(h::remove)))
                .build();
        return chain.filter(mutated);
    }

    @Override
    public int getOrder() {
        return GatewayFilterOrder.CLIENT_HEADER_STRIP;
    }
}
