package com.indie.gateway.domain.auth.controller;

import com.indie.gateway.domain.auth.client.MemberAuthClient.MemberCallException;
import com.indie.gateway.domain.auth.client.MemberAuthClient;
import com.indie.gateway.domain.auth.dto.request.RevokeRequest;
import com.indie.gateway.domain.auth.dto.request.SignupRequest;
import com.indie.gateway.domain.auth.dto.response.MeResponse;
import com.indie.gateway.domain.auth.entity.TokenClaims;
import com.indie.gateway.domain.auth.exception.AuthErrorCode;
import com.indie.gateway.domain.auth.repository.ProfileCache;
import com.indie.gateway.domain.auth.service.CookieFactory;
import com.indie.gateway.global.common.exception.CommonErrorCode;
import com.indie.gateway.global.common.response.ApiResponse;
import com.indie.gateway.global.common.response.ErrorResponses;
import com.indie.gateway.global.common.util.GatewayAttributes;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Gateway 자체 엔드포인트 — /api/auth/me · logout · refresh · signup (§7-3 ~ §7-5).
 * 쿠키를 굽는 주체가 Gateway 이므로 Member 로 라우팅하지 않습니다.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final ProfileCache profileCache;
    private final MemberAuthClient memberAuthClient;
    private final CookieFactory cookieFactory;
    private final MeterRegistry meterRegistry;

    public AuthController(ProfileCache profileCache, MemberAuthClient memberAuthClient,
                          CookieFactory cookieFactory, MeterRegistry meterRegistry) {
        this.profileCache = profileCache;
        this.memberAuthClient = memberAuthClient;
        this.cookieFactory = cookieFactory;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 프론트의 유일한 상태 확인 수단. 비로그인 401 은 AccessTokenFilter 가 냅니다 (에러가 아니라 정상 분기).
     * 🔴 Member 장애가 "로그인 안 된 상태"로 보이면 안 됩니다 — 200 + stale:true 로 클레임만 돌려줍니다.
     */
    @GetMapping("/me")
    public Mono<ApiResponse<MeResponse>> me(ServerWebExchange exchange) {
        TokenClaims claims = GatewayAttributes.claims(exchange);
        String xsrfToken = ensureXsrfToken(exchange);
        return profileCache.get(claims.sub())
                .map(p -> new MeResponse(claims.sub(), p.nickname(), p.profileImageUrl(), claims.roles(), xsrfToken, null))
                .onErrorResume(e -> {
                    log.error("auth_me_profile_failed userId={} error={}", claims.sub(), e.toString());
                    return Mono.just(new MeResponse(claims.sub(), null, null, claims.roles(), xsrfToken, true));
                })
                .map(ApiResponse::onSuccess);
    }

    /** 🔴 Member 호출이 실패해도 쿠키는 지우고 200 — 로그아웃이 실패하는 UX 가 더 나쁩니다 */
    @PostMapping("/logout")
    public Mono<ApiResponse<Map<String, Object>>> logout(ServerWebExchange exchange) {
        TokenClaims claims = GatewayAttributes.claims(exchange);
        HttpCookie rt = exchange.getRequest().getCookies().getFirst(CookieFactory.RT);
        RevokeRequest request = new RevokeRequest(
                claims == null ? null : Long.valueOf(claims.sub()),
                claims == null ? null : claims.jti(),
                claims == null ? null : claims.exp().getEpochSecond(),
                false,
                rt == null ? null : rt.getValue());
        return memberAuthClient.revoke(request)
                .onErrorResume(e -> {
                    log.error("logout_revoke_failed error={}", e.toString());
                    meterRegistry.counter("gateway.logout.revoke.failure").increment();
                    return Mono.empty();
                })
                .then(Mono.fromSupplier(() -> {
                    clearAuthCookies(exchange);
                    return ApiResponse.onSuccess(Map.<String, Object>of("ok", true));
                }));
    }

    /**
     * 수동 폴백 — 선제 갱신(③)이 실패했을 때 프론트가 호출합니다.
     * Member 가 RT 를 거절하면 401 + 쿠키 삭제 (프론트가 로그인 화면으로 보냄).
     * Member 에 닿지 못한 경우는 503 으로 두고 쿠키를 지우지 않습니다 — 인프라 장애가 로그아웃이 되지 않도록.
     */
    @PostMapping("/refresh")
    public Mono<Void> refresh(ServerWebExchange exchange) {
        HttpCookie rt = exchange.getRequest().getCookies().getFirst(CookieFactory.RT);
        if (rt == null || rt.getValue().isBlank()) {
            clearAuthCookies(exchange);
            return ErrorResponses.write(exchange, AuthErrorCode.REFRESH_INVALID);
        }
        return memberAuthClient.refresh(rt.getValue())
                .flatMap(tokens -> {
                    exchange.getResponse().addCookie(cookieFactory.at(tokens.accessToken()));
                    exchange.getResponse().addCookie(cookieFactory.rt(tokens.refreshToken()));
                    return ErrorResponses.write(exchange, HttpStatus.OK, ApiResponse.onSuccess(Map.of("ok", true)));
                })
                .onErrorResume(MemberCallException.class, e -> {
                    if (e.status() >= 500) {
                        return ErrorResponses.write(exchange, CommonErrorCode.SERVICE_UNAVAILABLE);
                    }
                    clearAuthCookies(exchange);
                    return ErrorResponses.write(exchange, "REFRESH_REUSED".equals(e.code())
                            ? AuthErrorCode.REFRESH_REUSED : AuthErrorCode.REFRESH_INVALID);
                })
                .onErrorResume(e -> {
                    log.error("manual_refresh_failed error={}", e.toString());
                    return ErrorResponses.write(exchange, CommonErrorCode.SERVICE_UNAVAILABLE);
                });
    }

    /**
     * 가입 완료 — 밴드 고르기의 「완료」 · 「건너뛰기」. SIGNUP 쿠키(사인업 토큰) + CSRF 헤더 · { artistIds }.
     * Member 가 회원 · 팔로우 · 동의 이력을 만들면 AT · RT 를 굽고 SIGNUP 은 지웁니다.
     * 토큰이 없거나 만료 · 이미 사용이면 401 SIGNUP_EXPIRED, 위조 · 형식 오류면 401 SIGNUP_INVALID (프론트는 둘 다 로그인 화면으로).
     */
    @PostMapping("/signup")
    public Mono<Void> signup(@RequestBody(required = false) SignupBody body, ServerWebExchange exchange) {
        HttpCookie signup = exchange.getRequest().getCookies().getFirst(CookieFactory.SIGNUP);
        if (signup == null || signup.getValue().isBlank()) {
            return ErrorResponses.write(exchange, AuthErrorCode.SIGNUP_EXPIRED);
        }
        List<Long> artistIds = body == null || body.artistIds() == null ? List.of() : body.artistIds();
        return memberAuthClient.signup(new SignupRequest(signup.getValue(), artistIds))
                .flatMap(issued -> {
                    exchange.getResponse().addCookie(cookieFactory.at(issued.accessToken()));
                    exchange.getResponse().addCookie(cookieFactory.rt(issued.refreshToken()));
                    exchange.getResponse().addCookie(cookieFactory.expireSignup());
                    Map<String, Object> result = new HashMap<>();
                    result.put("userId", issued.userId());
                    result.put("nickname", issued.nickname());
                    result.put("newUser", issued.newUser());
                    return ErrorResponses.write(exchange, HttpStatus.OK, ApiResponse.onSuccess(result));
                })
                .onErrorResume(MemberCallException.class, e -> {
                    if (e.status() >= 500) {
                        return ErrorResponses.write(exchange, CommonErrorCode.SERVICE_UNAVAILABLE);
                    }
                    if (e.status() == 401) {
                        exchange.getResponse().addCookie(cookieFactory.expireSignup());
                        return ErrorResponses.write(exchange, "SIGNUP_INVALID".equals(e.code())
                                ? AuthErrorCode.SIGNUP_INVALID : AuthErrorCode.SIGNUP_EXPIRED);
                    }
                    return ErrorResponses.write(exchange, CommonErrorCode.INVALID_REQUEST);   // artistIds 50개 초과 등
                })
                .onErrorResume(e -> {
                    log.error("signup_failed error={}", e.toString());
                    return ErrorResponses.write(exchange, CommonErrorCode.SERVICE_UNAVAILABLE);
                });
    }

    /** 밴드 고르기에서 고른 아티스트 — 건너뛰면 빈 배열 · 본문 없음 */
    public record SignupBody(List<Long> artistIds) {}

    private String ensureXsrfToken(ServerWebExchange exchange) {
        HttpCookie existing = exchange.getRequest().getCookies().getFirst(CookieFactory.XSRF);
        if (existing != null && !existing.getValue().isBlank()) {
            return existing.getValue();
        }
        String token = CookieFactory.newXsrfToken();
        exchange.getResponse().addCookie(cookieFactory.xsrf(token));
        return token;
    }

    private void clearAuthCookies(ServerWebExchange exchange) {
        exchange.getResponse().addCookie(cookieFactory.expire(CookieFactory.AT));
        exchange.getResponse().addCookie(cookieFactory.expire(CookieFactory.RT));
        exchange.getResponse().addCookie(cookieFactory.expire(CookieFactory.XSRF));
    }
}
