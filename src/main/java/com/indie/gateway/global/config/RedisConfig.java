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
 * 인증용 Redis와 캐시용 Redis를 분리합니다.
 * 블랙리스트, 갱신 잠금, nonce는 noeviction에 두고 호출 한도와 프로필은 LRU 캐시에 둡니다.
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

    /** 캐시용 Redis, 값은 ProfileResponse JSON (62B - 직렬화는 템플릿이 맡습니다) */
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
