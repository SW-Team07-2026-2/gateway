package com.indie.gateway.global.config;

import com.indie.gateway.domain.auth.entity.TokenClaims;
import com.indie.gateway.domain.ingest.entity.IngestRoute;
import com.indie.gateway.global.common.exception.CommonErrorCode;
import com.indie.gateway.global.common.response.ErrorResponses;
import com.indie.gateway.global.common.util.GatewayAttributes;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.GatewayFilterSpec;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.net.ConnectException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

import static org.springframework.cloud.gateway.support.RouteMetadataUtils.RESPONSE_TIMEOUT_ATTR;

/**
 * 라우트 (§6). 여기 없는 경로는 전부 404 — /internal/** · /api/ai/** 포함 (기본 거부).
 * /api/auth/** 는 Gateway 자체 컨트롤러입니다. 🔴 Member 로 라우팅하지 않습니다 (쿠키 정책이 두 곳에 생김).
 * lb://<이름> 의 이름은 k8s Service 이름과 같아야 합니다.
 */
@Configuration
public class RouteConfig {

    @Bean
    public RouteLocator routes(RouteLocatorBuilder builder, GatewayProperties props) {
        RouteLocatorBuilder.Builder routes = builder.routes()
                .route("member", r -> r.path("/api/members", "/api/members/**")
                        .filters(f -> retryGet(f).circuitBreaker(c -> c.setName("member").setFallbackUri("forward:/fallback/member")))
                        .uri("lb://member"))
                .route("catalog", r -> r.path("/api/catalog", "/api/catalog/**")
                        .filters(f -> retryGet(f).circuitBreaker(c -> c.setName("catalog").setFallbackUri("forward:/fallback/catalog")))
                        .uri("lb://catalog"))
                .route("concert", r -> r.path("/api/concerts", "/api/concerts/**")
                        .filters(f -> retryGet(f).circuitBreaker(c -> c.setName("concert").setFallbackUri("forward:/fallback/concert")))
                        .uri("lb://concert"))
                // 결제 — 🔴 재시도 0 · 폴백 없음. 조용히 실패하는 게 중복 승인보다 낫습니다
                .route("payment", r -> r.path("/api/payments", "/api/payments/**")
                        .filters(f -> f.circuitBreaker(c -> c.setName("payment")))
                        .uri("lb://payment"))
                // 챗봇 — 🔴 재시도 0 · 타임아웃 30s · 호출 한도 분당 5 (RateLimitFilter)
                .route("chatbot", r -> r.path("/api/chatbot", "/api/chatbot/**")
                        .filters(f -> f.circuitBreaker(c -> c.setName("chatbot").setFallbackUri("forward:/fallback/chatbot")))
                        .metadata(RESPONSE_TIMEOUT_ATTR, 30_000)
                        .uri("lb://chatbot-service"))
                // 관리자 AI (KT) — 🔴 재시도 0 · 타임아웃 60s · 호출 한도 분당 10 (RateLimitFilter)
                // TODO(결정대기): 관리자 전용 로그인(typ=admin · docs/22) 구현 전이라 Studio 와 같이 ADMIN 역할만 봅니다
                .route("admin-ai", r -> r.path("/api/admin-ai", "/api/admin-ai/**")
                        .filters(f -> f.filter(adminOnly())
                                .circuitBreaker(c -> c.setName("admin-ai").setFallbackUri("forward:/fallback/admin-ai")))
                        .metadata(RESPONSE_TIMEOUT_ATTR, 60_000)
                        .uri("lb://ai-service"))
                // TODO(결정대기): §18-2 Studio 호출 경로 — 기본값 /api/studio/** · ADMIN 만
                .route("studio", r -> r.path("/api/studio", "/api/studio/**")
                        .filters(f -> f.filter(adminOnly())
                                .circuitBreaker(c -> c.setName("studio").setFallbackUri("forward:/fallback/studio")))
                        .uri("lb://studio"))
                // TODO(결정대기): §18-1 무대 작업(GPU) 진입 경로 — 기본값 공개 라우트. Studio 가 GPU 전용 토큰을 검증해야 합니다
                .route("stage-jobs", r -> r.path("/api/stage-jobs", "/api/stage-jobs/**")
                        .filters(f -> f.circuitBreaker(c -> c.setName("studio").setFallbackUri("forward:/fallback/studio")))
                        .uri("lb://studio"));
        // 수집 (docs/22) — 서명 · 재전송 · 봉투 검사는 IngestFilter 가 끝낸 뒤입니다. 경로는 IngestRoute 고정 매핑만
        // 🔴 하위 서비스가 받은 뒤의 실패(5xx · 지연)는 재시도하지 않습니다 — 수집기가 새 서명으로 다시 보냅니다 (upsert 멱등 · S6)
        for (IngestRoute ingest : IngestRoute.values()) {
            routes.route("ingest-" + ingest.name().toLowerCase(), r -> r.path(ingest.path()).and().method(HttpMethod.POST)
                    .filters(f -> retryConnect(f.setPath(ingest.internalPath())
                            .circuitBreaker(c -> c.setName(ingest.circuitBreaker()).setFallbackUri("forward:/fallback/ingest"))))
                    .metadata(RESPONSE_TIMEOUT_ATTR, props.ingest().responseTimeout().toMillis())
                    .uri("lb://" + ingest.service()));
        }
        return routes.build();
    }

    /**
     * 수집 — 연결 자체가 안 된 경우(요청이 안 나감)만 1회. 롤링 배포 중 내려가는 Pod 에 걸렸을 때 다른 Pod 로 갑니다.
     * 🔴 Circuit Breaker <b>뒤에</b> 붙여야 안쪽에서 돌아 폴백(503)으로 바뀌기 전의 ConnectException 을 봅니다.
     */
    private static GatewayFilterSpec retryConnect(GatewayFilterSpec f) {
        return f.retry(c -> c.setRetries(1)
                .setMethods(HttpMethod.POST)
                .setSeries()
                .setExceptions(ConnectException.class)
                .setBackoff(Duration.ofMillis(200), Duration.ofSeconds(1), 2, false));
    }

    /** GET 만 · 5xx · IOException · TimeoutException · 최대 2회. 🔴 POST 재시도는 중복 주문 · 결제를 만듭니다 */
    private static GatewayFilterSpec retryGet(GatewayFilterSpec f) {
        return f.retry(c -> c.setRetries(2)
                .setMethods(HttpMethod.GET)
                .setSeries(HttpStatus.Series.SERVER_ERROR)
                .setExceptions(IOException.class, TimeoutException.class));
    }

    private static GatewayFilter adminOnly() {
        return (exchange, chain) -> {
            TokenClaims claims = GatewayAttributes.claims(exchange);
            return claims != null && claims.hasRole("ADMIN")
                    ? chain.filter(exchange)
                    : ErrorResponses.write(exchange, CommonErrorCode.FORBIDDEN);
        };
    }
}
