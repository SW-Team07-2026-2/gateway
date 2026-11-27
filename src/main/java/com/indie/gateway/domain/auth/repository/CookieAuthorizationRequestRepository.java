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
 * 직렬화는 JSON - 쿠키(클라이언트 입력)를 Java 역직렬화하지 않습니다.
 */
@Component
public class CookieAuthorizationRequestRepository implements ServerAuthorizationRequestRepository<OAuth2AuthorizationRequest> {

    /** 콜백 exchange 속성 - 로그인 결과를 돌려보낼 프론트 주소 (없으면 운영 설정값) */
    public static final String FRONTEND_BASE = CookieAuthorizationRequestRepository.class.getName() + ".frontendBase";

    /** 인가 요청 쿠키에는 이름만 담고, 주소는 이 고정 목록에서 고릅니다 - 쿠키를 고쳐도 다른 곳으로 보낼 수 없게 */
    private static final String FRONTEND = "frontend";
    private static final Map<String, String> FRONTENDS = Map.of(
            "local", "http://localhost:5173",
            "vercel", "https://sw-academy-workspace-front.vercel.app");

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
        return Mono.fromRunnable(() -> {
            // 로그인 시작 때 선택한 프론트를 OAuth 요청과 함께 보관합니다. 카카오 콜백 URI 는 그대로입니다.
            String frontend = exchange.getRequest().getQueryParams().getFirst(FRONTEND);
            OAuth2AuthorizationRequest stored = OAuth2AuthorizationRequest.from(request)
                    .attributes(attrs -> {
                        if (frontend != null && FRONTENDS.containsKey(frontend)) {
                            attrs.put(FRONTEND, frontend);
                        }
                    })
                    .build();
            exchange.getResponse().addCookie(cookieFactory.oauthRequest(encode(stored)));
        });
    }

    @Override
    public Mono<OAuth2AuthorizationRequest> removeAuthorizationRequest(ServerWebExchange exchange) {
        return loadAuthorizationRequest(exchange)
                .doOnNext(r -> {
                    exchange.getResponse().addCookie(cookieFactory.expire(CookieFactory.OAUTH_REQUEST));
                    // 콜백의 쿼리로 목적지를 바꾸지 않습니다. 같은 state 의 저장된 선택만 사용합니다.
                    String base = r.<Object>getAttribute(FRONTEND) instanceof String name ? FRONTENDS.get(name) : null;
                    if (base != null && r.getState() != null
                            && r.getState().equals(exchange.getRequest().getQueryParams().getFirst("state"))) {
                        exchange.getAttributes().put(FRONTEND_BASE, base);
                    }
                });
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
