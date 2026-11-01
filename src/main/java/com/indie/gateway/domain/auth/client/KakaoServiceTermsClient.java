package com.indie.gateway.domain.auth.client;

import com.indie.gateway.domain.auth.dto.request.IssueRequest.Agreement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * 카카오 싱크 약관 동의 내역 — GET https://kapi.kakao.com/v2/user/service_terms (기본 result=agreed_service_terms).
 * 로그인 콜백 안에서 그 사용자의 카카오 access_token 으로 한 번만 부릅니다 (토큰은 이 요청이 끝나면 버림).
 * 🔴 실패해도 로그인은 막지 않습니다 — 빈 목록으로 진행하고 경고 로그 (동의 원본은 카카오에 남아 있음).
 */
@Component
public class KakaoServiceTermsClient {

    private static final Logger log = LoggerFactory.getLogger(KakaoServiceTermsClient.class);

    private final WebClient webClient;

    public KakaoServiceTermsClient(@Qualifier("kakaoWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    @SuppressWarnings("unchecked")
    public Mono<List<Agreement>> agreed(String kakaoAccessToken) {
        return webClient.get().uri("/v2/user/service_terms")
                .headers(h -> h.setBearerAuth(kakaoAccessToken))
                .retrieve()
                .bodyToMono(Map.class)
                .map(body -> ((List<Map<String, Object>>) body.getOrDefault("service_terms", List.of())).stream()
                        .map(t -> new Agreement(String.valueOf(t.get("tag")), Boolean.TRUE.equals(t.get("agreed")),
                                (String) t.get("agreed_at")))
                        .toList())
                .onErrorResume(e -> {
                    log.warn("kakao_service_terms_failed error={}", e.toString());
                    return Mono.just(List.of());
                });
    }
}
