package com.indie.gateway.domain.auth.service;

import com.indie.gateway.global.config.GatewayProperties;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

/** 인증 쿠키의 속성과 만료 쿠키 생성을 관리합니다. */
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

    /**
     * 접근 토큰 쿠키를 만듭니다.
     *
     * @param value 쿠키에 저장할 값
     */
    public ResponseCookie at(String value) {
        return build(AT, value, true, Duration.ofSeconds(props.atMaxAge()));
    }

    /**
     * 리프레시 토큰 쿠키를 만듭니다.
     *
     * @param value 쿠키에 저장할 값
     */
    public ResponseCookie rt(String value) {
        return build(RT, value, true, Duration.ofSeconds(props.rtMaxAge()));
    }

    /**
     * 프론트가 읽을 수 있는 CSRF 토큰 쿠키를 만듭니다.
     *
     * @param value 쿠키에 저장할 값
     */
    public ResponseCookie xsrf(String value) {
        return build(XSRF, value, false, Duration.ofSeconds(props.rtMaxAge()));
    }

    /**
     * 가입 API 경로에 사용할 HttpOnly 가입 토큰 쿠키를 만듭니다.
     *
     * @param value 쿠키에 저장할 값
     */
    public ResponseCookie signup(String value) {
        return build(SIGNUP, value, true, Duration.ofSeconds(props.signupMaxAge()), SIGNUP_PATH, signupSameSite());
    }

    /** 가입 토큰을 삭제할 만료 쿠키를 만듭니다. */
    public ResponseCookie expireSignup() {
        return build(SIGNUP, "", true, Duration.ZERO, SIGNUP_PATH, signupSameSite());
    }

    private String signupSameSite() {
        return "None".equalsIgnoreCase(props.sameSite()) ? "None" : "Strict";
    }

    /**
     * OAuth 인가 요청을 보관할 쿠키를 만듭니다.
     *
     * @param value 쿠키에 저장할 값
     */
    public ResponseCookie oauthRequest(String value) {
        return build(OAUTH_REQUEST, value, true, OAUTH_REQUEST_MAX_AGE);
    }

    /**
     * 지정한 인증 쿠키를 삭제할 만료 쿠키를 만듭니다.
     *
     * @param name 만료시킬 쿠키 이름
     */
    public ResponseCookie expire(String name) {
        return build(name, "", !XSRF.equals(name), Duration.ZERO);
    }

    /** 새 CSRF 토큰을 생성합니다. */
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
            builder.domain(props.domain());   // 운영은 ".<도메인>" - 앞의 점
        }
        return builder.build();
    }
}
