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
 * 프론트 -> Gateway 요청의 CORS 헤더와 프리플라이트를 처리합니다.
 * Gateway 자체 API에도 적용되도록 토큰 검사와 CSRF 검사보다 먼저 실행합니다.
 */
@Component
public class CorsFilter implements WebFilter, Ordered {

    private final CorsWebFilter delegate;

    public CorsFilter(GlobalCorsProperties props) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        props.getCorsConfigurations().forEach(source::registerCorsConfiguration);
        this.delegate = new CorsWebFilter(source);
    }

    /** 프리플라이트는 여기서 응답하고 끝 (허용 안 된 출처는 403), 실제 요청은 헤더만 붙이고 다음 필터로 */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return delegate.filter(exchange, chain);
    }

    @Override
    public int getOrder() {
        return GatewayFilterOrder.CORS;
    }
}
