package com.indie.gateway.global.filter;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indie.gateway.domain.auth.service.InternalTokenIssuer;
import com.indie.gateway.domain.ingest.entity.IngestRoute;
import com.indie.gateway.domain.ingest.exception.IngestErrorCode;
import com.indie.gateway.domain.ingest.repository.IngestNonceStore;
import com.indie.gateway.domain.ingest.service.IngestSignatureVerifier;
import com.indie.gateway.global.common.exception.BaseErrorCode;
import com.indie.gateway.global.common.exception.CommonErrorCode;
import com.indie.gateway.global.common.response.ErrorResponses;
import com.indie.gateway.global.common.util.PathRules;
import com.indie.gateway.global.config.GatewayProperties;
import com.indie.gateway.global.config.RateLimiterConfig;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * 수집 경로 /ingest/** — Gateway 1차 검사 (docs/22 §1-4 G1~G9). 수집기는 묶음(items 최대 500건)으로 보내고,
 * 여기서는 <b>누가 · 어디서 · 얼마나 · 봉투가 온전한가</b>만 봅니다. 항목 안의 필드는 서비스 2차가 봅니다.
 * <pre>
 * G1 출처      ALB · Cloudflare 를 거친 요청 · 클러스터 밖 Host → 404
 * G2 고정 매핑 IngestRoute 에 없으면 404 · POST 만 · 쿼리 금지
 * G3 형식      JSON 만 415 · 본문 1 MiB 413 (chunked 도 읽으면서 끊음) · 본문이 10초 안에 다 안 오면 408
 * G4 서명      HMAC-SHA256 (본문 해시 포함 → 본문 변조도 여기서 걸림) 401
 * G5 재전송    시각 ±300초 · nonce SET NX 401 · 🔴 Redis 장애면 503 (fail-closed — 배치라 재시도하면 됨)
 * G7 호출 한도 키별 초당 10건 429 — 봉투 파싱(G6) 전에 둬서 CPU 도 같이 보호합니다
 * G6 봉투      JSON · 깊이 10 · 최상위 items 배열 1~maxItems 건 400
 * G8 기록      묶음 1건 = 로그 1줄 (본문은 남기지 않음 — 해시만)
 * G9 전달      새 X-Internal-Token (iss=gateway · sub=ingest:{키} · body_sha256 · TTL 60초)
 * </pre>
 * 하위 서비스 연결 실패 · 응답 지연은 라우트(RouteConfig)의 재시도 · 타임아웃 · Circuit Breaker 가 맡습니다.
 * 503 · 504 · 429 · 408 에는 Retry-After 를 붙입니다 — 수집기는 같은 본문을 <b>새 nonce · 시각으로 다시 서명</b>해 보냅니다.
 */
@Component
public class IngestFilter implements WebFilter, Ordered {

    public static final String KEY_ID = "X-Ingest-Key-Id";
    public static final String TIMESTAMP = "X-Ingest-Timestamp";
    public static final String NONCE = "X-Ingest-Nonce";
    public static final String SIGNATURE = "X-Ingest-Signature";

    private static final Logger log = LoggerFactory.getLogger(IngestFilter.class);
    private static final Pattern KEY_ID_FORMAT = Pattern.compile("[A-Za-z0-9-]{1,64}");
    private static final Pattern NONCE_FORMAT = Pattern.compile("[A-Za-z0-9-]{16,64}");
    private static final Pattern TIMESTAMP_FORMAT = Pattern.compile("\\d{1,12}");
    /** ALB · Cloudflare 가 붙이는 헤더 — X-Forwarded-For 는 ForwardedHeaderTransformer 가 필터보다 먼저 지우므로 이걸로 봅니다 */
    private static final List<String> PROXY_HEADERS = List.of("X-Amzn-Trace-Id", "CF-Ray", "CF-Connecting-IP", "X-Forwarded-For");

    private final GatewayProperties.Ingest props;
    private final long maxBodyBytes;
    private final IngestSignatureVerifier verifier;
    private final IngestNonceStore nonceStore;
    private final RedisRateLimiter rateLimiter;
    private final InternalTokenIssuer tokenIssuer;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final ObjectMapper envelopeMapper;

    public IngestFilter(GatewayProperties props, IngestSignatureVerifier verifier, IngestNonceStore nonceStore,
                        RedisRateLimiter rateLimiter, InternalTokenIssuer tokenIssuer, MeterRegistry meterRegistry, Clock clock) {
        this.props = props.ingest();
        this.maxBodyBytes = props.sanity().maxBodyBytes();
        this.verifier = verifier;
        this.nonceStore = nonceStore;
        this.rateLimiter = rateLimiter;
        this.tokenIssuer = tokenIssuer;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        this.envelopeMapper = new ObjectMapper(JsonFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(this.props.maxDepth()).build())
                .build());
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();
        if (!PathRules.isIngest(path)) {
            return chain.filter(exchange);
        }
        HttpHeaders headers = request.getHeaders();
        String keyId = headers.getFirst(KEY_ID);

        // G1 — 외부에서 온 요청에는 이 경로가 있다는 것도 알려주지 않습니다
        if (PROXY_HEADERS.stream().anyMatch(headers::containsKey)) {
            return reject(exchange, "source_proxy", keyId, CommonErrorCode.NOT_FOUND);
        }
        if (!isClusterHost(headers)) {
            return reject(exchange, "source_host", keyId, CommonErrorCode.NOT_FOUND);
        }
        // G2 — 여기부터는 클러스터 안 호출이라 경로 · 메서드를 구분해 알려줍니다
        IngestRoute route = IngestRoute.of(path).orElse(null);
        if (route == null) {
            return reject(exchange, "route", keyId, CommonErrorCode.NOT_FOUND);
        }
        if (!HttpMethod.POST.equals(request.getMethod())) {
            return reject(exchange, "method", keyId, CommonErrorCode.METHOD_NOT_ALLOWED);
        }
        if (!request.getQueryParams().isEmpty()) {   // 서명이 쿼리를 덮지 않으므로 받지 않습니다
            return reject(exchange, "query", keyId, CommonErrorCode.INVALID_REQUEST);
        }
        // G3
        MediaType type = headers.getContentType();
        if (type == null || !MediaType.APPLICATION_JSON.isCompatibleWith(type)) {
            return reject(exchange, "content_type", keyId, CommonErrorCode.UNSUPPORTED_MEDIA_TYPE);
        }
        String timestamp = headers.getFirst(TIMESTAMP);
        String nonce = headers.getFirst(NONCE);
        String signature = headers.getFirst(SIGNATURE);
        if (!matches(KEY_ID_FORMAT, keyId)) {
            return reject(exchange, "key_id_header", keyId, IngestErrorCode.INGEST_KEY_ID_INVALID);
        }
        if (!matches(TIMESTAMP_FORMAT, timestamp)) {
            return reject(exchange, "timestamp_header", keyId, IngestErrorCode.INGEST_TIMESTAMP_INVALID);
        }
        if (!matches(NONCE_FORMAT, nonce)) {
            return reject(exchange, "nonce_header", keyId, IngestErrorCode.INGEST_NONCE_INVALID);
        }
        if (signature == null) {
            return reject(exchange, "signature_header", keyId, IngestErrorCode.INGEST_SIGNATURE_MISSING);
        }

        long started = System.nanoTime();
        return DataBufferUtils.join(request.getBody(), (int) maxBodyBytes)
                .timeout(props.bodyReadTimeout())                     // 느리게 흘려보내는 연결이 이벤트 루프를 붙잡지 않게
                .map(IngestFilter::drain)
                .defaultIfEmpty(new byte[0])
                .map(Body::ok)
                .onErrorResume(DataBufferLimitException.class, e -> Mono.just(Body.failed(CommonErrorCode.PAYLOAD_TOO_LARGE)))
                .onErrorResume(TimeoutException.class, e -> Mono.just(Body.failed(IngestErrorCode.INGEST_BODY_TIMEOUT)))
                .flatMap(body -> body.rejection() != null
                        ? reject(exchange, "body", keyId, body.rejection())
                        : verify(exchange, chain, route, keyId, timestamp, nonce, signature, body.bytes(), started));
    }

    private Mono<Void> verify(ServerWebExchange exchange, WebFilterChain chain, IngestRoute route, String keyId,
                              String timestamp, String nonce, String signature, byte[] body, long started) {
        String bodySha256 = sha256Hex(body);
        // G4
        if (!verifier.verify(keyId, route.path(), timestamp, nonce, bodySha256, signature)) {
            return reject(exchange, "signature", keyId, IngestErrorCode.INGEST_SIGNATURE_INVALID);
        }
        // G5 — 시각
        long skew = Math.abs(clock.instant().getEpochSecond() - Long.parseLong(timestamp));
        if (skew > props.clockSkewSeconds()) {
            return reject(exchange, "timestamp", keyId, IngestErrorCode.INGEST_REPLAYED);
        }
        // G5 — nonce
        return nonceStore.claim(keyId, nonce)
                .map(Outcome::of)
                .defaultIfEmpty(Outcome.UNAVAILABLE)
                .onErrorResume(e -> {
                    log.warn("ingest_nonce_store_failed key={} error={}", keyId, e.toString());
                    return Mono.just(Outcome.UNAVAILABLE);
                })
                .flatMap(outcome -> switch (outcome) {
                    case UNAVAILABLE -> reject(exchange, "nonce_store", keyId, CommonErrorCode.SERVICE_UNAVAILABLE);
                    case DUPLICATE -> reject(exchange, "nonce", keyId, IngestErrorCode.INGEST_REPLAYED);
                    case FRESH -> limitAndForward(exchange, chain, route, keyId, body, bodySha256, started);
                });
    }

    private Mono<Void> limitAndForward(ServerWebExchange exchange, WebFilterChain chain, IngestRoute route,
                                       String keyId, byte[] body, String bodySha256, long started) {
        // G7 — RedisRateLimiter 는 Redis 장애 시 통과시킵니다 (바로 앞 G5 가 이미 fail-closed)
        return rateLimiter.isAllowed(RateLimiterConfig.INGEST, RateLimiterConfig.INGEST + ":" + keyId)
                .flatMap(limit -> {
                    if (!limit.isAllowed()) {
                        return reject(exchange, "rate_limit", keyId, CommonErrorCode.TOO_MANY_REQUESTS);
                    }
                    // G6
                    int items = countItems(body, route.maxItems());
                    if (items < 0) {
                        return reject(exchange, "envelope", keyId, CommonErrorCode.INVALID_REQUEST);
                    }
                    // G9
                    ServerWebExchange forwarded = exchange.mutate()
                            .request(withBody(exchange, tokenIssuer.issueForIngest(route.audience(), keyId, bodySha256), body))
                            .build();
                    // G8 — 묶음 1건 = 1줄. 결과 상태는 하위 서비스 응답(또는 폴백) 뒤에 확정됩니다
                    return chain.filter(forwarded).doFinally(signal -> log.info(
                            "ingest_request key={} path={} items={} bytes={} body_sha256={} status={} elapsed_ms={}",
                            keyId, route.path(), items, body.length, bodySha256,
                            exchange.getResponse().getStatusCode(), (System.nanoTime() - started) / 1_000_000));
                });
    }

    /** 최상위 { "items": [ … ] } · 1 ≤ 건수 ≤ maxItems 면 건수, 아니면 -1. 항목 안은 보지 않습니다 */
    private int countItems(byte[] body, int maxItems) {
        try {
            JsonNode root = envelopeMapper.readTree(body);
            JsonNode items = root == null || !root.isObject() ? null : root.get("items");
            if (items == null || !items.isArray() || items.isEmpty() || items.size() > maxItems) {
                return -1;
            }
            return items.size();
        } catch (Exception e) {   // 깨진 JSON · 깊이 초과(StreamConstraintsException)
            return -1;
        }
    }

    private static ServerHttpRequest withBody(ServerWebExchange exchange, String internalToken, byte[] body) {
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(h -> {
                    h.set("X-Internal-Token", internalToken);
                    h.remove(HttpHeaders.TRANSFER_ENCODING);
                    h.setContentLength(body.length);
                })
                .build();
        return new ServerHttpRequestDecorator(request) {
            @Override
            public Flux<DataBuffer> getBody() {   // 이미 읽은 본문을 다시 흘려보냄 — 재시도해도 매번 새 버퍼
                return Flux.defer(() -> Flux.just(exchange.getResponse().bufferFactory().wrap(body)));
            }
        };
    }

    private Mono<Void> reject(ServerWebExchange exchange, String rule, String keyId, BaseErrorCode code) {
        meterRegistry.counter("gateway.ingest.reject", "rule", rule).increment();
        log.warn("ingest_rejected rule={} key={} path={} status={}",
                rule, matches(KEY_ID_FORMAT, keyId) ? keyId : "-", exchange.getRequest().getPath().value(), code.getStatus().value());
        String retryAfter = switch (code.getStatus().value()) {
            case 408, 429 -> "1";
            case 503 -> "5";
            default -> null;
        };
        if (retryAfter != null) {
            exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER, retryAfter);
        }
        return ErrorResponses.write(exchange, code);
    }

    private boolean isClusterHost(HttpHeaders headers) {
        InetSocketAddress host = headers.getHost();
        return host != null && props.hosts().stream().anyMatch(h -> h.equalsIgnoreCase(host.getHostString()));
    }

    private static boolean matches(Pattern pattern, String value) {
        return value != null && pattern.matcher(value).matches();
    }

    private static byte[] drain(DataBuffer buffer) {
        try {
            byte[] bytes = new byte[buffer.readableByteCount()];
            buffer.read(bytes);
            return bytes;
        } finally {
            DataBufferUtils.release(buffer);
        }
    }

    private static String sha256Hex(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record Body(byte[] bytes, BaseErrorCode rejection) {
        static Body ok(byte[] bytes) { return new Body(bytes, null); }
        static Body failed(BaseErrorCode code) { return new Body(null, code); }
    }

    private enum Outcome {
        FRESH, DUPLICATE, UNAVAILABLE;

        static Outcome of(Boolean claimed) {
            return Boolean.TRUE.equals(claimed) ? FRESH : DUPLICATE;
        }
    }

    @Override
    public int getOrder() {
        return GatewayFilterOrder.INGEST;
    }
}
