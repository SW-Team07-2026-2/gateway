package com.indie.gateway.global.filter;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class TermsXsrfTokenFilterTest {

    private final TermsXsrfTokenFilter filter = new TermsXsrfTokenFilter();

    @Test
    void termsReturnsCookieTokenAndSignupStillValidatesIt() {
        MockServerWebExchange terms = terms("csrf-token");
        filter.filter(terms, e -> e.getResponse().setComplete()).block();

        String token = terms.getResponse().getHeaders().getFirst(CsrfFilter.HEADER);
        assertThat(token).isEqualTo("csrf-token");
        assertThat(terms.getResponse().getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(terms.getResponse().getCookies()).isEmpty();

        MockServerWebExchange signup = MockServerWebExchange.from(MockServerHttpRequest.post("/api/auth/signup")
                .cookie(new HttpCookie("XSRF-TOKEN", "csrf-token")).header(CsrfFilter.HEADER, token));
        AtomicBoolean accepted = new AtomicBoolean();
        new CsrfFilter().filter(signup, e -> { accepted.set(true); return e.getResponse().setComplete(); }).block();
        assertThat(accepted).isTrue();
    }

    @Test
    void missingCookieDoesNotInventAToken() {
        MockServerWebExchange terms = MockServerWebExchange.from(MockServerHttpRequest.get("/api/members/terms"));
        filter.filter(terms, e -> e.getResponse().setComplete()).block();

        assertThat(terms.getResponse().getHeaders().getFirst(CsrfFilter.HEADER)).isNull();
        assertThat(terms.getResponse().getCookies()).isEmpty();
        assertThat(terms.getResponse().getHeaders().getCacheControl()).isEqualTo("no-store");
    }

    @Test
    void failedTermsResponseDoesNotExposeToken() {
        MockServerWebExchange terms = terms("csrf-token");
        filter.filter(terms, e -> {
            e.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
            return e.getResponse().setComplete();
        }).block();
        assertThat(terms.getResponse().getHeaders().getFirst(CsrfFilter.HEADER)).isNull();
    }

    @Test
    void otherEndpointsDoNotExposeToken() {
        MockServerWebExchange other = MockServerWebExchange.from(MockServerHttpRequest.get("/api/members/terms/TOS")
                .cookie(new HttpCookie("XSRF-TOKEN", "csrf-token")));
        filter.filter(other, e -> e.getResponse().setComplete()).block();
        assertThat(other.getResponse().getHeaders().getFirst(CsrfFilter.HEADER)).isNull();
    }

    @Test
    void runsAfterCorsAndBeforeAuthentication() {
        assertThat(GatewayFilterOrder.CORS).isLessThan(filter.getOrder());
        assertThat(filter.getOrder()).isLessThan(GatewayFilterOrder.ACCESS_TOKEN);
    }

    private static MockServerWebExchange terms(String token) {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/api/members/terms")
                .cookie(new HttpCookie("XSRF-TOKEN", token)));
    }
}
