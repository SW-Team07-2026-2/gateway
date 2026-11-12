package com.indie.gateway.global.config;

import com.indie.gateway.domain.ingest.repository.IngestNonceStore;
import com.indie.gateway.global.filter.IngestFilter;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 수집 경로 라우팅 — 연결 실패 · 응답 지연 (docs/22 §1-4). 가짜 하위 서비스를 띄워 실제 Gateway 라우트로 보냅니다.
 * <pre>
 * catalog  인스턴스 2개 중 1개는 닫힌 포트 → 연결 실패 1회 재시도로 다른 인스턴스 → 200
 * concert  /internal/concerts 가 3초 걸림 → 응답 타임아웃(테스트 1초) → 504 + Retry-After
 * chatbot  인스턴스가 전부 닫힌 포트 → 재시도 뒤 503 + Retry-After
 * member   AI 의 회원 데이터 조회 · 저장 → /internal/members/{userId}/** (경로 변수 · 쿼리 · 본문이 그대로)
 * </pre>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.cloud.kubernetes.enabled=false",
        "spring.cloud.kubernetes.discovery.enabled=false",
        "internal.shared-secret=0123456789abcdef0123456789abcdef-local",
        "ingest.keys.crawler-test=ingest-key-0123456789abcdef0123456789",
        "ingest.member-keys.ai-test=member-key-0123456789abcdef0123456789",
        "app.cookie.domain=",
        "app.cors.origins=http://localhost:5173",
        "app.ingest.hosts=localhost",
        "app.ingest.response-timeout=1s",
})
class IngestRoutingTest {

    static final String KEY = "ingest-key-0123456789abcdef0123456789";
    static final String MEMBER_KEY = "member-key-0123456789abcdef0123456789";
    static final String VECTOR = "{\"embedding\":\"[0.0123,-0.0456]\"}";
    static final String BODY = "{\"items\":[{\"source\":\"melon\",\"external_id\":\"a1\",\"name\":\"밴드\"}]}";

    record Received(String uri, String internalToken, String body) {}

    static final List<Received> RECEIVED = new CopyOnWriteArrayList<>();

    static final DisposableServer DOWNSTREAM = HttpServer.create().host("127.0.0.1").port(0)
            .route(routes -> routes
                    .post("/internal/catalog/artists", (req, res) -> req.receive().aggregate().asString().defaultIfEmpty("")
                            .doOnNext(body -> RECEIVED.add(new Received(req.uri(), req.requestHeaders().get("X-Internal-Token"), body)))
                            .then(res.header("Content-Type", "application/json")
                                    .sendString(Mono.just("{\"isSuccess\":true,\"code\":\"OK\"}")).then()))
                    .post("/internal/concerts", (req, res) -> Mono.delay(Duration.ofSeconds(3))
                            .then(res.sendString(Mono.just("{}")).then()))
                    .get("/internal/members/{userId}/metrics", (req, res) -> {
                        RECEIVED.add(new Received(req.uri(), req.requestHeaders().get("X-Internal-Token"), ""));
                        return res.header("Content-Type", "application/json")
                                .sendString(Mono.just("{\"isSuccess\":true,\"result\":{\"items\":[]}}")).then();
                    })
                    .put("/internal/members/{userId}/embedding", (req, res) -> req.receive().aggregate().asString().defaultIfEmpty("")
                            .doOnNext(body -> RECEIVED.add(new Received(req.uri(), req.requestHeaders().get("X-Internal-Token"), body)))
                            .then(res.header("Content-Type", "application/json")
                                    .sendString(Mono.just("{\"isSuccess\":true,\"result\":{\"userId\":42,\"dimension\":2}}")).then())))
            .bindNow();

    static final int CLOSED_PORT = closedPort();

    @DynamicPropertySource
    static void instances(DynamicPropertyRegistry registry) {
        String up = "http://127.0.0.1:" + DOWNSTREAM.port();
        String down = "http://127.0.0.1:" + CLOSED_PORT;
        registry.add("spring.cloud.discovery.client.simple.instances.catalog[0].uri", () -> down);
        registry.add("spring.cloud.discovery.client.simple.instances.catalog[1].uri", () -> up);
        registry.add("spring.cloud.discovery.client.simple.instances.concert[0].uri", () -> up);
        registry.add("spring.cloud.discovery.client.simple.instances.chatbot-service[0].uri", () -> down);
        registry.add("spring.cloud.discovery.client.simple.instances.member[0].uri", () -> up);
    }

    @MockBean IngestNonceStore nonceStore;
    @MockBean RedisRateLimiter rateLimiter;
    @Autowired WebTestClient client;

    @BeforeEach
    void setUp() {
        RECEIVED.clear();
        when(nonceStore.claim(anyString(), anyString())).thenReturn(Mono.just(true));
        when(rateLimiter.isAllowed(anyString(), anyString())).thenReturn(Mono.just(new RateLimiter.Response(true, Map.of())));
    }

    @AfterAll
    static void stop() {
        DOWNSTREAM.disposeNow();
    }

    /** 롤링 배포 중 내려간 Pod — 연결 실패는 다른 Pod 로 1회 재시도. 라운드로빈이라 4번 중 최소 1번은 닫힌 포트를 고릅니다 */
    @Test
    void connectFailureToOnePodIsRetriedOnAnother() throws Exception {
        for (int i = 0; i < 4; i++) {
            post("/ingest/catalog/artists").expectStatus().isOk();
        }
        assertThat(RECEIVED).hasSize(4);
        Received first = RECEIVED.get(0);
        assertThat(first.uri()).isEqualTo("/internal/catalog/artists");   // 고정 매핑 (SetPath)
        assertThat(first.body()).isEqualTo(BODY);                          // 읽은 본문을 그대로 다시 보냄
        JWTClaimsSet claims = SignedJWT.parse(first.internalToken()).getJWTClaimsSet();
        assertThat(claims.getAudience()).containsExactly("catalog");
        assertThat(claims.getSubject()).isEqualTo("ingest:crawler-test");
    }

    @Test
    void slowDownstreamIs504WithRetryAfter() throws Exception {
        long started = System.nanoTime();
        post("/ingest/concerts").expectStatus().isEqualTo(504)
                .expectHeader().valueEquals("Retry-After", "30")
                .expectBody().jsonPath("$.code").isEqualTo("GATEWAY_TIMEOUT");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(2500));   // 3초를 기다리지 않음
    }

    @Test
    void allPodsDownIs503WithRetryAfter() throws Exception {
        post("/ingest/ai/documents").expectStatus().isEqualTo(503)
                .expectHeader().valueEquals("Retry-After", "30")
                .expectBody().jsonPath("$.code").isEqualTo("SERVICE_UNAVAILABLE");
    }

    /** ALB · Cloudflare 경유(외부)면 서명이 맞아도 404 */
    @Test
    void externalRequestIs404EvenWhenSigned() throws Exception {
        client.post().uri("/ingest/catalog/artists").contentType(MediaType.APPLICATION_JSON)
                .headers(h -> signHeaders("/ingest/catalog/artists").forEach(h::set))
                .header("X-Amzn-Trace-Id", "Root=1-abc")
                .bodyValue(BODY).exchange()
                .expectStatus().isNotFound();
        assertThat(RECEIVED).isEmpty();
    }

    /** AI → 회원 메트릭 조회 — 경로 변수와 쿼리가 그대로 Member 내부 경로로 가고, 내부 토큰은 aud=member */
    @Test
    void memberMetricsReadReachesMemberInternalPath() throws Exception {
        String target = "/ingest/members/42/metrics?metricType=DETAIL_VIEW,DWELL&aggregate=true&limit=10";

        client.get().uri(target)
                .headers(h -> signHeaders("GET", target, "", "ai-test", MEMBER_KEY).forEach(h::set))
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.isSuccess").isEqualTo(true);

        assertThat(RECEIVED).hasSize(1);
        Received received = RECEIVED.get(0);
        assertThat(received.uri()).isEqualTo("/internal/members/42/metrics?metricType=DETAIL_VIEW,DWELL&aggregate=true&limit=10");
        JWTClaimsSet claims = SignedJWT.parse(received.internalToken()).getJWTClaimsSet();
        assertThat(claims.getAudience()).containsExactly("member");
        assertThat(claims.getSubject()).isEqualTo("ingest:ai-test");
    }

    /** AI → 회원 벡터 저장 — PUT 본문이 그대로 */
    @Test
    void memberEmbeddingWriteReachesMemberInternalPath() throws Exception {
        String target = "/ingest/members/42/embedding";

        client.put().uri(target).contentType(MediaType.APPLICATION_JSON)
                .headers(h -> signHeaders("PUT", target, VECTOR, "ai-test", MEMBER_KEY).forEach(h::set))
                .bodyValue(VECTOR).exchange()
                .expectStatus().isOk();

        assertThat(RECEIVED).hasSize(1);
        assertThat(RECEIVED.get(0).uri()).isEqualTo("/internal/members/42/embedding");
        assertThat(RECEIVED.get(0).body()).isEqualTo(VECTOR);
    }

    /** 수집 키로는 회원 데이터 경로가 열리지 않습니다 — Member 까지 가지 않음 */
    @Test
    void collectKeyCannotReadMemberData() throws Exception {
        String target = "/ingest/members/42/metrics";

        client.get().uri(target)
                .headers(h -> signHeaders("GET", target, "", "crawler-test", KEY).forEach(h::set))
                .exchange()
                .expectStatus().isUnauthorized();

        assertThat(RECEIVED).isEmpty();
    }

    private WebTestClient.ResponseSpec post(String path) throws Exception {
        return client.post().uri(path).contentType(MediaType.APPLICATION_JSON)
                .headers(h -> signHeaders(path).forEach(h::set))
                .bodyValue(BODY).exchange();
    }

    private static Map<String, String> signHeaders(String path) {
        return signHeaders("POST", path, BODY, "crawler-test", KEY);
    }

    private static Map<String, String> signHeaders(String method, String target, String body, String keyId, String key) {
        try {
            long ts = System.currentTimeMillis() / 1000;
            String nonce = UUID.randomUUID().toString();
            String bodyHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String sig = Base64.getEncoder().encodeToString(
                    mac.doFinal((method + "\n" + target + "\n" + ts + "\n" + nonce + "\n" + bodyHash).getBytes(StandardCharsets.UTF_8)));
            return Map.of(IngestFilter.KEY_ID, keyId, IngestFilter.TIMESTAMP, String.valueOf(ts),
                    IngestFilter.NONCE, nonce, IngestFilter.SIGNATURE, sig);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static int closedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
