package com.indie.gateway.global.config;

import com.indie.gateway.domain.ingest.repository.IngestNonceStore;
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

import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 수집 경로 — 하위 서비스의 인스턴스가 전부 내려가 있을 때 (docs/22 §1-4). 재시도 뒤 503 + Retry-After.
 * IngestRoutingTest 는 catalog 인스턴스 하나를 살려 두므로, 전부 닫힌 포트인 경우는 여기서 따로 봅니다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.cloud.kubernetes.enabled=false",
        "spring.cloud.kubernetes.discovery.enabled=false",
        "internal.shared-secret=0123456789abcdef0123456789abcdef-local",
        "ingest.keys.crawler-test=" + IngestRoutingTest.KEY,
        "app.cookie.domain=",
        "app.cors.origins=http://localhost:5173",
        "app.ingest.hosts=localhost",
        "app.ingest.response-timeout=1s",
})
class IngestAllPodsDownTest {

    static final int CLOSED_PORT = IngestRoutingTest.closedPort();

    @DynamicPropertySource
    static void instances(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.discovery.client.simple.instances.catalog[0].uri", () -> "http://127.0.0.1:" + CLOSED_PORT);
    }

    @MockBean IngestNonceStore nonceStore;
    @MockBean RedisRateLimiter rateLimiter;
    @Autowired WebTestClient client;

    @BeforeEach
    void setUp() {
        when(nonceStore.claim(anyString(), anyString())).thenReturn(Mono.just(true));
        when(rateLimiter.isAllowed(anyString(), anyString())).thenReturn(Mono.just(new RateLimiter.Response(true, Map.of())));
    }

    @Test
    void allPodsDownIs503WithRetryAfter() {
        String path = "/ingest/catalog/artists";
        client.post().uri(path).contentType(MediaType.APPLICATION_JSON)
                .headers(h -> IngestRoutingTest.signHeaders(path).forEach(h::set))
                .bodyValue(IngestRoutingTest.BODY).exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().valueEquals("Retry-After", "30")
                .expectBody().jsonPath("$.code").isEqualTo("SERVICE_UNAVAILABLE");
    }
}
