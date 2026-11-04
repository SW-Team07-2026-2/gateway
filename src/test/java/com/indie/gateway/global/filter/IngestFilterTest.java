package com.indie.gateway.global.filter;

import com.indie.gateway.TestProps;
import com.indie.gateway.domain.auth.service.InternalTokenIssuer;
import com.indie.gateway.domain.ingest.repository.IngestNonceStore;
import com.indie.gateway.domain.ingest.service.IngestSignatureVerifier;
import com.indie.gateway.global.config.GatewayProperties;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** docs/22 §1-4 G1~G9 */
class IngestFilterTest {

    static final String KEY_ID = "crawler-test";
    static final String KEY = "ingest-key-0123456789abcdef0123456789";
    static final String SHARED = "0123456789abcdef0123456789abcdef-local";
    static final String PATH = "/ingest/catalog/artists";
    static final String BODY = "{\"items\":[{\"source\":\"melon\",\"external_id\":\"a1\",\"name\":\"밴드\"}]}";

    private final IngestNonceStore nonceStore = mock(IngestNonceStore.class);
    private final RedisRateLimiter rateLimiter = mock(RedisRateLimiter.class);
    private final AtomicReference<ServerWebExchange> passed = new AtomicReference<>();

    IngestFilterTest() {
        when(nonceStore.claim(anyString(), anyString())).thenReturn(Mono.just(true));
        when(rateLimiter.isAllowed(anyString(), anyString())).thenReturn(Mono.just(new RateLimiter.Response(true, Map.of())));
    }

    private IngestFilter filter(GatewayProperties props) throws Exception {
        MockEnvironment env = new MockEnvironment().withProperty("ingest.keys." + KEY_ID, KEY);
        return new IngestFilter(props, new IngestSignatureVerifier(env), nonceStore, rateLimiter,
                new InternalTokenIssuer(SHARED, props, Clock.systemUTC()), new SimpleMeterRegistry(), Clock.systemUTC());
    }

    private MockServerWebExchange run(MockServerHttpRequest request) throws Exception {
        return run(request, TestProps.create(null));
    }

    private MockServerWebExchange run(MockServerHttpRequest request, GatewayProperties props) throws Exception {
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        filter(props).filter(exchange, e -> {
            passed.set(e);
            e.getResponse().setRawStatusCode(200);
            return Mono.empty();
        }).block(Duration.ofSeconds(5));
        return exchange;
    }

    /** 올바르게 서명된 요청 — 수집기가 보내는 모양 */
    static MockServerHttpRequest.BodyBuilder signed(String path, String body, long timestamp, String signWith) throws Exception {
        return signed(path, body, timestamp, signWith, "gateway.indie.svc.cluster.local:8080", KEY_ID);
    }

    static MockServerHttpRequest.BodyBuilder signed(String path, String body, long timestamp, String signWith,
                                                    String host, String keyId) throws Exception {
        String nonce = UUID.randomUUID().toString();
        return MockServerHttpRequest.post(path)
                .header("Host", host)
                .contentType(MediaType.APPLICATION_JSON)
                .header(IngestFilter.KEY_ID, keyId)
                .header(IngestFilter.TIMESTAMP, String.valueOf(timestamp))
                .header(IngestFilter.NONCE, nonce)
                .header(IngestFilter.SIGNATURE, sign(signWith, path, timestamp, nonce, body));
    }

    static MockServerHttpRequest.BodyBuilder signed(String path, String body) throws Exception {
        return signed(path, body, now(), KEY);
    }

    static String sign(String key, String path, long timestamp, String nonce, String body) throws Exception {
        String bodyHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] sig = mac.doFinal(("POST\n" + path + "\n" + timestamp + "\n" + nonce + "\n" + bodyHash).getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(sig);
    }

    static long now() {
        return System.currentTimeMillis() / 1000;
    }

    private static int status(MockServerWebExchange exchange) {
        return exchange.getResponse().getStatusCode().value();
    }

    private static String responseBody(MockServerWebExchange exchange) {
        return exchange.getResponse().getBodyAsString().block(Duration.ofSeconds(1));
    }

    // ── 통과 · G9 ───────────────────────────────────────────────────────

    @Test
    void validRequestIsForwardedWithIngestTokenAndSameBody() throws Exception {
        MockServerWebExchange exchange = run(signed(PATH, BODY).body(BODY));

        assertThat(status(exchange)).isEqualTo(200);
        var forwarded = passed.get().getRequest();
        JWTClaimsSet claims = SignedJWT.parse(forwarded.getHeaders().getFirst("X-Internal-Token")).getJWTClaimsSet();
        assertThat(claims.getIssuer()).isEqualTo("gateway");
        assertThat(claims.getAudience()).containsExactly("catalog");
        assertThat(claims.getSubject()).isEqualTo("ingest:" + KEY_ID);
        assertThat(claims.getStringClaim("body_sha256")).isEqualTo(
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(BODY.getBytes(StandardCharsets.UTF_8))));
        assertThat(claims.getExpirationTime().getTime() - claims.getIssueTime().getTime()).isEqualTo(60_000);
        assertThat(forwarded.getHeaders().getContentLength()).isEqualTo(BODY.getBytes(StandardCharsets.UTF_8).length);
        // 이미 읽은 본문을 하위로 다시 흘려보냄 — 재시도(두 번째 구독)에도 같은 내용
        for (int i = 0; i < 2; i++) {
            assertThat(DataBufferUtils.join(forwarded.getBody()).map(b -> b.toString(StandardCharsets.UTF_8)).block()).isEqualTo(BODY);
        }
    }

    @Test
    void diffPathAllowsUpTo1000Items() throws Exception {
        String body = "{\"source\":\"melon\",\"items\":[" + String.join(",", java.util.Collections.nCopies(1000, "{}")) + "]}";
        assertThat(status(run(signed("/ingest/catalog/diff", body).body(body)))).isEqualTo(200);
    }

    // ── G1 출처 ─────────────────────────────────────────────────────────

    @Test
    void requestThroughAlbIs404() throws Exception {
        assertThat(status(run(signed(PATH, BODY).header("X-Amzn-Trace-Id", "Root=1-abc").body(BODY)))).isEqualTo(404);
        assertThat(passed.get()).isNull();
    }

    @Test
    void externalHostIs404() throws Exception {
        assertThat(status(run(signed(PATH, BODY, now(), KEY, "api.example.com", KEY_ID).body(BODY)))).isEqualTo(404);
    }

    // ── G2 고정 매핑 ────────────────────────────────────────────────────

    @Test
    void unmappedPathIs404() throws Exception {
        assertThat(status(run(signed("/ingest/members", BODY).body(BODY)))).isEqualTo(404);
        assertThat(status(run(signed("/ingest/catalog/artists/1", BODY).body(BODY)))).isEqualTo(404);
    }

    /** 클러스터 안(G1 통과)에서 메서드만 틀리면 404 가 아니라 405 — 수집기 버그를 바로 알 수 있게 */
    @Test
    void nonPostIs405() throws Exception {
        MockServerWebExchange exchange = run(MockServerHttpRequest.get(PATH).header("Host", "gateway").build());
        assertThat(status(exchange)).isEqualTo(405);
        assertThat(responseBody(exchange)).contains("METHOD_NOT_ALLOWED");
    }

    @Test
    void queryStringIs400() throws Exception {
        assertThat(status(run(signed(PATH + "?x=1", BODY).body(BODY)))).isEqualTo(400);
    }

    @Test
    void nonIngestPathIsUntouched() throws Exception {
        MockServerWebExchange exchange = run(MockServerHttpRequest.get("/api/catalog/albums").header("Host", "api.example.com").build());
        assertThat(status(exchange)).isEqualTo(200);
        assertThat(passed.get().getRequest().getHeaders().containsKey("X-Internal-Token")).isFalse();
    }

    // ── G3 형식 ─────────────────────────────────────────────────────────

    @Test
    void nonJsonIs415() throws Exception {
        assertThat(status(run(signed(PATH, BODY).contentType(MediaType.TEXT_PLAIN).body(BODY)))).isEqualTo(415);
    }

    /** Content-Length 없이(chunked) 흘려보내도 1 MiB 에서 끊습니다 — ⓪ 필터는 Content-Length 만 봅니다 */
    @Test
    void chunkedBodyOver1MiBIs413() throws Exception {
        Flux<DataBuffer> body = Flux.range(0, 5).map(i -> DefaultDataBufferFactory.sharedInstance.wrap(new byte[256 * 1024]));
        assertThat(status(run(signed(PATH, BODY).body(body)))).isEqualTo(413);
    }

    /** 본문을 끝까지 안 보내는 연결 — 이벤트 루프를 붙잡지 않고 408 */
    @Test
    void stalledBodyIs408WithRetryAfter() throws Exception {
        GatewayProperties base = TestProps.create(null);
        GatewayProperties.Ingest fast = new GatewayProperties.Ingest(base.ingest().hosts(), 300,
                Duration.ofSeconds(600), Duration.ofMillis(100), Duration.ofSeconds(30), 60, 10);
        GatewayProperties props = new GatewayProperties(base.domain(), base.cookie(), base.token(), base.internalToken(),
                base.memberBaseUrl(), base.oauth(), base.sanity(), base.trust(), base.redis(), fast);

        MockServerWebExchange exchange = run(signed(PATH, BODY).body(Flux.never()), props);

        assertThat(status(exchange)).isEqualTo(408);
        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("1");
    }

    // ── G4 서명 ─────────────────────────────────────────────────────────

    @Test
    void missingSignatureHeadersIs401() throws Exception {
        assertThat(status(run(MockServerHttpRequest.post(PATH).header("Host", "gateway")
                .contentType(MediaType.APPLICATION_JSON).body(BODY)))).isEqualTo(401);
    }

    /** 서명 헤더 4개는 빠진 · 틀린 헤더마다 코드가 다릅니다 (모두 401) */
    @Test
    void eachBrokenSignatureHeaderHasOwnCode() throws Exception {
        Map<String, String> cases = Map.of(
                IngestFilter.KEY_ID, "INGEST_KEY_ID_INVALID",
                IngestFilter.TIMESTAMP, "INGEST_TIMESTAMP_INVALID",
                IngestFilter.NONCE, "INGEST_NONCE_INVALID",
                IngestFilter.SIGNATURE, "INGEST_SIGNATURE_MISSING");
        for (var c : cases.entrySet()) {
            HttpHeaders headers = new HttpHeaders();
            headers.putAll(signed(PATH, BODY).build().getHeaders());
            headers.remove(c.getKey());
            MockServerWebExchange exchange = run(MockServerHttpRequest.post(PATH).headers(headers).body(BODY));
            assertThat(status(exchange)).as(c.getKey()).isEqualTo(401);
            assertThat(responseBody(exchange)).as(c.getKey()).contains(c.getValue());
        }
    }

    @Test
    void wrongKeyIs401() throws Exception {
        MockServerWebExchange exchange = run(signed(PATH, BODY, now(), "another-key-another-key-another-key").body(BODY));
        assertThat(status(exchange)).isEqualTo(401);
        assertThat(responseBody(exchange)).contains("INGEST_SIGNATURE_INVALID");
    }

    @Test
    void unknownKeyIdIs401() throws Exception {
        assertThat(status(run(signed(PATH, BODY, now(), KEY, "gateway", "crawler-old").body(BODY)))).isEqualTo(401);
    }

    /** 서명 안에 본문 해시가 있어서 본문을 바꾸면 서명이 깨집니다 */
    @Test
    void tamperedBodyIs401() throws Exception {
        String tampered = BODY.replace("밴드", "가짜");
        assertThat(status(run(signed(PATH, BODY).body(tampered)))).isEqualTo(401);
    }

    /** 서명은 경로도 덮습니다 — artists 서명으로 concerts 에 보낼 수 없음 */
    @Test
    void signatureForAnotherPathIs401() throws Exception {
        long ts = now();
        String nonce = UUID.randomUUID().toString();
        MockServerHttpRequest request = MockServerHttpRequest.post("/ingest/concerts").header("Host", "gateway")
                .contentType(MediaType.APPLICATION_JSON)
                .header(IngestFilter.KEY_ID, KEY_ID).header(IngestFilter.TIMESTAMP, String.valueOf(ts))
                .header(IngestFilter.NONCE, nonce).header(IngestFilter.SIGNATURE, sign(KEY, PATH, ts, nonce, BODY))
                .body(BODY);
        assertThat(status(run(request))).isEqualTo(401);
    }

    // ── G5 재전송 ───────────────────────────────────────────────────────

    @Test
    void staleTimestampIs401Replayed() throws Exception {
        MockServerWebExchange exchange = run(signed(PATH, BODY, now() - 301, KEY).body(BODY));
        assertThat(status(exchange)).isEqualTo(401);
        assertThat(responseBody(exchange)).contains("INGEST_REPLAYED");
    }

    @Test
    void reusedNonceIs401Replayed() throws Exception {
        when(nonceStore.claim(anyString(), anyString())).thenReturn(Mono.just(false));
        MockServerWebExchange exchange = run(signed(PATH, BODY).body(BODY));
        assertThat(status(exchange)).isEqualTo(401);
        assertThat(responseBody(exchange)).contains("INGEST_REPLAYED");
    }

    /** 🔴 fail-closed — 재전송을 확인할 수 없으면 받지 않습니다 */
    @Test
    void nonceStoreDownIs503WithRetryAfter() throws Exception {
        when(nonceStore.claim(anyString(), anyString())).thenReturn(Mono.error(new RedisConnectionFailureException("down")));
        MockServerWebExchange exchange = run(signed(PATH, BODY).body(BODY));
        assertThat(status(exchange)).isEqualTo(503);
        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("5");
        assertThat(passed.get()).isNull();
    }

    // ── G7 호출 한도 ────────────────────────────────────────────────────

    @Test
    void overRateLimitIs429WithRetryAfter() throws Exception {
        when(rateLimiter.isAllowed(anyString(), anyString())).thenReturn(Mono.just(new RateLimiter.Response(false, Map.of())));
        MockServerWebExchange exchange = run(signed(PATH, BODY).body(BODY));
        assertThat(status(exchange)).isEqualTo(429);
        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("1");
    }

    // ── G6 봉투 ─────────────────────────────────────────────────────────

    @Test
    void brokenEnvelopeIs400() throws Exception {
        for (String body : List.of("{\"items\":[]}", "[{}]", "{\"rows\":[{}]}", "{\"items\":{}}", "{\"items\":[{}", "")) {
            passed.set(null);
            assertThat(status(run(signed(PATH, body).body(body)))).as(body).isEqualTo(400);
            assertThat(passed.get()).isNull();
        }
    }

    @Test
    void over500ItemsIs400() throws Exception {
        String body = "{\"items\":[" + String.join(",", java.util.Collections.nCopies(501, "{}")) + "]}";
        assertThat(status(run(signed(PATH, body).body(body)))).isEqualTo(400);
    }

    @Test
    void nestingDeeperThan10Is400() throws Exception {
        String body = "{\"items\":[" + "[".repeat(9) + "]".repeat(9) + "]}";
        assertThat(status(run(signed(PATH, body).body(body)))).isEqualTo(400);
    }
}
