package com.indie.gateway.domain.auth.service;

import com.indie.gateway.global.config.GatewayProperties;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Member 의 JWKS 를 kid → 공개키 맵으로 캐시합니다 (§5-②).
 * <ul>
 *   <li>TTL 10분 — 지나면 백그라운드로 재조회하고 그동안은 기존 키로 검증</li>
 *   <li>모르는 kid — 즉시 1회 재조회 (쿨다운 30초). 이게 없으면 키 롤링 때 전원 로그아웃</li>
 *   <li>JWKS 가 아예 안 뜰 때 — ConfigMap 백업 공개키로 검증. Member 장애가 전체 401 이 되면 안 됩니다</li>
 * </ul>
 */
@Component
public class JwksKeyStore {

    private static final Logger log = LoggerFactory.getLogger(JwksKeyStore.class);

    private final WebClient webClient;
    private final GatewayProperties.Token props;
    private final Clock clock;
    private final RSAPublicKey fallbackKey;

    private volatile Map<String, RSAPublicKey> keys = Map.of();
    private volatile Instant fetchedAt = Instant.EPOCH;
    private volatile Instant lastForcedFetch = Instant.EPOCH;

    public JwksKeyStore(WebClient.Builder builder, GatewayProperties props, Clock clock) {
        this.webClient = builder.build();
        this.props = props.token();
        this.clock = clock;
        this.fallbackKey = loadFallbackKey(this.props.fallbackPublicKeyPath());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        fetch().subscribe();   // 기동을 막지 않습니다 — 실패하면 백업 공개키로 버팁니다
    }

    /** kid 에 해당하는 공개키. 없으면 1회 재조회 후 백업 공개키, 그래도 없으면 empty */
    public Mono<RSAPublicKey> find(String kid) {
        Instant now = clock.instant();
        if (now.isAfter(fetchedAt.plus(props.jwksCacheTtl()))) {
            fetch().subscribe();
        }
        RSAPublicKey cached = kid == null ? null : keys.get(kid);
        if (cached != null) {
            return Mono.just(cached);
        }
        Mono<Void> refetch = Mono.empty();
        if (now.isAfter(lastForcedFetch.plus(props.jwksRefetchCooldown()))) {
            lastForcedFetch = now;
            refetch = fetch();
        }
        return refetch.then(Mono.fromSupplier(() -> Optional.ofNullable(kid == null ? null : keys.get(kid))
                .orElse(fallbackKey)));
    }

    private Mono<Void> fetch() {
        return webClient.get().uri(props.jwksUri())
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(2))
                .doOnNext(this::replaceKeys)
                .doOnError(e -> log.warn("jwks_fetch_failed uri={} error={}", props.jwksUri(), e.toString()))
                .onErrorResume(e -> Mono.empty())
                .then();
    }

    private void replaceKeys(String json) {
        try {
            Map<String, RSAPublicKey> next = new HashMap<>();
            for (JWK jwk : JWKSet.parse(json).getKeys()) {
                if (jwk instanceof RSAKey rsa && rsa.getKeyID() != null) {
                    next.put(rsa.getKeyID(), rsa.toRSAPublicKey());
                }
            }
            keys = Map.copyOf(next);
            fetchedAt = clock.instant();
            log.info("jwks_loaded kids={}", next.keySet());
        } catch (Exception e) {
            log.warn("jwks_parse_failed error={}", e.toString());
        }
    }

    private static RSAPublicKey loadFallbackKey(String path) {
        if (path == null || path.isBlank() || !Files.isReadable(Path.of(path))) {
            log.warn("jwks_fallback_key_missing path={}", path);
            return null;
        }
        try {
            String pem = Files.readString(Path.of(path))
                    .replaceAll("-----(BEGIN|END) PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(pem);
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("백업 공개키를 읽을 수 없습니다: " + path, e);
        }
    }
}
