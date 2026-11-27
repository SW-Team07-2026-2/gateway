package com.indie.gateway.domain.auth.repository;

import com.indie.gateway.domain.auth.client.MemberAuthClient;
import com.indie.gateway.domain.auth.dto.response.ProfileResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Member 프로필을 캐시용 Redis에 5분간 보관합니다.
 * 닉네임 변경은 최대 5분 뒤 반영되며, Redis 장애나 잘못된 값은 캐시 미스로 처리합니다.
 */
@Component
public class ProfileCache {

    private static final Logger log = LoggerFactory.getLogger(ProfileCache.class);
    private static final Duration TTL = Duration.ofMinutes(5);

    private final ReactiveRedisTemplate<String, ProfileResponse> profileRedis;
    private final MemberAuthClient memberAuthClient;

    public ProfileCache(@Qualifier("profileRedis") ReactiveRedisTemplate<String, ProfileResponse> profileRedis,
                        MemberAuthClient memberAuthClient) {
        this.profileRedis = profileRedis;
        this.memberAuthClient = memberAuthClient;
    }

    /** 캐시 -> Member 순으로 찾습니다. Member 도 실패하면 error 를 냅니다 (호출자가 stale 처리) */
    public Mono<ProfileResponse> get(String userId) {
        String key = "auth:profile:" + userId;
        Mono<ProfileResponse> fromMember = memberAuthClient.profile(userId)
                .flatMap(profile -> put(key, profile).thenReturn(profile));
        return profileRedis.opsForValue().get(key)
                .onErrorResume(e -> {
                    log.warn("profile_cache_read_failed error={}", e.toString());
                    return Mono.empty();
                })
                .switchIfEmpty(fromMember);
    }

    private Mono<Boolean> put(String key, ProfileResponse profile) {
        return profileRedis.opsForValue().set(key, profile, TTL)
                .onErrorResume(e -> Mono.just(false));
    }
}
