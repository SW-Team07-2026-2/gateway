package com.indie.gateway.domain.auth.client;

import com.indie.gateway.domain.auth.dto.request.IssueRequest.Agreement;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class KakaoServiceTermsClientTest {

    private static KakaoServiceTermsClient client(HttpStatus status, String body, AtomicReference<String> auth) {
        WebClient webClient = WebClient.builder().baseUrl("https://kapi.kakao.com").exchangeFunction(request -> {
            auth.set(request.headers().getFirst(HttpHeaders.AUTHORIZATION) + " " + request.url().getPath());
            return Mono.just(ClientResponse.create(status)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE).body(body).build());
        }).build();
        return new KakaoServiceTermsClient(webClient);
    }

    @Test
    void mapsServiceTerms() {
        AtomicReference<String> auth = new AtomicReference<>();
        String body = """
                {"id":42,"service_terms":[
                  {"tag":"tos","required":true,"agreed":true,"revocable":false,"agreed_at":"2026-10-28T10:00:00Z","referer":"KAUTH"},
                  {"tag":"marketing","required":false,"agreed":false,"revocable":true}]}
                """;

        assertThat(client(HttpStatus.OK, body, auth).agreed("kakao-at").block()).containsExactly(
                new Agreement("tos", true, "2026-10-28T10:00:00Z"), new Agreement("marketing", false, null));
        assertThat(auth.get()).isEqualTo("Bearer kakao-at /v2/user/service_terms");
    }

    /** 🔴 카카오가 실패해도 로그인은 진행 — 빈 목록 */
    @Test
    void failureIsEmpty() {
        assertThat(client(HttpStatus.BAD_REQUEST, "{\"code\":-2}", new AtomicReference<>()).agreed("x").block()).isEmpty();
        assertThat(client(HttpStatus.OK, "{\"id\":42}", new AtomicReference<>()).agreed("x").block()).isEmpty();
    }
}
