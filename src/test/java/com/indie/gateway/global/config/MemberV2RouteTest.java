package com.indie.gateway.global.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.handler.RoutePredicateHandlerMapping;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

/**
 * member v2 (member-service docs/v2_member_service_re.md §8 Gateway) — 장바구니는 Catalog 로, 새 회원 API 는 기존 member 라우트로,
 * AI 의 회원 데이터 조회 · 저장은 서명 경로(/ingest/members/**)로만 갑니다.
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
class MemberV2RouteTest {

    @Autowired RoutePredicateHandlerMapping handlerMapping;

    @Test
    void cartGoesToCatalog() {
        for (HttpMethod method : new HttpMethod[]{HttpMethod.GET, HttpMethod.POST, HttpMethod.PATCH, HttpMethod.DELETE}) {
            for (String path : new String[]{"/api/cart", "/api/cart/items", "/api/cart/items/7"}) {
                Route route = routeOf(method, path);
                assertThat(route.getId()).as(method + " " + path).isEqualTo("cart");
                assertThat(route.getUri().toString()).as(method + " " + path).isEqualTo("lb://catalog");
            }
        }
        assertThat(routeOf(HttpMethod.GET, "/api/cartoon")).isNull();
    }

    @Test
    void newMemberApisStayOnMemberRoute() {
        assertThat(routeOf(HttpMethod.DELETE, "/api/members/me").getId()).isEqualTo("member");
        assertThat(routeOf(HttpMethod.POST, "/api/members/me/metrics").getId()).isEqualTo("member");
        assertThat(routeOf(HttpMethod.POST, "/api/members/notifications/7/read").getId()).isEqualTo("member");
        assertThat(routeOf(HttpMethod.DELETE, "/api/members/collections/7").getId()).isEqualTo("member");
    }

    @Test
    void memberDataIsReachableOnlyThroughSignedRoutes() {
        assertSigned(HttpMethod.GET, "/ingest/members/42/metrics", "ingest-member_metrics");
        assertSigned(HttpMethod.GET, "/ingest/members/42/embedding", "ingest-member_embedding");
        assertSigned(HttpMethod.PUT, "/ingest/members/42/embedding", "ingest-member_embedding_save");
        // 매핑에 없는 메서드 · 경로, 내부 경로 그대로는 라우트가 없습니다 (404)
        assertThat(routeOf(HttpMethod.POST, "/ingest/members/42/metrics")).isNull();
        assertThat(routeOf(HttpMethod.DELETE, "/ingest/members/42/embedding")).isNull();
        assertThat(routeOf(HttpMethod.GET, "/ingest/members/42/profile")).isNull();
        assertThat(routeOf(HttpMethod.GET, "/internal/members/42/metrics")).isNull();
        assertThat(routeOf(HttpMethod.GET, "/api/members/42/metrics").getId()).isEqualTo("member");   // 사용자 경로 — Member 에 그런 API 가 없어 404
    }

    private void assertSigned(HttpMethod method, String path, String routeId) {
        Route route = routeOf(method, path);
        assertThat(route).as(method + " " + path).isNotNull();
        assertThat(route.getId()).as(method + " " + path).isEqualTo(routeId);
        assertThat(route.getUri().toString()).as(method + " " + path).isEqualTo("lb://member");
    }

    private Route routeOf(HttpMethod method, String path) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.method(method, path));
        handlerMapping.getHandler(exchange).block(Duration.ofSeconds(5));
        return exchange.getAttribute(GATEWAY_ROUTE_ATTR);
    }
}
