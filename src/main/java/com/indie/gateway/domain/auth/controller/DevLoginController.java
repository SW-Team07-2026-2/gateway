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
 * 로컬 프론트 -> Gateway -> Member 토큰 발급 API.
 * local 프로파일에서만 OAuth 인증 없이 로그인 흐름을 확인합니다.
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

    /**
     * 로컬 회원의 토큰을 발급받아 로그인 쿠키를 설정합니다.
     *
     * @param nickname 로컬 테스트 회원 닉네임
     * @param exchange 현재 HTTP 요청과 응답
     */
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
