package com.indie.gateway.global.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

/**
 * Redis 2개를 구분해서 씁니다 (§9).
 * <pre>
 * authRedis  (AZ-a · noeviction)   member:blacklist:{jti} · auth:profile:{userId} · auth:refresh:lock:{jti}
 * cacheRedis (AZ-c · allkeys-lru)  request_rate_limiter.*
 * </pre>
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

    private static LettuceClientConfiguration client(RedisProperties props) {
        return LettuceClientConfiguration.builder().commandTimeout(props.getTimeout()).build();
    }
}
