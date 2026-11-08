package com.indie.gateway.domain.auth.service;

import com.indie.gateway.global.config.GatewayProperties;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

/**
 * 쿠키 생성은 이 클래스 한 곳에서만 (§8-3).
 * 흩어지면 한 곳만 Domain 의 점을 빼먹어도 그 경로에서만 401 이 나는, 가장 찾기 힘든 버그가 생깁니다.
 */
@Component
public class CookieFactory {

    public static final String AT = "AT";
    public static final String RT = "RT";
    public static final String XSRF = "XSRF-TOKEN";
    public static final String SIGNUP = "SIGNUP";
    public static final String SIGNUP_PATH = "/api/auth/signup";
    public static final String OAUTH_REQUEST = "OAUTH2_AUTH_REQ";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration OAUTH_REQUEST_MAX_AGE = Duration.ofMinutes(5);

    private final GatewayProperties.Cookie props;

    public CookieFactory(GatewayProperties props) {
        this.props = props.cookie();
    }

    public ResponseCookie at(String value) {
        return build(AT, value, true, Duration.ofSeconds(props.atMaxAge()));
    }

    public ResponseCookie rt(String value) {
        return build(RT, value, true, Duration.ofSeconds(props.rtMaxAge()));
    }

    /** 🔴 HttpOnly 를 붙이지 않습니다 — JS 가 읽어 X-XSRF-TOKEN 헤더로 되돌려야 합니다 */
    public ResponseCookie xsrf(String value) {
        return build(XSRF, value, false, Duration.ofSeconds(props.rtMaxAge()));
    }

    /**
     * 사인업 토큰 — 가입 API 에만 실리도록 Path 를 좁힙니다. 로컬 프론트 연동 중에는 None 을 사용합니다.
     */
    public ResponseCookie signup(String value) {
        return build(SIGNUP, value, true, Duration.ofSeconds(props.signupMaxAge()), SIGNUP_PATH, signupSameSite());
    }

    public ResponseCookie expireSignup() {
        return build(SIGNUP, "", true, Duration.ZERO, SIGNUP_PATH, signupSameSite());
    }

    private String signupSameSite() {
        return "None".equalsIgnoreCase(props.sameSite()) ? "None" : "Strict";
    }

    /** OAuth 인가 요청 보관 — 세션 대신 쿠키 (replica 2 에서 authorization_request_not_found 방지) */
    public ResponseCookie oauthRequest(String value) {
        return build(OAUTH_REQUEST, value, true, OAUTH_REQUEST_MAX_AGE);
    }

    public ResponseCookie expire(String name) {
        return build(name, "", !XSRF.equals(name), Duration.ZERO);
    }

    public static String newXsrfToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private ResponseCookie build(String name, String value, boolean httpOnly, Duration maxAge) {
        return build(name, value, httpOnly, maxAge, "/", props.sameSite());
    }

    private ResponseCookie build(String name, String value, boolean httpOnly, Duration maxAge, String path, String sameSite) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(name, value)
                .path(path)
                .httpOnly(httpOnly)
                .secure(props.secure())
                .sameSite(sameSite)
                .maxAge(maxAge);
        if (props.domain() != null && !props.domain().isBlank()) {
            builder.domain(props.domain());   // ★ 운영은 ".<도메인>" — 앞의 점
        }
        return builder.build();
    }
}
