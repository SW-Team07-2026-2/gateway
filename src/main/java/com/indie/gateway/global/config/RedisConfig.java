package com.indie.gateway.global.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indie.gateway.domain.auth.dto.response.ProfileResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 2개를 구분해서 씁니다 (§9).
 * <pre>
 * authRedis  (AZ-a · noeviction)   member:blacklist:{jti} · auth:refresh:lock:{jti} · ingest:nonce:{키 ID}:{nonce}
 * cacheRedis (AZ-c · allkeys-lru)  request_rate_limiter.* · auth:profile:{userId}
 * </pre>
 * 기준 (50A): 지워지면 안 되는 것만 인증용 — 지워져도 다시 만들 수 있는 것은 캐시용.
 * 🔴 블랙리스트를 캐시용(LRU)에 두면 메모리 압박 시 조용히 밀려나 로그아웃이 무효화됩니다.
 * 빈 이름(@Qualifier)으로 실수를 막습니다.
 */
@Configuration
public class RedisConfig {

    @Bean
    @Primary
    public LettuceConnectionFactory authRedisConnectionFactory(RedisProperties props) {
        RedisStandaloneConfiguration server = new RedisStandaloneConfiguration(props.getHost(), props.getPort());
        if (props.getPassword() != null) server.setPassword(props.getPassword());
        return new LettuceConnectionFactory(server, client(props));
    }

    @Bean
    public LettuceConnectionFactory cacheRedisConnectionFactory(GatewayProperties app, RedisProperties props) {
        RedisStandaloneConfiguration server = new RedisStandaloneConfiguration(
                app.redis().cache().host(), app.redis().cache().port());
        return new LettuceConnectionFactory(server, client(props));
    }

    @Bean("authRedis")
    @Primary
    public ReactiveStringRedisTemplate authRedis(@Qualifier("authRedisConnectionFactory") LettuceConnectionFactory factory) {
        return new ReactiveStringRedisTemplate(factory);
    }

    @Bean("cacheRedis")
    public ReactiveStringRedisTemplate cacheRedis(@Qualifier("cacheRedisConnectionFactory") LettuceConnectionFactory factory) {
        return new ReactiveStringRedisTemplate(factory);
    }

    /** 캐시용 Redis · 값은 ProfileResponse JSON (62B — 직렬화는 템플릿이 맡습니다) */
    @Bean("profileRedis")
    public ReactiveRedisTemplate<String, ProfileResponse> profileRedis(
            @Qualifier("cacheRedisConnectionFactory") LettuceConnectionFactory factory, ObjectMapper objectMapper) {
        RedisSerializationContext<String, ProfileResponse> context = RedisSerializationContext
                .<String, ProfileResponse>newSerializationContext(StringRedisSerializer.UTF_8)
                .value(new Jackson2JsonRedisSerializer<>(objectMapper, ProfileResponse.class))
                .build();
        return new ReactiveRedisTemplate<>(factory, context);
    }

    private static LettuceClientConfiguration client(RedisProperties props) {
        return LettuceClientConfiguration.builder().commandTimeout(props.getTimeout()).build();
    }
}
