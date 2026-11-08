package com.indie.gateway.domain.auth.controller;

import com.indie.gateway.domain.auth.service.CookieFactory;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import static org.assertj.core.api.Assertions.assertThat;

/** 11/08 GET /api/auth/signup — 사인업 토큰이 없으면 가입 화면을 이어 가지 못하게 401 */
class SignupStatusTest {

    private final AuthController controller = new AuthController(null, null, null, null);

    @Test
    void okWhenSignupCookieExists() {
        MockServerWebExchange exchange = exchange(new HttpCookie(CookieFactory.SIGNUP, "signup-token"));

        controller.signupStatus(exchange).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchange.getResponse().getBodyAsString().block()).contains("\"ok\":true");
    }

    @Test
    void unauthorizedWithoutSignupCookie() {
        MockServerWebExchange exchange = exchange();

        controller.signupStatus(exchange).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange.getResponse().getBodyAsString().block()).contains("SIGNUP_EXPIRED");
    }

    /** 가입 완료 · 401 때 Gateway 가 값을 비워 지운 쿠키가 남아 있어도 없는 것으로 봅니다 */
    @Test
    void unauthorizedWithBlankSignupCookie() {
        MockServerWebExchange exchange = exchange(new HttpCookie(CookieFactory.SIGNUP, ""));

        controller.signupStatus(exchange).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private static MockServerWebExchange exchange(HttpCookie... cookies) {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/api/auth/signup").cookie(cookies));
    }
}
