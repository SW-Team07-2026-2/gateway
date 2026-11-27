package com.indie.gateway.global.filter;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

class AccessTokenFilterTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AccessTokenFilter filter = new AccessTokenFilter(null, registry);   // 쿠키 없음 -> 검증기를 부르지 않음

    private int status(String path) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path).build());
        filter.filter(exchange, e -> { e.getResponse().setRawStatusCode(200); return Mono.empty(); }).block();
        return exchange.getResponse().getStatusCode().value();
    }

    private double missingCount() {
        var counter = registry.find("gateway.auth.failure").tag("reason", "missing").counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void anonymousAuthMeIs401ButNotCountedAsFailure() {
        assertThat(status("/api/auth/me")).isEqualTo(401);
        assertThat(missingCount()).isZero();
    }

    @Test
    void anonymousProtectedPathIsCountedAsFailure() {
        assertThat(status("/api/members/me")).isEqualTo(401);
        assertThat(missingCount()).isEqualTo(1);
    }
}
