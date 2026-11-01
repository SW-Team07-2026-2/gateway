package com.indie.gateway.domain.auth.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indie.gateway.domain.auth.client.MemberAuthClient;
import com.indie.gateway.domain.auth.dto.response.ProfileResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * /api/auth/me 의 프로필 5분 캐시 (§9-3) — 인증용 Redis auth:profile:{userId}.
 * Member 가 닉네임을 바꿔도 무효화할 방법이 없습니다 (Gateway 는 이벤트를 받지 않음) — 최대 5분 늦게 보입니다.
 * Redis 장애는 캐시 미스로 취급합니다.
 */
@Component
public class ProfileCache {

    private static final Logger log = LoggerFactory.getLogger(ProfileCache.class);
    private static final Duration TTL = Duration.ofMinutes(5);

    private final ReactiveStringRedisTemplate authRedis;
    private final MemberAuthClient memberAuthClient;
    private final ObjectMapper objectMapper;

    public ProfileCache(@Qualifier("authRedis") ReactiveStringRedisTemplate authRedis,
                        MemberAuthClient memberAuthClient, ObjectMapper objectMapper) {
        this.authRedis = authRedis;
        this.memberAuthClient = memberAuthClient;
        this.objectMapper = objectMapper;
    }

    /** 캐시 → Member 순으로 찾습니다. Member 도 실패하면 error 를 냅니다 (호출자가 stale 처리) */
    public Mono<ProfileResponse> get(String userId) {
        String key = "auth:profile:" + userId;
        Mono<ProfileResponse> fromMember = memberAuthClient.profile(userId)
                .flatMap(profile -> put(key, profile).thenReturn(profile));
        return authRedis.opsForValue().get(key)
                .map(this::read)
                .onErrorResume(e -> {
                    log.warn("profile_cache_read_failed error={}", e.toString());
                    return Mono.empty();
                })
                .switchIfEmpty(fromMember);
    }

    private Mono<Boolean> put(String key, ProfileResponse profile) {
        try {
            return authRedis.opsForValue().set(key, objectMapper.writeValueAsString(profile), TTL)
                    .onErrorResume(e -> Mono.just(false));
        } catch (Exception e) {
            return Mono.just(false);
        }
    }

    private ProfileResponse read(String json) {
        try {
            return objectMapper.readValue(json, ProfileResponse.class);
        } catch (Exception e) {
            throw new IllegalStateException("profile cache corrupted", e);
        }
    }
}
