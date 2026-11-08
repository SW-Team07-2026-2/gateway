package com.indie.gateway.domain.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indie.gateway.TestProps;
import com.indie.gateway.domain.auth.client.MemberAuthClient;
import com.indie.gateway.domain.auth.dto.request.IssueRequest;
import com.indie.gateway.domain.auth.dto.response.IssueResponse;
import com.indie.gateway.domain.auth.repository.CookieAuthorizationRequestRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.web.server.WebFilterExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OAuth2RedirectTest {

    private static final String API = "https://api.example.com";
    private static final String CALLBACK = API + "/oauth2/code/kakao";
    private final CookieFactory cookies = new CookieFactory(TestProps.create("/nonexistent"));
    private final OAuth2AuthenticationToken authentication = new OAuth2AuthenticationToken(
            new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("ROLE_USER")), Map.of("id", 42L), "id"),
            List.of(new SimpleGrantedAuthority("ROLE_USER")), "kakao");

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void newUserReturnsToSelectedSignup(boolean local) {
        MockServerWebExchange callback = callback(local ? "?frontend=local" : "", "?code=code&state=state");

        handler(Mono.just(new IssueResponse(null, null, null, List.of(), null, true, "signup-token")))
                .onAuthenticationSuccess(webFilter(callback), authentication).block();

        assertLocation(callback, (local ? "http://localhost:5173" : "https://example.com") + "/welcome");
        assertThat(callback.getResponse().getCookies().getFirst(CookieFactory.SIGNUP).getValue()).isEqualTo("signup-token");
        assertThat(callback.getResponse().getCookies().getFirst(CookieFactory.OAUTH_REQUEST).getMaxAge()).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void existingUserReturnsHomeWithAuthCookies(boolean local) {
        MockServerWebExchange callback = callback(local ? "?frontend=local" : "", "?code=code&state=state");

        handler(Mono.just(new IssueResponse(1L, "at", "rt", List.of("USER"), "닉네임", false, null)))
                .onAuthenticationSuccess(webFilter(callback), authentication).block();

        assertLocation(callback, (local ? "http://localhost:5173" : "https://example.com") + "/");
        assertThat(callback.getResponse().getCookies().getFirst(CookieFactory.AT).getValue()).isEqualTo("at");
        assertThat(callback.getResponse().getCookies().getFirst(CookieFactory.RT).getValue()).isEqualTo("rt");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancelledLoginReturnsToSelectedLogin(boolean local) {
        MockServerWebExchange callback = callback(local ? "?frontend=local" : "", "?error=access_denied&state=state");

        handler(Mono.empty()).onAuthenticationFailure(webFilter(callback), new OAuth2AuthenticationException("access_denied")).block();

        assertLocation(callback, (local ? "http://localhost:5173" : "https://example.com") + "/login?error=oauth_failed");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void memberFailureReturnsToSelectedLogin(boolean local) {
        MockServerWebExchange callback = callback(local ? "?frontend=local" : "", "?code=code&state=state");

        handler(Mono.error(new IllegalStateException("member unavailable")))
                .onAuthenticationSuccess(webFilter(callback), authentication).block();

        assertLocation(callback, (local ? "http://localhost:5173" : "https://example.com") + "/login?error=signup_failed");
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://evil.example", "//evil.example", "http://localhost:5174", "local.evil.example"})
    void arbitraryDestinationsCannotOverrideProduction(String frontend) {
        MockServerWebExchange callback = callback("?frontend=" + frontend, "?code=code&state=state");

        handler(Mono.just(new IssueResponse(null, null, null, List.of(), null, true, "signup-token")))
                .onAuthenticationSuccess(webFilter(callback), authentication).block();

        assertLocation(callback, "https://example.com/welcome");
    }

    @Test
    void callbackQueryCannotChangeSelectedFrontend() {
        MockServerWebExchange callback = callback("", "?code=code&state=state&frontend=local");

        handler(Mono.just(new IssueResponse(null, null, null, List.of(), null, true, "signup-token")))
                .onAuthenticationSuccess(webFilter(callback), authentication).block();

        assertLocation(callback, "https://example.com/welcome");
    }

    @Test
    void mismatchedStateCannotUseSavedLocalFrontend() {
        MockServerWebExchange callback = callback("?frontend=local", "?code=code&state=other");

        handler(Mono.empty()).onAuthenticationFailure(webFilter(callback), new OAuth2AuthenticationException("invalid_state_parameter")).block();

        assertLocation(callback, "https://example.com/login?error=oauth_failed");
    }

    @Test
    void missingAuthorizationCookieCannotSelectLocalOnCallback() {
        MockServerWebExchange callback = MockServerWebExchange.from(MockServerHttpRequest.get(CALLBACK + "?frontend=local"));
        assertThat(repository().removeAuthorizationRequest(callback).block()).isNull();

        handler(Mono.empty()).onAuthenticationFailure(webFilter(callback), new OAuth2AuthenticationException("authorization_request_not_found")).block();

        assertLocation(callback, "https://example.com/login?error=oauth_failed");
    }

    /** 콜백이 다른 Pod 로 와도 동작하도록 새 인스턴스에서 쿠키만으로 복원합니다. */
    private MockServerWebExchange callback(String startQuery, String callbackQuery) {
        MockServerWebExchange start = MockServerWebExchange.from(MockServerHttpRequest.get(API + "/oauth2/authorization/kakao" + startQuery));
        OAuth2AuthorizationRequest request = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("https://kauth.kakao.com/oauth/authorize").clientId("client-id")
                .redirectUri(CALLBACK).state("state").scopes(Set.of("profile_nickname"))
                .attributes(attrs -> attrs.put("registration_id", "kakao")).build();
        repository().saveAuthorizationRequest(request, start).block();
        ResponseCookie stored = start.getResponse().getCookies().getFirst(CookieFactory.OAUTH_REQUEST);
        MockServerWebExchange callback = MockServerWebExchange.from(MockServerHttpRequest.get(CALLBACK + callbackQuery)
                .cookie(new HttpCookie(stored.getName(), stored.getValue())));

        OAuth2AuthorizationRequest restored = repository().removeAuthorizationRequest(callback).block();
        assertThat(restored.getRedirectUri()).isEqualTo(CALLBACK);
        assertThat(restored.getAuthorizationRequestUri()).isEqualTo(request.getAuthorizationRequestUri());
        assertThat(restored.getState()).isEqualTo("state");
        return callback;
    }

    private CookieAuthorizationRequestRepository repository() {
        return new CookieAuthorizationRequestRepository(cookies, new ObjectMapper());
    }

    private OAuth2LoginHandler handler(Mono<IssueResponse> issued) {
        MemberAuthClient client = new MemberAuthClient(null, null) {
            @Override
            public Mono<IssueResponse> issue(IssueRequest request) {
                return issued;
            }
        };
        return new OAuth2LoginHandler(client, cookies, TestProps.create("/nonexistent"));
    }

    private static WebFilterExchange webFilter(MockServerWebExchange exchange) {
        return new WebFilterExchange(exchange, e -> Mono.empty());
    }

    private static void assertLocation(MockServerWebExchange exchange, String expected) {
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(exchange.getResponse().getHeaders().getLocation()).isEqualTo(URI.create(expected));
    }
}
