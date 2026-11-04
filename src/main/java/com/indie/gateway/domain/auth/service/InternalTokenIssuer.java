package com.indie.gateway.domain.auth.service;

import com.indie.gateway.global.config.GatewayProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * X-Internal-Token (HS256 · 공유 시크릿). 매 호출마다 새로 만듭니다.
 * 토큰을 캐시하지 마세요 — 생성 비용은 무시할 수준이고, 캐시하면 만료 처리가 또 하나의 버그 지점이 됩니다.
 */
@Component
public class InternalTokenIssuer {

    private final MACSigner signer;
    private final GatewayProperties.InternalToken props;
    private final long ingestTtlSeconds;
    private final Clock clock;

    public InternalTokenIssuer(@Value("${internal.shared-secret}") String sharedSecret,
                               GatewayProperties props, Clock clock) throws JOSEException {
        this.signer = new MACSigner(sharedSecret.trim().getBytes(StandardCharsets.UTF_8));   // 256bit 미만이면 기동 실패
        this.props = props.internalToken();
        this.ingestTtlSeconds = props.ingest().tokenTtlSeconds();
        this.clock = clock;
    }

    public String issue() {
        return sign(claims(props.audience(), props.ttlSeconds()).build());
    }

    /**
     * 수집 경로 (docs/22 G9) — 서명을 확인한 수집 요청에만. 서비스는 sub=ingest:* 와 본문 해시를 다시 확인합니다 (S1 · S2).
     * body_sha256 으로 토큰을 본문에 묶어, 가로챈 토큰을 다른 본문에 쓸 수 없게 합니다.
     */
    public String issueForIngest(String audience, String keyId, String bodySha256Hex) {
        return sign(claims(audience, ingestTtlSeconds)
                .subject("ingest:" + keyId)
                .claim("body_sha256", bodySha256Hex)
                .build());
    }

    private JWTClaimsSet.Builder claims(String audience, long ttlSeconds) {
        Instant now = clock.instant();
        return new JWTClaimsSet.Builder()
                .issuer(props.issuer())
                .audience(audience)
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(ttlSeconds)));
    }

    private String sign(JWTClaimsSet claims) {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        try {
            jwt.sign(signer);
        } catch (JOSEException e) {
            throw new IllegalStateException("internal token signing failed", e);
        }
        return jwt.serialize();
    }
}
