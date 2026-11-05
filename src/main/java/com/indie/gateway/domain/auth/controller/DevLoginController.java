package com.indie.gateway.domain.auth.controller;

import com.indie.gateway.domain.auth.client.MemberAuthClient;
import com.indie.gateway.domain.auth.dto.request.IssueRequest;
import com.indie.gateway.domain.auth.service.CookieFactory;
import com.indie.gateway.global.common.response.ApiResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 🔴 local 프로파일 전용 — 카카오 키 없이 로그인 흐름을 재현합니다.
 * OAuth 콜백의 ③~④ 를 그대로 탑니다 — 기존 회원이면 AT · RT · XSRF, 처음이면 SIGNUP · XSRF (이어서 POST /api/auth/signup).
 * IdP 단계만 건너뜁니다.
 * 운영 프로파일에는 빈이 없으므로 /dev/login 은 404 입니다.
 */
@RestController
@Profile("local")
public class DevLoginController {

    private final MemberAuthClient memberAuthClient;
    private final CookieFactory cookieFactory;

    public DevLoginController(MemberAuthClient memberAuthClient, CookieFactory cookieFactory) {
        this.memberAuthClient = memberAuthClient;
        this.cookieFactory = cookieFactory;
    }

    @GetMapping("/dev/login")
    public Mono<ApiResponse<Map<String, Object>>> login(@RequestParam(defaultValue = "로컬유저") String nickname, ServerWebExchange exchange) {
        IssueRequest request = new IssueRequest("KAKAO", "local-dev-" + nickname, null, nickname, null);
        return memberAuthClient.issue(request).map(issued -> {
            String xsrf = CookieFactory.newXsrfToken();
            exchange.getResponse().addCookie(cookieFactory.xsrf(xsrf));
            if (issued.newUser()) {
                exchange.getResponse().addCookie(cookieFactory.signup(issued.signupToken()));
                return ApiResponse.onSuccess(Map.<String, Object>of("newUser", true, "xsrfToken", xsrf));
            }
            exchange.getResponse().addCookie(cookieFactory.at(issued.accessToken()));
            exchange.getResponse().addCookie(cookieFactory.rt(issued.refreshToken()));
            exchange.getResponse().addCookie(cookieFactory.expireSignup());
            return ApiResponse.onSuccess(Map.<String, Object>of("userId", issued.userId(), "nickname", issued.nickname(),
                    "newUser", false, "xsrfToken", xsrf));
        });
    }
}
