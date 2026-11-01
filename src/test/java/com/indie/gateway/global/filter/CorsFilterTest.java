package com.indie.gateway.global.filter;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.config.GlobalCorsProperties;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.cors.CorsConfiguration;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/** 프론트(다른 출처)가 쿠키 + X-XSRF-TOKEN 으로 Gateway 자체 엔드포인트를 부를 수 있어야 합니다 */
class CorsFilterTest {

    private static final String FRONT = "http://localhost:5173";
    private static final String API = "http://localhost:8080";   // 실제 요청은 항상 절대 URI — 상대 경로면 출처 비교가 실패합니다

    private final CorsFilter filter = new CorsFilter(props());

    /** ★ 프리플라이트는 토큰 검사(②)까지 가지 않고 여기서 200 — 쿠키를 실어도 된다(credentials) · CSRF 헤더 허용 */
    @Test
    void preflightIsAnsweredBeforeTokenCheck() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.options(API + "/api/auth/signup")
                .header("Origin", FRONT).header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type,x-xsrf-token"));
        AtomicBoolean next = new AtomicBoolean();

        filter.filter(exchange, e -> { next.set(true); return Mono.empty(); }).block();

        assertThat(next).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange.getResponse().getHeaders().getAccessControlAllowOrigin()).isEqualTo(FRONT);
        assertThat(exchange.getResponse().getHeaders().getAccessControlAllowCredentials()).isTrue();
        assertThat(exchange.getResponse().getHeaders().getAccessControlAllowHeaders()).contains("x-xsrf-token");
    }

    /** 실제 요청은 다음 필터로 — 401 같은 거절 응답에도 헤더가 남아 프론트가 읽을 수 있음 */
    @Test
    void actualRequestGetsHeadersAndContinues() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get(API + "/api/auth/me").header("Origin", FRONT));
        AtomicBoolean next = new AtomicBoolean();

        filter.filter(exchange, e -> { next.set(true); return Mono.empty(); }).block();

        assertThat(next).isTrue();
        assertThat(exchange.getResponse().getHeaders().getAccessControlAllowOrigin()).isEqualTo(FRONT);
    }

    @Test
    void otherOriginIsRejected() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.options(API + "/api/auth/signup")
                .header("Origin", "https://evil.example").header("Access-Control-Request-Method", "POST"));
        AtomicBoolean next = new AtomicBoolean();

        filter.filter(exchange, e -> { next.set(true); return Mono.empty(); }).block();

        assertThat(next).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange.getResponse().getHeaders().getAccessControlAllowOrigin()).isNull();
    }

    /** CORS 는 토큰 검사보다 먼저 */
    @Test
    void runsBeforeAccessToken() {
        assertThat(GatewayFilterOrder.CORS).isLessThan(GatewayFilterOrder.ACCESS_TOKEN).isLessThan(GatewayFilterOrder.CSRF);
    }

    /** application.yml 의 globalcors 와 같은 모양 */
    private static GlobalCorsProperties props() {
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowedOrigins(List.of(FRONT));
        c.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        c.setAllowedHeaders(List.of("*"));
        c.setAllowCredentials(true);
        GlobalCorsProperties props = new GlobalCorsProperties();
        props.getCorsConfigurations().put("/**", c);
        return props;
    }
}
