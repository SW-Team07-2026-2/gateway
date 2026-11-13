package com.indie.gateway.global.config;

import com.indie.gateway.domain.ingest.entity.IngestRoute;
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
                // 장바구니 — Member 위시리스트를 없애고 Catalog 의 cart_item 으로 옮겼습니다. 공개 경로가 아니라 로그인 필수 (PathRules)
                .route("cart", r -> r.path("/api/cart", "/api/cart/**")
                        .filters(f -> retryGet(f).circuitBreaker(c -> c.setName("catalog").setFallbackUri("forward:/fallback/catalog")))
                        .uri("lb://catalog"))
                // ✏️ 11/04 75-5 — 좌석 선점(N10) · 결제 승인(N16)은 Concert 가 Payment 를 두 번 부를 수 있어 최악 8초 (docs/30 R8).
                //   이 두 경로만 응답 타임아웃 10초 · POST 라 재시도 없음. 나머지 /api/concerts/** 는 기본 5초 그대로 (GET 재시도 2회 × 5초)
                .route("concert-booking", r -> r.path("/api/concerts/*/bookings", "/api/concerts/bookings/*/confirm").and().method(HttpMethod.POST)
                        .filters(f -> f.circuitBreaker(c -> c.setName("concert").setFallbackUri("forward:/fallback/concert")))
                        .metadata(RESPONSE_TIMEOUT_ATTR, 10_000)
                        .uri("lb://concert"))
                .route("concert", r -> r.path("/api/concerts", "/api/concerts/**")
                        .filters(f -> retryGet(f).circuitBreaker(c -> c.setName("concert").setFallbackUri("forward:/fallback/concert")))
                        .uri("lb://concert"))
                // 결제 — 🔴 재시도 0 · 폴백 없음. 조용히 실패하는 게 중복 승인보다 낫습니다
                .route("payment", r -> r.path("/api/payments", "/api/payments/**")
                        .filters(f -> f.circuitBreaker(c -> c.setName("payment")))
                        .uri("lb://payment"))
                // 챗봇 — 🔴 재시도 0 · 타임아웃 30s · 호출 한도 분당 5 (RateLimitFilter)
                //   챗봇(FastAPI)도 CORS 헤더를 붙여 CorsFilter 의 값과 겹칩니다("true, true" → 브라우저가 막음) — 하나만 남깁니다.
                //   application.yml 의 default-filters 는 여기처럼 Java 로 만든 라우트에는 적용되지 않습니다
                .route("chatbot", r -> r.path("/api/chatbot", "/api/chatbot/**")
                        .filters(f -> f.dedupeResponseHeader("Access-Control-Allow-Origin Access-Control-Allow-Credentials", "RETAIN_UNIQUE")
                                .circuitBreaker(c -> c.setName("chatbot").setFallbackUri("forward:/fallback/chatbot")))
                        .metadata(RESPONSE_TIMEOUT_ATTR, 30_000)
                        .uri("lb://chatbot-service"));
        // ✏️ 11/04 결정 56B · 57A — 관리자(Studio · 관리자 AI · GPU 무대 작업)는 KT 의 admin.<도메인> 으로 따로 들어갑니다.
        //   /api/studio/** · /api/admin-ai/** · /api/stage-jobs/** 는 여기 없으니 404 입니다 (docs/20 §18-1 · §18-2)
        // 서명 경로 (docs/22) — 서명 · 재전송 · 봉투 검사는 IngestFilter 가 끝낸 뒤입니다. 경로는 IngestRoute 고정 매핑만
        //   (수집 + AI 가 쓰는 회원 데이터 /ingest/members/{userId}/** — SetPath 가 {userId} 를 그대로 옮깁니다)
        // 🔴 하위 서비스가 받은 뒤의 실패(5xx · 지연)는 재시도하지 않습니다 — 호출한 쪽이 새 서명으로 다시 보냅니다 (upsert 멱등 · S6)
        for (IngestRoute ingest : IngestRoute.values()) {
            routes.route("ingest-" + ingest.name().toLowerCase(), r -> r.path(ingest.path()).and().method(ingest.method())
                    .filters(f -> retryConnect(f.setPath(ingest.internalPath())
                            .circuitBreaker(c -> c.setName(ingest.circuitBreaker()).setFallbackUri("forward:/fallback/ingest")), ingest.method()))
                    .metadata(RESPONSE_TIMEOUT_ATTR, props.ingest().responseTimeout().toMillis())
                    .uri("lb://" + ingest.service()));
        }
        return routes.build();
    }

    /**
     * 서명 경로 — 연결 자체가 안 된 경우(요청이 안 나감)만 1회. 롤링 배포 중 내려가는 Pod 에 걸렸을 때 다른 Pod 로 갑니다.
     * 🔴 Circuit Breaker <b>뒤에</b> 붙여야 안쪽에서 돌아 폴백(503)으로 바뀌기 전의 ConnectException 을 봅니다.
     */
    private static GatewayFilterSpec retryConnect(GatewayFilterSpec f, HttpMethod method) {
        return f.retry(c -> c.setRetries(1)
                .setMethods(method)
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
}
