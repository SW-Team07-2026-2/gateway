package com.indie.gateway.global.config;

import com.indie.gateway.domain.ingest.entity.IngestRoute;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.GatewayFilterSpec;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

import java.net.ConnectException;
import java.time.Duration;

import static org.springframework.cloud.gateway.support.RouteMetadataUtils.RESPONSE_TIMEOUT_ATTR;

/**
 * Gateway -> 각 서비스의 공개 API와 서명 요청 경로를 등록합니다.
 * 인증 API는 Gateway 컨트롤러가 처리하며, 등록되지 않은 경로는 404를 반환합니다.
 */
@Configuration
public class RouteConfig {

    /** 서킷 실패로 집계할 하위 응답은 500뿐입니다. 서비스가 의도한 502와 503은 그대로 전달합니다. */
    static final String UNHANDLED_ERROR = "500";

    @Bean
    public RouteLocator routes(RouteLocatorBuilder builder, GatewayProperties props) {
        RouteLocatorBuilder.Builder routes = builder.routes()
                .route("member", r -> r.path("/api/members", "/api/members/**")
                        .filters(f -> retryConnect(f.circuitBreaker(c -> c.setName("member").setFallbackUri("forward:/fallback/member")
                                .addStatusCode(UNHANDLED_ERROR)), HttpMethod.GET))
                        .uri("lb://member"))
                .route("catalog", r -> r.path("/api/catalog", "/api/catalog/**")
                        .filters(f -> retryConnect(f.circuitBreaker(c -> c.setName("catalog").setFallbackUri("forward:/fallback/catalog")
                                .addStatusCode(UNHANDLED_ERROR)), HttpMethod.GET))
                        .uri("lb://catalog"))
                // 장바구니 - Member 위시리스트를 없애고 Catalog 의 cart_item 으로 옮겼습니다. 공개 경로가 아니라 로그인 필수 (PathRules)
                .route("cart", r -> r.path("/api/cart", "/api/cart/**")
                        .filters(f -> retryConnect(f.circuitBreaker(c -> c.setName("catalog").setFallbackUri("forward:/fallback/catalog")
                                .addStatusCode(UNHANDLED_ERROR)), HttpMethod.GET))
                        .uri("lb://catalog"))
                // 75-5 - 좌석 선점(N10), 결제 승인(N16)은 Concert 가 Payment 를 두 번 부를 수 있어 최악 8초 (docs/30 R8).
                // 이 두 경로만 응답 타임아웃 10초, POST 라 재시도 없음. 나머지 /api/concerts/** 는 기본 5초 그대로
                // 서킷도 따로 (concert-booking, TimeLimiter 11초) - 결제가 느려져도 공연 조회 서킷은 열리지 않고, 조회는 5초에 끊깁니다
                .route("concert-booking", r -> r.path("/api/concerts/*/bookings", "/api/concerts/bookings/*/confirm").and().method(HttpMethod.POST)
                        .filters(f -> f.circuitBreaker(c -> c.setName("concert-booking").setFallbackUri("forward:/fallback/concert")
                                .addStatusCode(UNHANDLED_ERROR)))
                        .metadata(RESPONSE_TIMEOUT_ATTR, 10_000)
                        .uri("lb://concert"))
                .route("concert", r -> r.path("/api/concerts", "/api/concerts/**")
                        .filters(f -> retryConnect(f.circuitBreaker(c -> c.setName("concert").setFallbackUri("forward:/fallback/concert")
                                .addStatusCode(UNHANDLED_ERROR)), HttpMethod.GET))
                        .uri("lb://concert"))
                // 결제 - 재시도 0, 폴백 없음. 조용히 실패하는 게 중복 승인보다 낫습니다
                // 응답 타임아웃 30초 - 굿즈 승인(P1), 취소는 Payment 가 토스를 승인 + 조회로 최악 26초 기다립니다 (payment read-timeout-ms).
                // 기본 5초면 Gateway 가 먼저 504 를 내고 결제는 뒤에서 끝납니다. 티켓 승인은 이 경로가 아니라 concert-booking (10초)
                .route("payment", r -> r.path("/api/payments", "/api/payments/**")
                        .filters(f -> f.circuitBreaker(c -> c.setName("payment").addStatusCode(UNHANDLED_ERROR)))   // 500 은 GatewayErrorHandler 가 같은 500 으로
                        .metadata(RESPONSE_TIMEOUT_ATTR, 30_000)
                        .uri("lb://payment"))
                // 챗봇 - 재시도 0, 타임아웃 30s, 호출 한도 분당 5 (RateLimitFilter)
                // 챗봇(FastAPI)도 CORS 헤더를 붙여 CorsFilter 의 값과 겹칩니다("true, true" -> 브라우저가 막음) - 하나만 남깁니다.
                // application.yml 의 default-filters 는 여기처럼 Java 로 만든 라우트에는 적용되지 않습니다
                .route("chatbot", r -> r.path("/api/chatbot", "/api/chatbot/**")
                        .filters(f -> f.dedupeResponseHeader("Access-Control-Allow-Origin Access-Control-Allow-Credentials", "RETAIN_UNIQUE")
                                .circuitBreaker(c -> c.setName("chatbot").setFallbackUri("forward:/fallback/chatbot").addStatusCode(UNHANDLED_ERROR)))
                        .metadata(RESPONSE_TIMEOUT_ATTR, 30_000)
                        .uri("lb://chatbot-service"));
        // 결정 56B, 57A - 관리자(Studio, 관리자 AI, GPU 무대 작업)는 KT 의 admin.<도메인> 으로 따로 들어갑니다.
        // /api/studio/**, /api/admin-ai/**, /api/stage-jobs/** 는 여기 없으니 404 입니다 (docs/20 §18-1, §18-2)
        // 서명 경로 (docs/22) - 서명, 재전송, 봉투 검사는 IngestFilter 가 끝낸 뒤입니다. 경로는 IngestRoute 고정 매핑만
        // (수집 + AI 가 쓰는 회원 데이터 /ingest/members/{userId}/** - SetPath 가 {userId} 를 그대로 옮깁니다)
        // 하위 서비스가 받은 뒤의 실패(5xx, 지연)는 재시도하지 않습니다 - 호출한 쪽이 새 서명으로 다시 보냅니다 (upsert 멱등, S6)
        for (IngestRoute ingest : IngestRoute.values()) {
            routes.route("ingest-" + ingest.name().toLowerCase(), r -> r.path(ingest.path()).and().method(ingest.method())
                    .filters(f -> retryConnect(f.setPath(ingest.internalPath())
                            .circuitBreaker(c -> c.setName(ingest.circuitBreaker()).setFallbackUri("forward:/fallback/ingest")), ingest.method()))
                    .metadata(RESPONSE_TIMEOUT_ATTR, props.ingest().responseTimeout().toMillis())
                    .uri("lb://" + ingest.service()));
        }
        return routes.build();
    }

    /** 연결 실패만 서킷 안쪽에서 1회 재시도합니다. 사용자 요청은 GET만 허용하고 서명 경로는 지정된 메서드를 사용합니다. */
    private static GatewayFilterSpec retryConnect(GatewayFilterSpec f, HttpMethod method) {
        return f.retry(c -> c.setRetries(1)
                .setMethods(method)
                .setSeries()
                .setExceptions(ConnectException.class)
                .setBackoff(Duration.ofMillis(200), Duration.ofSeconds(1), 2, false));
    }
}
