package com.indie.gateway.global.config;

import com.indie.gateway.domain.ingest.repository.IngestNonceStore;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.loadbalancer.cache.LoadBalancerCacheProperties;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 사용자 경로 서킷 . 가짜 하위 서비스를 띄워 실제 Gateway 라우트로 보냅니다.
 * <pre>
 * 하위 500 -> 서킷 실패 1, 응답은 같은 500 INTERNAL_ERROR, 재시도 없음
 * 하위 503 (일부러) -> 본문 그대로 통과, 서킷 실패로 세지 않음
 * 연결 실패 -> 서킷 안쪽에서 다른 인스턴스로 1회 재시도
 * 서킷 열림 -> 폴백 503, 하위로 요청이 가지 않음
 * </pre>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.cloud.kubernetes.enabled=false",
        "spring.cloud.kubernetes.discovery.enabled=false",
        "internal.shared-secret=0123456789abcdef0123456789abcdef-local",
        "ingest.keys.crawler-test=ingest-key-0123456789abcdef0123456789",
        "app.cookie.domain=",
        "app.cors.origins=http://localhost:5173",
        "app.ingest.hosts=localhost",
        "app.sanity.allowed-hosts=localhost",
})
class CircuitBreakerRouteTest {

    static final String INTERNAL_ERROR = "{\"isSuccess\":false,\"code\":\"INTERNAL_ERROR\",\"message\":\"일시적인 오류가 발생했습니다\"}";
    static final String QUEUE_UNAVAILABLE = "{\"isSuccess\":false,\"code\":\"QUEUE_UNAVAILABLE\",\"message\":\"대기열을 잠시 사용할 수 없습니다\"}";
    static final String PG_PROCESSING = "{\"isSuccess\":false,\"code\":\"PG_PROCESSING\",\"message\":\"결제사가 아직 처리 중입니다\"}";

    static final Map<String, AtomicInteger> HITS = new ConcurrentHashMap<>();

    static final DisposableServer DOWNSTREAM = HttpServer.create().host("127.0.0.1").port(0)
            .route(routes -> routes
                    .get("/api/concerts/broken", (req, res) -> json(req.uri(), res.status(500), INTERNAL_ERROR))
                    .get("/api/concerts/queue", (req, res) -> json(req.uri(), res.status(503), QUEUE_UNAVAILABLE))
                    .get("/api/concerts/ok", (req, res) -> json(req.uri(), res, "{\"isSuccess\":true}"))
                    .get("/api/catalog/albums", (req, res) -> json(req.uri(), res, "{\"isSuccess\":true}"))
                    .post("/api/payments/webhook/broken", (req, res) -> json(req.uri(), res.status(500), INTERNAL_ERROR))
                    .post("/api/payments/webhook/processing", (req, res) -> json(req.uri(), res.status(503), PG_PROCESSING)))
            .bindNow();

    static final int CLOSED_PORT = IngestRoutingTest.closedPort();

    static Mono<Void> json(String uri, reactor.netty.http.server.HttpServerResponse res, String body) {
        HITS.computeIfAbsent(uri, k -> new AtomicInteger()).incrementAndGet();
        return res.header("Content-Type", "application/json").sendString(Mono.just(body)).then();
    }

    @DynamicPropertySource
    static void instances(DynamicPropertyRegistry registry) {
        String up = "http://127.0.0.1:" + DOWNSTREAM.port();
        registry.add("spring.cloud.discovery.client.simple.instances.concert[0].uri", () -> up);
        registry.add("spring.cloud.discovery.client.simple.instances.payment[0].uri", () -> up);
        registry.add("spring.cloud.discovery.client.simple.instances.catalog[0].uri", () -> "http://127.0.0.1:" + CLOSED_PORT);
        registry.add("spring.cloud.discovery.client.simple.instances.catalog[1].uri", () -> up);
    }

    @MockBean IngestNonceStore nonceStore;
    @MockBean RedisRateLimiter rateLimiter;
    @Autowired WebTestClient client;
    @Autowired CircuitBreakerRegistry circuitBreakers;
    @Autowired LoadBalancerCacheProperties loadBalancerCache;

    @BeforeEach
    void setUp() {
        HITS.clear();
        circuitBreakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
        when(rateLimiter.isAllowed(anyString(), anyString())).thenReturn(Mono.just(new RateLimiter.Response(true, Map.of())));
    }

    @AfterAll
    static void stop() {
        DOWNSTREAM.disposeNow();
    }

    @Test
    void downstream500CountsAsFailureAndKeepsSameResponse() {
        client.get().uri("/api/concerts/broken").exchange()
                .expectStatus().isEqualTo(500)
                .expectBody().jsonPath("$.code").isEqualTo("INTERNAL_ERROR");

        assertThat(failed("concert")).isEqualTo(1);
        assertThat(hits("/api/concerts/broken")).isEqualTo(1);   // 5xx 는 재시도하지 않음
    }

    @Test
    void deliberate503PassesThroughAndIsNotCounted() {
        client.get().uri("/api/concerts/queue").exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.code").isEqualTo("QUEUE_UNAVAILABLE");

        assertThat(failed("concert")).isZero();
        assertThat(hits("/api/concerts/queue")).isEqualTo(1);
    }

    /** 폴백이 없는 결제 - 500 은 GatewayErrorHandler 가 같은 500 으로, PG_PROCESSING(503)은 본문 그대로 */
    @Test
    void paymentCountsOnly500() {
        webhook("/api/payments/webhook/broken").expectStatus().isEqualTo(500)
                .expectBody().jsonPath("$.code").isEqualTo("INTERNAL_ERROR");
        webhook("/api/payments/webhook/processing").expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.code").isEqualTo("PG_PROCESSING");

        assertThat(failed("payment")).isEqualTo(1);
    }

    /** 인스턴스 2개 중 1개는 닫힌 포트 - 라운드로빈이라 4번 중 최소 1번은 닫힌 포트를 고르고, 서킷 안쪽에서 다른 쪽으로 재시도 */
    @Test
    void connectFailureIsRetriedInsideCircuit() {
        for (int i = 0; i < 4; i++) {
            client.get().uri("/api/catalog/albums").exchange().expectStatus().isOk();
        }
        assertThat(hits("/api/catalog/albums")).isEqualTo(4);
        assertThat(failed("catalog")).isZero();
    }

    @Test
    void openCircuitReturnsFallbackWithoutCallingDownstream() {
        circuitBreakers.circuitBreaker("concert").transitionToOpenState();

        client.get().uri("/api/concerts/ok").exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.code").isEqualTo("SERVICE_UNAVAILABLE");

        assertThat(hits("/api/concerts/ok")).isZero();
    }

    /** 예매 서킷이 열려도 공연 조회는 그대로 */
    @Test
    void bookingCircuitIsSeparateFromConcert() {
        circuitBreakers.circuitBreaker("concert-booking").transitionToOpenState();

        client.get().uri("/api/concerts/ok").exchange().expectStatus().isOk();
        assertThat(circuitBreakers.circuitBreaker("concert").getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    /** 내려가는 Pod 를 preStop(15초) 안에 목록에서 빼도록 */
    @Test
    void loadBalancerCacheIsShorterThanPreStop() {
        assertThat(loadBalancerCache.getTtl()).isEqualTo(Duration.ofSeconds(5));
    }

    private WebTestClient.ResponseSpec webhook(String path) {
        return client.post().uri(path).contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange();
    }

    private int failed(String name) {
        return circuitBreakers.circuitBreaker(name).getMetrics().getNumberOfFailedCalls();
    }

    private static int hits(String uri) {
        AtomicInteger n = HITS.get(uri);
        return n == null ? 0 : n.get();
    }
}
