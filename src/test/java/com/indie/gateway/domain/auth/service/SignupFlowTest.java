package com.indie.gateway.domain.auth.service;

import com.indie.gateway.TestProps;
import com.indie.gateway.domain.auth.dto.request.IssueRequest;
import com.indie.gateway.global.common.util.PathRules;
import com.indie.gateway.global.config.GatewayProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseCookie;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 10/28 가입 — 사인업 토큰 쿠키 · 공개 경로 · 카카오 프로필 매핑 (11/05 약관은 가입 화면 · M24 공개) */
class SignupFlowTest {

    private final CookieFactory cookies = new CookieFactory(TestProps.create("/nonexistent"));

    /** ★ 가입 API 에만 실리고(Path) JS 가 못 읽고(HttpOnly) 다른 사이트에서 안 실림(Strict) */
    @Test
    void signupCookieIsNarrow() {
        ResponseCookie c = cookies.signup("tok");

        assertThat(c.getPath()).isEqualTo("/api/auth/signup");
        assertThat(c.isHttpOnly()).isTrue();
        assertThat(c.getSameSite()).isEqualTo("Strict");
        assertThat(c.getMaxAge()).isEqualTo(Duration.ofSeconds(1800));
        assertThat(cookies.expireSignup().getPath()).isEqualTo("/api/auth/signup");   // Path 가 같아야 지워집니다
        assertThat(cookies.expireSignup().getMaxAge()).isZero();
    }

    @Test
    void crossSiteCookiesAreSecureAndIncludeSignup() {
        GatewayProperties p = TestProps.create("/nonexistent");
        GatewayProperties configured = new GatewayProperties(p.domain(),
                new GatewayProperties.Cookie(".example.com", true, "None", 1800, 1209600, 1800),
                p.token(), p.internalToken(), p.memberBaseUrl(), p.oauth(), p.sanity(), p.trust(), p.redis(), p.ingest());
        CookieFactory crossSite = new CookieFactory(configured);

        for (ResponseCookie cookie : new ResponseCookie[]{crossSite.at("at"), crossSite.rt("rt"),
                crossSite.xsrf("xsrf"), crossSite.signup("signup"), crossSite.oauthRequest("request"), crossSite.expireSignup()}) {
            assertThat(cookie.getSameSite()).isEqualTo("None");
            assertThat(cookie.isSecure()).isTrue();
        }
        assertThat(crossSite.signup("signup").isHttpOnly()).isTrue();
        assertThat(crossSite.signup("signup").getPath()).isEqualTo("/api/auth/signup");
    }

    /** 아직 회원이 아니라 AT 가 없는 경로 — 가입 완료 · 밴드 고르기 · 11/05 약관 */
    @Test
    void signupPathsArePublic() {
        assertThat(PathRules.isPublic(HttpMethod.POST, "/api/auth/signup")).isTrue();
        assertThat(PathRules.isPublic(HttpMethod.GET, "/api/chatbot/artists/similar")).isTrue();
        assertThat(PathRules.isPublic(HttpMethod.POST, "/api/chatbot")).isFalse();
        assertThat(PathRules.isPublic(HttpMethod.GET, "/api/members/terms")).isTrue();
        assertThat(PathRules.isPublic(HttpMethod.GET, "/api/members/me/agreements")).isFalse();   // MY 는 로그인
        assertThat(PathRules.isPublic(HttpMethod.POST, "/api/members/terms")).isFalse();
        assertThat(PathRules.isCsrfExempt("/api/auth/signup")).isFalse();   // 🔴 CSRF 는 그대로
    }

    @Test
    void kakaoProfile() {
        IssueRequest r = OAuth2LoginHandler.toIssueRequest("kakao", Map.of("id", 42L,
                "kakao_account", Map.of("email", "a@b.c", "profile", Map.of("nickname", "새벽"))));

        assertThat(r).isEqualTo(new IssueRequest("KAKAO", "42", "a@b.c", "새벽", null));
    }

    /** 10/28 로그인은 카카오만 */
    @Test
    void googleIsNotSupported() {
        assertThatThrownBy(() -> OAuth2LoginHandler.toIssueRequest("google", Map.of("sub", "1")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
