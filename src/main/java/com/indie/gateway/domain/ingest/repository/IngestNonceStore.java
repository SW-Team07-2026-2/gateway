package com.indie.gateway.domain.ingest.repository;

import com.indie.gateway.global.config.GatewayProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * 인증용 Redis의 SET NX로 서명 요청의 nonce 재사용을 막습니다.
 * TTL은 허용 시각 범위의 두 배이며, 만료 전 축출을 막기 위해 noeviction Redis를 사용합니다.
 */
@Component
public class IngestNonceStore {

    private final ReactiveStringRedisTemplate authRedis;
    private final Duration ttl;

    public IngestNonceStore(@Qualifier("authRedis") ReactiveStringRedisTemplate authRedis, GatewayProperties props) {
        this.authRedis = authRedis;
        this.ttl = props.ingest().nonceTtl();
    }

    /** 처음 보는 nonce 면 true. Redis 오류, 타임아웃(300ms)은 error 그대로 - 호출하는 쪽이 거부합니다 (fail-closed) */
    public Mono<Boolean> claim(String keyId, String nonce) {
        return authRedis.opsForValue().setIfAbsent("ingest:nonce:" + keyId + ":" + nonce, "1", ttl);
    }
}
