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
 * Spring Security는 OAuth2 로그인에만 사용하고 세션은 만들지 않습니다.
 * OAuth2 체인을 order -55에 실행해 호출 한도 검사 뒤에 인가 요청을 처리합니다.
 */
@Configuration
public class SecurityConfig {

    /** 기본 Security 체인이 요청에 매칭되지 않도록 설정합니다. OAuth2는 별도 필터 체인에서 처리합니다. */
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
                        // 콜백을 기본값 /login/oauth2/code/* 대신 /oauth2/* 아래로 - ALB 규칙(/api/*, /oauth2/*)에 걸리도록
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
     * 후속 필터가 요청 헤더를 변경할 수 있도록 기본 Security 방화벽을 비활성화합니다.
     * 입력 형식 검사는 RequestSanityFilter가 담당합니다.
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

    /** IdP 접근 토큰을 현재 콜백 요청 속성에만 보관합니다. 요청이 끝나면 함께 해제됩니다. */
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
