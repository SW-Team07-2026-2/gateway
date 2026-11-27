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
 * 프론트 -> Gateway 인증 API -> Member 내부 API.
 * Gateway에서 인증 쿠키를 발급하고 삭제합니다.
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
     * 로그인 상태를 조회합니다. Member 조회 실패 시 클레임과 stale=true를 반환합니다.
     *
     * @param exchange 현재 HTTP 요청과 응답
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

    /**
     * Member 토큰 폐기를 요청합니다. 호출 실패 여부와 관계없이 인증 쿠키를 삭제합니다.
     *
     * @param exchange 현재 HTTP 요청과 응답
     */
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
     * 프론트 요청으로 토큰을 갱신합니다. 토큰 거절 시 쿠키를 삭제하고, 서버 장애 시에는 유지합니다.
     *
     * @param exchange 현재 HTTP 요청과 응답
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
     * SIGNUP 쿠키 존재 여부로 가입 화면 진입을 확인합니다. 토큰 검증은 가입 완료 시 수행합니다.
     *
     * @param exchange 현재 HTTP 요청과 응답
     */
    @GetMapping("/signup")
    public Mono<Void> signupStatus(ServerWebExchange exchange) {
        HttpCookie signup = exchange.getRequest().getCookies().getFirst(CookieFactory.SIGNUP);
        if (signup == null || signup.getValue().isBlank()) {
            return ErrorResponses.write(exchange, AuthErrorCode.SIGNUP_EXPIRED);
        }
        return ErrorResponses.write(exchange, HttpStatus.OK, ApiResponse.onSuccess(Map.of("ok", true)));
    }

    /**
     * Member에 가입 정보와 동의 내역을 전달하고 인증 쿠키를 발급합니다. 약관 오류 시 가입 쿠키를 유지합니다.
     *
     * @param body 가입할 아티스트와 약관 동의 내역
     * @param exchange 현재 HTTP 요청과 응답
     */
    @PostMapping("/signup")
    public Mono<Void> signup(@RequestBody(required = false) SignupBody body, ServerWebExchange exchange) {
        HttpCookie signup = exchange.getRequest().getCookies().getFirst(CookieFactory.SIGNUP);
        if (signup == null || signup.getValue().isBlank()) {
            return ErrorResponses.write(exchange, AuthErrorCode.SIGNUP_EXPIRED);
        }
        List<Long> artistIds = body == null || body.artistIds() == null ? List.of() : body.artistIds();
        List<SignupRequest.Agreement> agreements = body == null || body.agreements() == null ? List.of() : body.agreements();
        return memberAuthClient.signup(new SignupRequest(signup.getValue(), artistIds, agreements))
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
                    if ("REQUIRED_AGREEMENT_MISSING".equals(e.code())) {
                        return ErrorResponses.write(exchange, AuthErrorCode.REQUIRED_AGREEMENT_MISSING);
                    }
                    if ("TERMS_OUTDATED".equals(e.code())) {
                        return ErrorResponses.write(exchange, AuthErrorCode.TERMS_OUTDATED);
                    }
                    return ErrorResponses.write(exchange, CommonErrorCode.INVALID_REQUEST);   // artistIds 50개 초과 등
                })
                .onErrorResume(e -> {
                    log.error("signup_failed error={}", e.toString());
                    return ErrorResponses.write(exchange, CommonErrorCode.SERVICE_UNAVAILABLE);
                });
    }

    /** 가입할 아티스트 목록과 약관 동의 내역. */
    public record SignupBody(List<Long> artistIds, List<SignupRequest.Agreement> agreements) {}

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
