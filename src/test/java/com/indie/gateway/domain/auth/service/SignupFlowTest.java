package com.indie.gateway.domain.auth.service;

import com.indie.gateway.TestProps;
import com.indie.gateway.domain.auth.dto.request.IssueRequest;
import com.indie.gateway.domain.auth.dto.request.IssueRequest.Agreement;
import com.indie.gateway.global.common.util.PathRules;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseCookie;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 10/28 가입 — 사인업 토큰 쿠키 · 공개 경로 · 카카오 프로필 매핑 */
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

    /** 아직 회원이 아니라 AT 가 없는 두 경로 — 가입 완료 · 밴드 고르기 */
    @Test
    void signupPathsArePublic() {
        assertThat(PathRules.isPublic(HttpMethod.POST, "/api/auth/signup")).isTrue();
        assertThat(PathRules.isPublic(HttpMethod.GET, "/api/chatbot/artists/similar")).isTrue();
        assertThat(PathRules.isPublic(HttpMethod.POST, "/api/chatbot")).isFalse();
        assertThat(PathRules.isCsrfExempt("/api/auth/signup")).isFalse();   // 🔴 CSRF 는 그대로
    }

    @Test
    void kakaoProfileWithAgreements() {
        List<Agreement> agreements = List.of(new Agreement("tos", true, "2026-10-28T10:00:00Z"));
        IssueRequest r = OAuth2LoginHandler.toIssueRequest("kakao", Map.of("id", 42L,
                "kakao_account", Map.of("email", "a@b.c", "profile", Map.of("nickname", "새벽"))), agreements);

        assertThat(r).isEqualTo(new IssueRequest("KAKAO", "42", "a@b.c", "새벽", null, agreements));
    }

    /** 10/28 로그인은 카카오만 */
    @Test
    void googleIsNotSupported() {
        assertThatThrownBy(() -> OAuth2LoginHandler.toIssueRequest("google", Map.of("sub", "1"), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
