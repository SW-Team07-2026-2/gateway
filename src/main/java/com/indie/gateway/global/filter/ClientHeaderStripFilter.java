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
 * 토큰 검증 전에 클라이언트가 보낸 사용자 식별 헤더를 제거해 사칭을 막습니다.
 * CF-Connecting-IP는 인프라에서 출처를 제한하므로 유지합니다.
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
