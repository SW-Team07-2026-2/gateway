package com.indie.gateway.global.filter;

import com.indie.gateway.TestProps;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

class RequestSanityFilterTest {

    private final RequestSanityFilter filter = new RequestSanityFilter(TestProps.create(null), new SimpleMeterRegistry());

    private int status(MockServerHttpRequest request) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        filter.filter(exchange, e -> { e.getResponse().setRawStatusCode(200); return Mono.empty(); }).block();
        return exchange.getResponse().getStatusCode().value();
    }

    @Test
    void normalRequestPasses() {
        assertThat(status(MockServerHttpRequest.get("/api/catalog/albums").header("Host", "api.example.com").build())).isEqualTo(200);
    }

    /** DoD 11 */
    @Test
    void encodedPathTraversalIsRejected() {
        assertThat(status(MockServerHttpRequest.get("/api/catalog/..%2f..%2fetc/passwd").header("Host", "api.example.com").build())).isEqualTo(400);
    }

    /** DoD 13 */
    @Test
    void foreignHostIsRejected() {
        assertThat(status(MockServerHttpRequest.get("/api/catalog/albums").header("Host", "evil.com").build())).isEqualTo(400);
    }

    /** DoD 12 */
    @Test
    void oversizedBodyIsRejected() {
        assertThat(status(MockServerHttpRequest.post("/api/members/me").header("Host", "api.example.com")
                .contentType(MediaType.APPLICATION_JSON).contentLength(2 * 1024 * 1024).build())).isEqualTo(413);
    }

    @Test
    void bodylessPostWithoutContentTypePasses() {
        assertThat(status(MockServerHttpRequest.post("/api/members/follows/1").header("Host", "api.example.com").build())).isEqualTo(200);
    }

    @Test
    void formPostIsRejected() {
        assertThat(status(MockServerHttpRequest.post("/api/members/me").header("Host", "api.example.com")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).contentLength(3).build())).isEqualTo(415);
    }
}
