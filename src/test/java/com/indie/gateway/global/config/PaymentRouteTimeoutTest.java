package com.indie.gateway.global.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreakerFactory;
import org.springframework.cloud.gateway.config.HttpClientProperties;
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
 * 결제 경로 30초 - 굿즈 승인, 취소는 Payment 가 토스를 최악 26초 기다립니다 (기본 5초면 Gateway 가 먼저 504).
 * 하위 연결 풀은 톰캣 keep-alive(60초)보다 먼저 쉬는 연결을 버립니다.
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
class PaymentRouteTimeoutTest {

    @Autowired RoutePredicateHandlerMapping handlerMapping;
    @Autowired ReactiveCircuitBreakerFactory<?, ?> circuitBreakers;
    @Autowired HttpClientProperties httpClient;

    @Test
    void confirmAndCancelWaitPastTossWorstCase() {
        for (String path : new String[]{"/api/payments/confirm", "/api/payments/7/cancel"}) {
            Route route = routeOf(HttpMethod.POST, path);
            assertThat(route.getId()).as(path).isEqualTo("payment");
            assertThat(route.getMetadata()).as(path).containsEntry(RESPONSE_TIMEOUT_ATTR, 30_000);
        }
    }

    /** TimeLimiter 가 기본 5초로 먼저 끊지 않는지 - 6초 걸려도 통과 */
    @Test
    void paymentTimeLimiterDoesNotCutAt5s() {
        String result = circuitBreakers.create("payment")
                .run(Mono.delay(Duration.ofSeconds(6)).thenReturn("ok"), t -> Mono.just("fallback"))
                .block(Duration.ofSeconds(15));
        assertThat(result).isEqualTo("ok");
    }

    @Test
    void upstreamPoolDropsIdleConnectionsBeforeTomcatKeepAlive() {
        assertThat(httpClient.getPool().getMaxIdleTime()).isEqualTo(Duration.ofSeconds(30));
    }

    private Route routeOf(HttpMethod method, String path) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.method(method, path));
        handlerMapping.getHandler(exchange).block(Duration.ofSeconds(5));
        return exchange.getAttribute(GATEWAY_ROUTE_ATTR);
    }
}
