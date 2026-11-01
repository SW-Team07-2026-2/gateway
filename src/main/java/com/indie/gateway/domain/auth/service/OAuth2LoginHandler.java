package com.indie.gateway.domain.auth.service;

import com.indie.gateway.domain.auth.client.KakaoServiceTermsClient;
import com.indie.gateway.domain.auth.client.MemberAuthClient;
import com.indie.gateway.domain.auth.dto.request.IssueRequest;
import com.indie.gateway.domain.auth.dto.request.IssueRequest.Agreement;
import com.indie.gateway.global.config.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.server.WebFilterExchange;
import org.springframework.security.web.server.authentication.ServerAuthenticationFailureHandler;
import org.springframework.security.web.server.authentication.ServerAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * OAuth 콜백 GET /oauth2/code/{provider} 의 후반부 (§7-2).
 * code 교환 · state 검증 · IdP 프로필 조회는 Spring Security 가 끝낸 상태로 들어옵니다.
 * <pre>
 * ③ 카카오 약관 동의 내역 (카카오 싱크) → Member POST /internal/auth/issue
 * ④ 기존 회원 → AT · RT · XSRF-TOKEN Set-Cookie → ⑤ 302 프론트 홈
 *    처음      → SIGNUP(사인업 토큰) · XSRF-TOKEN Set-Cookie → ⑤ 302 프론트 가입(밴드 고르기) — 회원은 POST /api/auth/signup 에서 생김
 * </pre>
 * 프론트는 사인업 토큰을 읽을 수 없습니다(HttpOnly) — 처음인지는 302 목적지로 압니다.
 * 🔴 에러 메시지를 쿼리에 그대로 넣지 않습니다 — 고정 코드만. 리다이렉트 대상도 설정의 고정 URL 만.
 */
@Component
public class OAuth2LoginHandler implements ServerAuthenticationSuccessHandler, ServerAuthenticationFailureHandler {

    /** 콜백 요청 안에서만 IdP access_token 을 넘겨받는 exchange 속성 (SecurityConfig 가 넣음) */
    public static final String AUTHORIZED_CLIENT = OAuth2LoginHandler.class.getName() + ".authorizedClient";

    private static final Logger log = LoggerFactory.getLogger(OAuth2LoginHandler.class);

    private final MemberAuthClient memberAuthClient;
    private final KakaoServiceTermsClient kakaoServiceTerms;
    private final CookieFactory cookieFactory;
    private final GatewayProperties.OAuth props;

    public OAuth2LoginHandler(MemberAuthClient memberAuthClient, KakaoServiceTermsClient kakaoServiceTerms,
                              CookieFactory cookieFactory, GatewayProperties props) {
        this.memberAuthClient = memberAuthClient;
        this.kakaoServiceTerms = kakaoServiceTerms;
        this.cookieFactory = cookieFactory;
        this.props = props.oauth();
    }

    @Override
    public Mono<Void> onAuthenticationSuccess(WebFilterExchange webFilterExchange, Authentication authentication) {
        ServerWebExchange exchange = webFilterExchange.getExchange();
        OAuth2AuthenticationToken token = (OAuth2AuthenticationToken) authentication;
        String provider = token.getAuthorizedClientRegistrationId();
        OAuth2AuthorizedClient client = exchange.getAttribute(AUTHORIZED_CLIENT);
        Mono<List<Agreement>> agreements = client == null ? Mono.just(List.of())
                : kakaoServiceTerms.agreed(client.getAccessToken().getTokenValue());
        return agreements
                .map(a -> toIssueRequest(provider, token.getPrincipal().getAttributes(), a))
                .flatMap(memberAuthClient::issue)
                .flatMap(issued -> {
                    exchange.getResponse().addCookie(cookieFactory.xsrf(CookieFactory.newXsrfToken()));
                    if (issued.newUser()) {
                        exchange.getResponse().addCookie(cookieFactory.signup(issued.signupToken()));
                        exchange.getResponse().addCookie(cookieFactory.expire(CookieFactory.AT));   // 다른 계정으로 로그인돼 있던 흔적
                        exchange.getResponse().addCookie(cookieFactory.expire(CookieFactory.RT));
                        return redirect(exchange, props.signupRedirect());
                    }
                    exchange.getResponse().addCookie(cookieFactory.at(issued.accessToken()));
                    exchange.getResponse().addCookie(cookieFactory.rt(issued.refreshToken()));
                    exchange.getResponse().addCookie(cookieFactory.expireSignup());   // 같은 브라우저에서 그만둔 가입의 토큰
                    return redirect(exchange, props.successRedirect());
                })
                .onErrorResume(e -> {
                    log.error("oauth_signup_failed provider={} error={}", provider, e.toString());
                    return redirect(exchange, props.failureRedirect() + "?error=signup_failed");
                });
    }

    @Override
    public Mono<Void> onAuthenticationFailure(WebFilterExchange webFilterExchange, AuthenticationException exception) {
        log.warn("oauth_failed error={}", exception.toString());
        return redirect(webFilterExchange.getExchange(), props.failureRedirect() + "?error=oauth_failed");
    }

    /** 카카오 프로필 → Member 요청. 이메일 미동의면 email 은 null (Member 는 provider+providerUid 로 식별) · 10/28 로그인은 카카오만 */
    @SuppressWarnings("unchecked")
    static IssueRequest toIssueRequest(String registrationId, Map<String, Object> attrs, List<Agreement> agreements) {
        if ("kakao".equals(registrationId)) {
            Map<String, Object> account = (Map<String, Object>) attrs.getOrDefault("kakao_account", Map.of());
            Map<String, Object> profile = (Map<String, Object>) account.getOrDefault("profile", Map.of());
            return new IssueRequest("KAKAO", String.valueOf(attrs.get("id")), (String) account.get("email"),
                    (String) profile.get("nickname"), (String) profile.get("profile_image_url"), agreements);
        }
        throw new IllegalArgumentException("unsupported provider: " + registrationId);
    }

    private static Mono<Void> redirect(ServerWebExchange exchange, String location) {
        exchange.getResponse().setStatusCode(HttpStatus.FOUND);
        exchange.getResponse().getHeaders().setLocation(URI.create(location));
        return exchange.getResponse().setComplete();
    }
}
