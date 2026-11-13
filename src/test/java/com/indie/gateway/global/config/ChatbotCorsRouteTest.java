package com.indie.gateway.global.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.handler.RoutePredicateHandlerMapping;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

/**
 * 챗봇(FastAPI)은 자체 CORS 미들웨어가 Access-Control-Allow-Credentials · -Origin 을 붙입니다.
 * Gateway 의 CorsFilter 도 같은 헤더를 붙이므로, 겹친 값("true, true")이 나가면 브라우저가 응답을 막습니다.
 * application.yml 의 default-filters 는 Java 로 만든 라우트에 적용되지 않아 라우트에 직접 겁니다.
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
class ChatbotCorsRouteTest {

    private static final String ORIGIN = "http://localhost:5173";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Autowired RoutePredicateHandlerMapping handlerMapping;

    @Test
    void downstreamCorsHeadersAreNotDuplicated() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/chatbot/chat").header(HttpHeaders.ORIGIN, ORIGIN));
        handlerMapping.getHandler(exchange).block(TIMEOUT);
        Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
        assertThat(route.getId()).isEqualTo("chatbot");

        // Gateway CorsFilter 가 먼저 붙인 헤더
        HttpHeaders headers = exchange.getResponse().getHeaders();
        headers.add(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN);
        headers.add(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");

        // 챗봇 응답 헤더가 뒤에 덧붙는 상황 (NettyRoutingFilter 는 addAll)
        GatewayFilterChain chain = ex -> {
            ex.getResponse().getHeaders().add(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN);
            ex.getResponse().getHeaders().add(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
            return Mono.empty();
        };
        List<GatewayFilter> filters = route.getFilters();
        for (int i = filters.size() - 1; i >= 0; i--) {
            GatewayFilter filter = filters.get(i);
            GatewayFilterChain next = chain;
            chain = ex -> filter.filter(ex, next);
        }
        chain.filter(exchange).block(TIMEOUT);

        assertThat(headers.get(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)).containsExactly("true");
        assertThat(headers.get(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).containsExactly(ORIGIN);
    }
}
