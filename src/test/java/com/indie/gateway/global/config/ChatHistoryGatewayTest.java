package com.indie.gateway.global.config;

import com.indie.gateway.TestProps;
import com.indie.gateway.domain.auth.entity.TokenClaims;
import com.indie.gateway.domain.auth.service.AccessTokenVerifier;
import com.indie.gateway.global.filter.AccessTokenFilter;
import com.indie.gateway.global.filter.ClientHeaderStripFilter;
import com.indie.gateway.global.filter.CsrfFilter;
import com.indie.gateway.global.filter.RequestSanityFilter;
import com.indie.gateway.global.filter.UserHeaderInjectFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.springframework.cloud.gateway.filter.factory.DedupeResponseHeaderGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.RetryGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.SetPathGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.SpringCloudCircuitBreakerFilterFactory;
import org.springframework.cloud.gateway.handler.predicate.MethodRoutePredicateFactory;
import org.springframework.cloud.gateway.handler.predicate.PathRoutePredicateFactory;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 실제 라우트와 인증 관련 필터 검사. 토큰 검증기·하위 서비스 호출은 대체합니다. */
class ChatHistoryGatewayTest {

    private static final String PATH = "/api/members/me/chat-history";
    private static final String BASE = "http://gateway.indie.svc.cluster.local:8080";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private enum Method { GET, POST }

    @ParameterizedTest
    @EnumSource(Method.class)
    void getAndPostRouteToMember(Method method) {
        try (var context = new StaticApplicationContext()) {
            var beans = context.getBeanFactory();
            beans.registerSingleton("path", new PathRoutePredicateFactory());
            beans.registerSingleton("method", new MethodRoutePredicateFactory());
            beans.registerSingleton("retry", new RetryGatewayFilterFactory());
            beans.registerSingleton("setPath", new SetPathGatewayFilterFactory());
            beans.registerSingleton("dedupeResponseHeader", new DedupeResponseHeaderGatewayFilterFactory());
            var circuitBreaker = mock(SpringCloudCircuitBreakerFilterFactory.class);
            when(circuitBreaker.apply(anyString(), ArgumentMatchers.<Consumer<SpringCloudCircuitBreakerFilterFactory.Config>>any()))
                    .thenReturn((exchange, chain) -> chain.filter(exchange));
            beans.registerSingleton("circuitBreaker", circuitBreaker);
            var routes = new RouteConfig().routes(new RouteLocatorBuilder(context), TestProps.create(null));
            var exchange = MockServerWebExchange.from(request(method));

            var route = routes.getRoutes().filterWhen(r -> r.getPredicate().apply(exchange)).single().block(TIMEOUT);

            assertThat(route).isNotNull();
            assertThat(route.getId()).isEqualTo("member");
            assertThat(route.getUri().toString()).isEqualTo("lb://member");
        }
    }

    @ParameterizedTest
    @EnumSource(Method.class)
    void validUserReplacesForgedIdentity(Method method) {
        var exchange = MockServerWebExchange.from(request(method)
                .cookie(new HttpCookie("AT", "valid-at"), new HttpCookie("XSRF-TOKEN", "csrf"))
                .header("X-XSRF-TOKEN", "csrf")
                .header("X-User-Id", "999").header("X-User-Roles", "ADMIN"));

        var passed = filter(exchange);

        assertThat(passed).isNotNull();
        assertThat(passed.getRequest().getPath().value()).isEqualTo(PATH);
        assertThat(passed.getRequest().getHeaders().getFirst("X-User-Id")).isEqualTo("42");
        assertThat(passed.getRequest().getHeaders().getFirst("X-User-Roles")).isEqualTo("USER");
    }

    @ParameterizedTest
    @EnumSource(Method.class)
    void missingUserTokenIsRejected(Method method) {
        var exchange = MockServerWebExchange.from(request(method));

        assertThat(filter(exchange)).isNull();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "wrong"})
    void postRequiresMatchingCsrfHeader(String header) {
        var request = request(Method.POST)
                .cookie(new HttpCookie("AT", "valid-at"), new HttpCookie("XSRF-TOKEN", "csrf"));
        if (!header.isEmpty()) request.header("X-XSRF-TOKEN", header);
        var exchange = MockServerWebExchange.from(request);

        assertThat(filter(exchange)).isNull();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @ParameterizedTest
    @EnumSource(Method.class)
    void internalAddressStillRequiresAllowedApiHost(Method method) {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.method(HttpMethod.valueOf(method.name()), BASE + PATH)
                .header("Host", "gateway.indie.svc.cluster.local:8080")
                .cookie(new HttpCookie("AT", "valid-at")));

        assertThat(filter(exchange)).isNull();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private MockServerHttpRequest.BodyBuilder request(Method method) {
        return MockServerHttpRequest.method(HttpMethod.valueOf(method.name()), BASE + PATH)
                .header("Host", "api.example.com").contentType(MediaType.APPLICATION_JSON);
    }

    private ServerWebExchange filter(MockServerWebExchange exchange) {
        var verifier = mock(AccessTokenVerifier.class);
        when(verifier.verify("valid-at")).thenReturn(Mono.just(
                new TokenClaims("42", List.of("USER"), "test-jti", Instant.now().plusSeconds(1800))));
        var meters = new SimpleMeterRegistry();
        var sanity = new RequestSanityFilter(TestProps.create(null), meters);
        var strip = new ClientHeaderStripFilter(meters);
        var auth = new AccessTokenFilter(verifier, meters);
        var csrf = new CsrfFilter();
        var inject = new UserHeaderInjectFilter();
        var passed = new AtomicReference<ServerWebExchange>();
        sanity.filter(exchange, e -> strip.filter(e, s -> auth.filter(s, a -> csrf.filter(a,
                c -> inject.filter(c, target -> { passed.set(target); return Mono.empty(); }))))).block(TIMEOUT);
        return passed.get();
    }
}
