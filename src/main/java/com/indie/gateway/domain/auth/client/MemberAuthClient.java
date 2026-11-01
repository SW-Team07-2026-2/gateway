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
 * Member 내부 API 호출 (§8-4) — X-Internal-Token · X-Correlation-Id 첨부.
 * 🔴 재시도 0회 — refresh 는 RT 회전이라 재시도가 곧 재사용 탐지를 유발합니다.
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

    public Mono<IssueResponse> issue(IssueRequest request) {
        return call(HttpMethod.POST, "/internal/auth/issue", request, IssueResponse.class);
    }

    public Mono<IssueResponse> signup(SignupRequest request) {
        return call(HttpMethod.POST, "/internal/auth/signup", request, IssueResponse.class);
    }

    public Mono<RefreshResponse> refresh(String refreshToken) {
        return call(HttpMethod.POST, "/internal/auth/refresh", Map.of("refreshToken", refreshToken), RefreshResponse.class);
    }

    public Mono<Void> revoke(RevokeRequest request) {
        return call(HttpMethod.POST, "/internal/auth/revoke", request, Map.class).then();
    }

    public Mono<ProfileResponse> profile(String userId) {
        return call(HttpMethod.GET, "/internal/members/" + userId + "/profile", null, ProfileResponse.class);
    }

    /** Member 응답은 ApiResponse — 성공이면 result 를 꺼내고, 실패면 code 로 MemberCallException */
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

    /** Member 가 4xx/5xx 로 응답한 경우. 연결 실패 · 타임아웃은 원래 예외 그대로 전달됩니다 */
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
