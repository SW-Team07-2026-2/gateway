package com.indie.gateway.global.filter;

import com.indie.gateway.domain.auth.entity.TokenClaims;
import com.indie.gateway.domain.auth.client.MemberAuthClient;
import com.indie.gateway.global.common.util.GatewayAttributes;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** 실제 Redis 의 MGET - 없는 키가 차단으로 읽히지 않는지, Member 가 쓴 키는 막히는지 */
@Testcontainers(disabledWithoutDocker = true)
class BlacklistFilterRedisTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static LettuceConnectionFactory factory;
    static ReactiveStringRedisTemplate redis;

    @BeforeAll
    static void connect() {
        factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        redis = new ReactiveStringRedisTemplate(factory);
    }

    @AfterAll
    static void close() {
        factory.destroy();
    }

    private MemberAuthClient activeMember() {
        var member = mock(MemberAuthClient.class);
        when(member.exists(anyString())).thenReturn(Mono.just(true));
        return member;
    }

    private boolean passes(String userId, String jti) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/cart"));
        exchange.getAttributes().put(GatewayAttributes.CLAIMS, new TokenClaims(userId, List.of("USER"), jti, Instant.now().plusSeconds(1200)));
        AtomicBoolean passed = new AtomicBoolean();
        new BlacklistFilter(redis, new SimpleMeterRegistry(), activeMember()).filter(exchange, e -> { passed.set(true); return Mono.empty(); }).block();
        assertThat(passed.get() || exchange.getResponse().getStatusCode() == HttpStatus.UNAUTHORIZED).isTrue();
        return passed.get();
    }

    @Test
    void missingKeysPassAndMemberKeysBlock() {
        assertThat(passes("7", "jti-a")).isTrue();

        redis.opsForValue().set("member:blacklist:jti-a", "1", Duration.ofMinutes(1)).block();
        assertThat(passes("7", "jti-a")).isFalse();
        assertThat(passes("7", "jti-b")).isTrue();

        redis.opsForValue().set("member:withdrawn:7", "1", Duration.ofMinutes(1)).block();
        assertThat(passes("7", "jti-b")).isFalse();
        assertThat(passes("8", "jti-c")).isTrue();
    }
}
