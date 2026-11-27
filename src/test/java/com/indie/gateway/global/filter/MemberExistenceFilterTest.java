package com.indie.gateway.global.filter;

import com.indie.gateway.domain.auth.client.MemberAuthClient;
import com.indie.gateway.domain.auth.entity.TokenClaims;
import com.indie.gateway.global.common.util.GatewayAttributes;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MemberExistenceFilterTest {
    private final MemberAuthClient member = mock(MemberAuthClient.class);
    private final MemberExistenceFilter filter = new MemberExistenceFilter(member);

    private MockServerWebExchange request(String path, String jti) {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path));
        exchange.getAttributes().put(GatewayAttributes.CLAIMS,
                new TokenClaims("42", List.of("USER"), jti, Instant.now().plusSeconds(1200)));
        return exchange;
    }

    @Test
    void allDevicesAreBlockedAfterDeletionWithoutCachingActiveState() {
        when(member.exists("42")).thenReturn(Mono.just(true), Mono.just(false), Mono.just(false));
        var passed = new AtomicBoolean();
        filter.filter(request("/api/cart", "device1"), e -> { passed.set(true); return Mono.empty(); }).block();
        assertThat(passed).isTrue();
        for (String device : List.of("device1", "device2")) {
            var exchange = request("/api/cart", device);
            filter.filter(exchange, e -> Mono.error(new AssertionError("withdrawn user passed"))).block();
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(exchange.getResponse().getBodyAsString().block()).contains("TOKEN_REVOKED");
        }
        verify(member, times(3)).exists("42");
    }

    @Test
    void unavailableMemberFailsClosedForProtectedRequests() {
        when(member.exists("42")).thenReturn(Mono.error(new IllegalStateException("unavailable")));
        var exchange = request("/api/cart", "device1");
        filter.filter(exchange, e -> Mono.error(new AssertionError("passed"))).block();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void withdrawnUserCanReadPublicDataOnlyAnonymously() {
        when(member.exists("42")).thenReturn(Mono.just(false));
        var exchange = request("/api/catalog/artists", "device1");
        var passed = new AtomicBoolean();
        filter.filter(exchange, e -> {
            assertThat(GatewayAttributes.claims(e)).isNull();
            passed.set(true);
            return Mono.empty();
        }).block();
        assertThat(passed).isTrue();
    }

    @Test
    void anonymousRequestsDoNotCallMember() {
        filter.filter(MockServerWebExchange.from(MockServerHttpRequest.get("/api/catalog/artists")), e -> Mono.empty()).block();
        verifyNoInteractions(member);
    }

    @Test
    void checkRunsAfterRateLimitAndBeforeUserHeaders() {
        assertThat(filter.getOrder()).isGreaterThan(GatewayFilterOrder.RATE_LIMIT)
                .isLessThan(GatewayFilterOrder.USER_HEADER_INJECT);
    }
}
