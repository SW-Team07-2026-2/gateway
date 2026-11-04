package com.indie.gateway.global.config;

import com.indie.gateway.domain.auth.repository.CookieAuthorizationRequestRepository;
import com.indie.gateway.domain.auth.service.OAuth2LoginHandler;
import com.indie.gateway.global.filter.GatewayFilterOrder;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizedClientRepository;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.WebFilterChainProxy;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.firewall.ServerWebExchangeFirewall;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Spring Security 는 OAuth2 로그인(/oauth2/** — 시작 /oauth2/authorization/{provider} · 콜백 /oauth2/code/{provider})에만 씁니다.
 * 인증 · 인가는 필터 ⓪~⑦ 이 담당하고, 세션은 만들지 않습니다.
 *
 * <p>Security 의 기본 WebFilterChainProxy 는 order -100 으로 등록되어 ⑥ 호출 한도(-60)보다 먼저 실행됩니다.
 * 그러면 /oauth2/authorization/** 의 IP 한도가 걸리기 전에 IdP 로 302 가 나가므로, 기본 체인은 아무 요청에도
 * 매칭되지 않게 두고 OAuth2 체인을 별도 WebFilter(order -55)로 감싸 순서를 고정합니다.
 */
@Configuration
public class SecurityConfig {

    /** 기본 체인 — 어떤 요청에도 매칭되지 않음 (이 빈이 없으면 Spring Security 가 전 경로를 잠그는 기본 체인을 만듭니다) */
    @Bean
    public SecurityWebFilterChain disabledDefaultChain(ServerHttpSecurity http) {
        return http.securityMatcher(exchange -> ServerWebExchangeMatcher.MatchResult.notMatch()).build();
    }

    @Bean
    public OAuth2LoginWebFilter oauth2LoginWebFilter(ServerHttpSecurity http,
                                                     CookieAuthorizationRequestRepository authorizationRequestRepository,
                                                     OAuth2LoginHandler handler) {
        SecurityWebFilterChain chain = http
                .securityMatcher(ServerWebExchangeMatchers.pathMatchers("/oauth2/**"))
                .csrf(ServerHttpSecurity.CsrfSpec::disable)                // state 파라미터가 대신합니다
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .headers(ServerHttpSecurity.HeaderSpec::disable)           // SecurityHeaderFilter 가 담당
                .requestCache(c -> c.requestCache(NoOpServerRequestCache.getInstance()))
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .authorizeExchange(a -> a.anyExchange().permitAll())
                .oauth2Login(o -> o
                        // 콜백을 기본값 /login/oauth2/code/* 대신 /oauth2/* 아래로 — ALB 규칙(/api/* · /oauth2/*)에 걸리도록
                        .authenticationMatcher(ServerWebExchangeMatchers.pathMatchers("/oauth2/code/{registrationId}"))
                        .authorizationRequestRepository(authorizationRequestRepository)
                        .authorizedClientRepository(new RequestScopedAuthorizedClientRepository())
                        .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                        .authenticationSuccessHandler(handler)
                        .authenticationFailureHandler(handler))
                .build();
        WebFilterChainProxy proxy = new WebFilterChainProxy(chain);
        proxy.setFirewall(ServerWebExchangeFirewall.INSECURE_NOOP);
        return new OAuth2LoginWebFilter(proxy);
    }

    /**
     * Spring Security 6.3 의 StrictServerWebExchangeFirewall 은 요청을 감싸서, 뒤에 오는 필터의 헤더 변경
     * (⑦ X-User-Id 주입 · ③ 갱신된 Cookie)을 UnsupportedOperationException 으로 막습니다.
     * Security 는 OAuth2 로그인에만 쓰고 입력 형식 검사는 ⓪ RequestSanityFilter 가 담당하므로 끕니다.
     */
    @Bean
    public static BeanPostProcessor webFilterChainProxyFirewallDisabler() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof WebFilterChainProxy proxy) {
                    proxy.setFirewall(ServerWebExchangeFirewall.INSECURE_NOOP);
                }
                return bean;
            }
        };
    }

    public static class OAuth2LoginWebFilter implements WebFilter, Ordered {

        private final WebFilterChainProxy delegate;

        OAuth2LoginWebFilter(WebFilterChainProxy delegate) {
            this.delegate = delegate;
        }

        @Override
        public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
            return delegate.filter(exchange, chain);
        }

        @Override
        public int getOrder() {
            return GatewayFilterOrder.OAUTH2_LOGIN;
        }
    }

    /**
     * IdP access_token 은 이 요청 안에서만 둡니다 — 성공 핸들러가 카카오 약관 동의 내역을 조회하고 나면 요청과 함께 사라집니다.
     * 기본 구현은 Pod 메모리에 쌓입니다. Spring Security 는 성공 핸들러보다 먼저 save 를 부릅니다
     */
    private static class RequestScopedAuthorizedClientRepository implements ServerOAuth2AuthorizedClientRepository {

        @Override
        @SuppressWarnings("unchecked")
        public <T extends OAuth2AuthorizedClient> Mono<T> loadAuthorizedClient(String id, Authentication p, ServerWebExchange e) {
            return Mono.justOrEmpty((T) e.getAttribute(OAuth2LoginHandler.AUTHORIZED_CLIENT));
        }

        @Override
        public Mono<Void> saveAuthorizedClient(OAuth2AuthorizedClient c, Authentication p, ServerWebExchange e) {
            e.getAttributes().put(OAuth2LoginHandler.AUTHORIZED_CLIENT, c);
            return Mono.empty();
        }

        @Override
        public Mono<Void> removeAuthorizedClient(String id, Authentication p, ServerWebExchange e) {
            e.getAttributes().remove(OAuth2LoginHandler.AUTHORIZED_CLIENT);
            return Mono.empty();
        }
    }
}
