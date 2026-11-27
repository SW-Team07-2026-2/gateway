package com.indie.gateway.domain.auth.client;

import com.indie.gateway.domain.auth.dto.request.IssueRequest;
import com.indie.gateway.domain.auth.dto.request.RevokeRequest;
import com.indie.gateway.domain.auth.dto.request.SignupRequest;
import com.indie.gateway.domain.auth.dto.response.IssueResponse;
import com.indie.gateway.domain.auth.dto.response.ProfileResponse;
import com.indie.gateway.domain.auth.dto.response.RefreshResponse;
import com.indie.gateway.domain.auth.service.InternalTokenIssuer;
import com.indie.gateway.global.common.response.ApiResponse;
import com.indie.gateway.global.filter.CorrelationIdFilter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.ResolvableType;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Gateway -> Member 내부 인증 API 호출. 내부 토큰과 요청 추적 ID를 전달합니다.
 * 리프레시 토큰 재사용 탐지를 피하기 위해 재시도하지 않습니다.
 */
@Component
public class MemberAuthClient {

    private static final ParameterizedTypeReference<Map<String, Object>> ERROR_BODY = new ParameterizedTypeReference<>() {};

    private final WebClient webClient;
    private final InternalTokenIssuer internalTokenIssuer;

    public MemberAuthClient(@Qualifier("memberWebClient") WebClient webClient, InternalTokenIssuer internalTokenIssuer) {
        this.webClient = webClient;
        this.internalTokenIssuer = internalTokenIssuer;
    }

    /**
     * Member에 로그인 토큰 발급을 요청합니다.
     *
     * @param request OAuth 제공자와 회원 프로필
     */
    public Mono<IssueResponse> issue(IssueRequest request) {
        return call(HttpMethod.POST, "/internal/auth/issue", request, IssueResponse.class);
    }

    /**
     * Member에 회원 가입과 토큰 발급을 요청합니다.
     *
     * @param request 가입 토큰, 아티스트와 약관 동의 내역
     */
    public Mono<IssueResponse> signup(SignupRequest request) {
        return call(HttpMethod.POST, "/internal/auth/signup", request, IssueResponse.class);
    }

    /**
     * Member에 리프레시 토큰 회전을 요청합니다.
     *
     * @param refreshToken 회전할 리프레시 토큰
     */
    public Mono<RefreshResponse> refresh(String refreshToken) {
        return call(HttpMethod.POST, "/internal/auth/refresh", Map.of("refreshToken", refreshToken), RefreshResponse.class);
    }

    /**
     * Member에 토큰 폐기를 요청합니다.
     *
     * @param request 폐기할 접근 토큰과 리프레시 토큰 정보
     */
    public Mono<Void> revoke(RevokeRequest request) {
        return call(HttpMethod.POST, "/internal/auth/revoke", request, Map.class).then();
    }

    /**
     * Member에서 회원 프로필을 조회합니다.
     *
     * @param userId 조회하거나 처리할 회원 ID
     */
    public Mono<ProfileResponse> profile(String userId) {
        return call(HttpMethod.GET, "/internal/members/" + userId + "/profile", null, ProfileResponse.class);
    }

    /** 탈퇴 표시가 유실됐을 때도 차단하도록 회원 DB의 현재 존재 여부를 조회합니다. */
    public Mono<Boolean> exists(String userId) {
        return call(HttpMethod.GET, "/internal/members/" + userId + "/exists", null, Boolean.class);
    }

    /** Member 응답은 ApiResponse - 성공이면 result 를 꺼내고, 실패면 code 로 MemberCallException */
    private <T> Mono<T> call(HttpMethod method, String path, Object body, Class<T> type) {
        ParameterizedTypeReference<ApiResponse<T>> wrapped =
                ParameterizedTypeReference.forType(ResolvableType.forClassWithGenerics(ApiResponse.class, type).getType());
        return Mono.deferContextual(ctx -> {
            WebClient.RequestBodySpec spec = webClient.method(method).uri(path)
                    .header("X-Internal-Token", internalTokenIssuer.issue());
            ctx.<String>getOrEmpty(CorrelationIdFilter.CONTEXT_KEY)
                    .ifPresent(id -> spec.header(CorrelationIdFilter.HEADER, id));
            WebClient.RequestHeadersSpec<?> ready = body == null ? spec : spec.bodyValue(body);
            return ready.exchangeToMono(response -> response.statusCode().is2xxSuccessful()
                    ? response.bodyToMono(wrapped).flatMap(r -> Mono.justOrEmpty(r.result()))
                    : response.bodyToMono(ERROR_BODY).defaultIfEmpty(Map.of())
                            .flatMap(err -> Mono.error(new MemberCallException(
                                    response.statusCode().value(), String.valueOf(err.getOrDefault("code", "UNKNOWN"))))));
        });
    }

    /** Member 가 4xx/5xx 로 응답한 경우. 연결 실패, 타임아웃은 원래 예외 그대로 전달됩니다 */
    public static class MemberCallException extends RuntimeException {
        private final int status;
        private final String code;

        public MemberCallException(int status, String code) {
            super("member responded " + status + " " + code, null, false, false);
            this.status = status;
            this.code = code;
        }

        public int status() { return status; }

        public String code() { return code; }
    }
}
