package com.indie.gateway.global.filter;

import org.springframework.cloud.gateway.config.GlobalCorsProperties;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * ⓪'' CORS — 프론트(https://&lt;도메인&gt;)와 API(https://api.&lt;도메인&gt;)는 출처가 달라, 브라우저가 X-XSRF-TOKEN 을 붙인
 * POST 전에 프리플라이트(OPTIONS)를 보냅니다.
 * 🔴 spring.cloud.gateway.globalcors 는 라우팅 단계에서만 적용되어 ① 프리플라이트가 쿠키 없이 ② 에서 401 이 되고
 * ② Gateway 자체 엔드포인트(/api/auth/**)의 응답에는 헤더가 아예 붙지 않습니다. 그래서 같은 설정으로 토큰 · CSRF 검사보다 먼저 처리합니다.
 * 응답에 헤더가 이미 있으면 라우팅 단계의 CORS 처리는 건너뜁니다 (중복 없음).
 */
@Component
public class CorsFilter implements WebFilter, Ordered {

    private final CorsWebFilter delegate;

    public CorsFilter(GlobalCorsProperties props) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        props.getCorsConfigurations().forEach(source::registerCorsConfiguration);
        this.delegate = new CorsWebFilter(source);
    }

    /** 프리플라이트는 여기서 응답하고 끝 (허용 안 된 출처는 403) · 실제 요청은 헤더만 붙이고 다음 필터로 */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return delegate.filter(exchange, chain);
    }

    @Override
    public int getOrder() {
        return GatewayFilterOrder.CORS;
    }
}
