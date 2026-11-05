package com.indie.gateway.global.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreakerFactory;
import org.springframework.cloud.gateway.handler.RoutePredicateHandlerMapping;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.cloud.gateway.support.RouteMetadataUtils.RESPONSE_TIMEOUT_ATTR;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

/**
 * 75-5 — 좌석 선점(N10) · 결제 승인(N16)만 응답 타임아웃 10초 (docs/30 R8).
 * 나머지 /api/concerts/** 는 기본 5초 · GET 재시도 그대로인 concert 라우트로 갑니다.
 */
@SpringBootTest(properties = {
        "spring.cloud.kubernetes.enabled=false",
        "spring.cloud.kubernetes.discovery.enabled=false",
        "internal.shared-secret=0123456789abcdef0123456789abcdef-local",
        "ingest.keys.crawler-test=ingest-key-0123456789abcdef0123456789",
        "app.cookie.domain=",
        "app.cors.origins=http://localhost:5173",
        "app.ingest.hosts=localhost",
})
class ConcertBookingRouteTest {

    @Autowired RoutePredicateHandlerMapping handlerMapping;
    @Autowired ReactiveCircuitBreakerFactory<?, ?> circuitBreakers;

    @Test
    void holdAndConfirmGoToBookingRouteWith10s() {
        for (String path : new String[]{"/api/concerts/7/bookings", "/api/concerts/bookings/7/confirm"}) {
            Route route = routeOf(HttpMethod.POST, path);
            assertThat(route.getId()).as(path).isEqualTo("concert-booking");
            assertThat(route.getMetadata()).as(path).containsEntry(RESPONSE_TIMEOUT_ATTR, 10_000);
        }
    }

    /** 다른 메서드 · 다른 예매 경로는 기본 라우트 — 응답 타임아웃은 전역 5초 */
    @Test
    void otherConcertPathsStayOnDefaultRoute() {
        assertDefault(HttpMethod.GET, "/api/concerts/7/bookings");
        assertDefault(HttpMethod.GET, "/api/concerts/bookings/7");
        assertDefault(HttpMethod.POST, "/api/concerts/bookings/7/cancel");   // Payment 1번(최악 4초)이라 5초 안
        assertDefault(HttpMethod.GET, "/api/concerts/bookings/7/entry-qr");
    }

    /** TimeLimiter concert 11초 — 기본 5초였다면 응답 타임아웃(10초)보다 먼저 끊어 폴백이 됩니다 */
    @Test
    void concertTimeLimiterDoesNotCutAt5s() {
        String result = circuitBreakers.create("concert")
                .run(Mono.delay(Duration.ofSeconds(6)).thenReturn("ok"), t -> Mono.just("fallback"))
                .block(Duration.ofSeconds(15));
        assertThat(result).isEqualTo("ok");
    }

    private void assertDefault(HttpMethod method, String path) {
        Route route = routeOf(method, path);
        assertThat(route.getId()).as(method + " " + path).isEqualTo("concert");
        assertThat(route.getMetadata()).as(method + " " + path).doesNotContainKey(RESPONSE_TIMEOUT_ATTR);
    }

    private Route routeOf(HttpMethod method, String path) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.method(method, path));
        handlerMapping.getHandler(exchange).block(Duration.ofSeconds(5));
        return exchange.getAttribute(GATEWAY_ROUTE_ATTR);
    }
}
