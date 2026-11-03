package com.indie.gateway.domain.ingest.repository;

import com.indie.gateway.global.config.GatewayProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * 수집 요청 재전송 차단 (docs/22 G5) — 인증용 Redis 에 ingest:nonce:{키 ID}:{nonce} SET NX.
 * TTL 은 허용 시각 범위(±300초)의 두 배라, 범위 안에서 같은 nonce 가 두 번 들어올 수 없습니다.
 * 🔴 캐시용(LRU)에 두면 메모리 압박 시 TTL 전에 밀려나 같은 요청이 다시 통과합니다 (50A)
 */
@Component
public class IngestNonceStore {

    private final ReactiveStringRedisTemplate authRedis;
    private final Duration ttl;

    public IngestNonceStore(@Qualifier("authRedis") ReactiveStringRedisTemplate authRedis, GatewayProperties props) {
        this.authRedis = authRedis;
        this.ttl = props.ingest().nonceTtl();
    }

    /** 처음 보는 nonce 면 true. 🔴 Redis 오류 · 타임아웃(300ms)은 error 그대로 — 호출하는 쪽이 거부합니다 (fail-closed) */
    public Mono<Boolean> claim(String keyId, String nonce) {
        return authRedis.opsForValue().setIfAbsent("ingest:nonce:" + keyId + ":" + nonce, "1", ttl);
    }
}
