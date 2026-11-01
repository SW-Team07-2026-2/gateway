package com.indie.gateway.domain.auth.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indie.gateway.domain.auth.service.CookieFactory;
import org.springframework.http.HttpCookie;
import org.springframework.security.oauth2.client.web.server.ServerAuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * OAuth 인가 요청을 세션 대신 쿠키에 보관합니다 (API 명세 §11.4 G2).
 * 세션에 두면 replica 2 에서 콜백이 다른 Pod 로 갔을 때 authorization_request_not_found 가 납니다.
 * 직렬화는 JSON — 쿠키(클라이언트 입력)를 Java 역직렬화하지 않습니다.
 */
@Component
public class CookieAuthorizationRequestRepository implements ServerAuthorizationRequestRepository<OAuth2AuthorizationRequest> {

    private final CookieFactory cookieFactory;
    private final ObjectMapper objectMapper;

    public CookieAuthorizationRequestRepository(CookieFactory cookieFactory, ObjectMapper objectMapper) {
        this.cookieFactory = cookieFactory;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<OAuth2AuthorizationRequest> loadAuthorizationRequest(ServerWebExchange exchange) {
        HttpCookie cookie = exchange.getRequest().getCookies().getFirst(CookieFactory.OAUTH_REQUEST);
        if (cookie == null || cookie.getValue().isBlank()) {
            return Mono.empty();
        }
        return Mono.fromCallable(() -> decode(cookie.getValue())).onErrorResume(e -> Mono.empty());
    }

    @Override
    public Mono<Void> saveAuthorizationRequest(OAuth2AuthorizationRequest request, ServerWebExchange exchange) {
        if (request == null) {
            exchange.getResponse().addCookie(cookieFactory.expire(CookieFactory.OAUTH_REQUEST));
            return Mono.empty();
        }
        return Mono.fromRunnable(() -> exchange.getResponse().addCookie(cookieFactory.oauthRequest(encode(request))));
    }

    @Override
    public Mono<OAuth2AuthorizationRequest> removeAuthorizationRequest(ServerWebExchange exchange) {
        return loadAuthorizationRequest(exchange)
                .doOnNext(r -> exchange.getResponse().addCookie(cookieFactory.expire(CookieFactory.OAUTH_REQUEST)));
    }

    private String encode(OAuth2AuthorizationRequest r) {
        try {
            Stored stored = new Stored(r.getAuthorizationUri(), r.getClientId(), r.getRedirectUri(),
                    List.copyOf(r.getScopes()), r.getState(), r.getAdditionalParameters(), r.getAttributes(),
                    r.getAuthorizationRequestUri());
            return Base64.getUrlEncoder().withoutPadding().encodeToString(objectMapper.writeValueAsBytes(stored));
        } catch (Exception e) {
            throw new IllegalStateException("authorization request encode failed", e);
        }
    }

    private OAuth2AuthorizationRequest decode(String value) throws Exception {
        Stored s = objectMapper.readValue(Base64.getUrlDecoder().decode(value), Stored.class);
        return OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri(s.authorizationUri())
                .clientId(s.clientId())
                .redirectUri(s.redirectUri())
                .scopes(new LinkedHashSet<>(s.scopes()))
                .state(s.state())
                .additionalParameters(s.additionalParameters())
                .attributes(s.attributes())
                .authorizationRequestUri(s.authorizationRequestUri())
                .build();
    }

    private record Stored(String authorizationUri, String clientId, String redirectUri, List<String> scopes,
                          String state, Map<String, Object> additionalParameters, Map<String, Object> attributes,
                          String authorizationRequestUri) {}
}
