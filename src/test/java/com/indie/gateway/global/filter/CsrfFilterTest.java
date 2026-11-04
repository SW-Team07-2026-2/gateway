package com.indie.gateway.global.filter;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class CsrfFilterTest {

    private final CsrfFilter filter = new CsrfFilter();

    private boolean passes(MockServerHttpRequest request, MockServerWebExchange[] holder) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        holder[0] = exchange;
        AtomicBoolean passed = new AtomicBoolean();
        filter.filter(exchange, e -> { passed.set(true); return Mono.empty(); }).block();
        return passed.get();
    }

    /** DoD 8 */
    @Test
    void postWithoutHeaderIsRejected() {
        MockServerWebExchange[] ex = new MockServerWebExchange[1];
        assertThat(passes(MockServerHttpRequest.post("/api/members/follows/1").cookie(cookie("abc")).build(), ex)).isFalse();
        assertThat(ex[0].getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void missingCookieHeaderAndMismatchHaveOwnCodes() {
        MockServerWebExchange[] ex = new MockServerWebExchange[1];
        passes(MockServerHttpRequest.post("/api/members/follows/1").header(CsrfFilter.HEADER, "abc").build(), ex);
        assertThat(ex[0].getResponse().getBodyAsString().block()).contains("CSRF_COOKIE_MISSING");
        passes(MockServerHttpRequest.post("/api/members/follows/1").cookie(cookie("abc")).build(), ex);
        assertThat(ex[0].getResponse().getBodyAsString().block()).contains("CSRF_HEADER_MISSING");
        passes(MockServerHttpRequest.post("/api/members/follows/1").cookie(cookie("abc")).header(CsrfFilter.HEADER, "abd").build(), ex);
        assertThat(ex[0].getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(ex[0].getResponse().getBodyAsString().block()).contains("CSRF_MISMATCH");
    }

    @Test
    void matchingCookieAndHeaderPass() {
        MockServerWebExchange[] ex = new MockServerWebExchange[1];
        assertThat(passes(MockServerHttpRequest.post("/api/members/follows/1").cookie(cookie("abc"))
                .header(CsrfFilter.HEADER, "abc").build(), ex)).isTrue();
    }

    /** DoD 9 — PG 웹훅은 쿠키가 없습니다 */
    @Test
    void webhookIsExempt() {
        MockServerWebExchange[] ex = new MockServerWebExchange[1];
        assertThat(passes(MockServerHttpRequest.post("/api/payments/webhook/toss").build(), ex)).isTrue();
    }

    @Test
    void getIsExempt() {
        MockServerWebExchange[] ex = new MockServerWebExchange[1];
        assertThat(passes(MockServerHttpRequest.get("/api/members/me").build(), ex)).isTrue();
    }

    private static org.springframework.http.HttpCookie cookie(String value) {
        return new org.springframework.http.HttpCookie("XSRF-TOKEN", value);
    }
}
