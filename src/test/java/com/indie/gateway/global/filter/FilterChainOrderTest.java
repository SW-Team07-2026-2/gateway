package com.indie.gateway.global.filter;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class FilterChainOrderTest {

    /** 🔴 ① 헤더 제거가 ② 토큰 검증보다 먼저 — 뒤바뀌면 공개 경로에서 X-User-Id 사칭 */
    @Test
    void stripRunsBeforeTokenVerificationAndInjection() {
        assertThat(GatewayFilterOrder.CLIENT_HEADER_STRIP).isLessThan(GatewayFilterOrder.ACCESS_TOKEN);
        assertThat(new int[]{GatewayFilterOrder.REQUEST_SANITY, GatewayFilterOrder.CORRELATION_ID,
                GatewayFilterOrder.CLIENT_HEADER_STRIP, GatewayFilterOrder.INGEST, GatewayFilterOrder.ACCESS_TOKEN,
                GatewayFilterOrder.PREEMPTIVE_REFRESH, GatewayFilterOrder.BLACKLIST, GatewayFilterOrder.CSRF,
                GatewayFilterOrder.RATE_LIMIT, GatewayFilterOrder.USER_HEADER_INJECT}).isSorted();
    }

    /** DoD 1 — X-User-Id: 999 를 직접 보낸 요청 → 하위로 전달되는 헤더에 999 가 없음 */
    @Test
    void clientSuppliedTrustedHeadersAreRemoved() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/catalog/albums")
                .header("X-User-Id", "999").header("X-User-Roles", "ADMIN").header("X-Internal-Token", "x"));
        AtomicReference<ServerWebExchange> passed = new AtomicReference<>();

        new ClientHeaderStripFilter(new SimpleMeterRegistry())
                .filter(exchange, e -> { passed.set(e); return Mono.empty(); }).block();

        assertThat(passed.get().getRequest().getHeaders())
                .doesNotContainKeys("X-User-Id", "X-User-Roles", "X-Internal-Token");
    }
}
