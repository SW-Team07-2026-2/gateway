package com.indie.gateway.domain.auth.service;

import com.indie.gateway.global.config.GatewayProperties;
import com.indie.gateway.domain.auth.entity.TokenClaims;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.security.interfaces.RSAPublicKey;
import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * AT 검증 — 서명 RS256 · iss · exp · nbf.
 * 🔴 alg 를 토큰에서 읽어 그대로 쓰지 않습니다. RS256 이 아니면 거절 (alg=none · HS256 혼동 공격 차단).
 */
@Component
public class AccessTokenVerifier {

    private static final Logger log = LoggerFactory.getLogger(AccessTokenVerifier.class);

    private final JwksKeyStore keyStore;
    private final String issuer;
    private final Clock clock;

    public AccessTokenVerifier(JwksKeyStore keyStore, GatewayProperties props, Clock clock) {
        this.keyStore = keyStore;
        this.issuer = props.token().issuer();
        this.clock = clock;
    }

    public Mono<TokenClaims> verify(String token) {
        SignedJWT jwt;
        try {
            jwt = SignedJWT.parse(token);          // alg=none 은 JWS 가 아니라 여기서 ParseException
        } catch (ParseException e) {
            return Mono.error(TokenException.invalid());
        }
        if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())) {
            return Mono.error(TokenException.invalid());
        }
        return keyStore.find(jwt.getHeader().getKeyID())
                .switchIfEmpty(Mono.error(TokenException.invalid()))
                .flatMap(key -> Mono.fromCallable(() -> check(jwt, key)));
    }

    /** 이미 Member 가 발급해 돌려준 토큰에서 클레임만 읽습니다 (선제 갱신 직후) */
    public static TokenClaims readClaims(String token) {
        try {
            return toClaims(SignedJWT.parse(token).getJWTClaimsSet());
        } catch (ParseException e) {
            throw TokenException.invalid();
        }
    }

    private TokenClaims check(SignedJWT jwt, RSAPublicKey key) throws ParseException {
        try {
            if (!jwt.verify(new RSASSAVerifier(key))) {
                throw TokenException.invalid();
            }
        } catch (JOSEException e) {
            throw TokenException.invalid();
        }
        JWTClaimsSet claims = jwt.getJWTClaimsSet();
        Date now = Date.from(clock.instant());
        // 서명은 맞는데 클레임이 틀린 토큰 — 발급 쪽 설정 오류 신호라 사유를 남깁니다 (응답 코드는 하나로)
        if (!issuer.equals(claims.getIssuer())) {
            throw rejected("issuer");
        }
        if (claims.getSubject() == null) {
            throw rejected("subject");
        }
        if (claims.getJWTID() == null) {
            throw rejected("jti");
        }
        if (claims.getNotBeforeTime() != null && now.before(claims.getNotBeforeTime())) {
            throw TokenException.invalid();
        }
        if (claims.getExpirationTime() == null) {
            throw rejected("exp");                     // 만료 시각이 없는 토큰은 만료가 아니라 잘못된 토큰
        }
        if (!now.before(claims.getExpirationTime())) {
            throw TokenException.expired();
        }
        return toClaims(claims);
    }

    private static TokenException rejected(String reason) {
        log.warn("access_token_rejected reason={}", reason);
        return TokenException.invalid();
    }

    private static TokenClaims toClaims(JWTClaimsSet claims) throws ParseException {
        List<String> roles = claims.getStringListClaim("roles");
        Instant exp = claims.getExpirationTime() == null ? Instant.EPOCH : claims.getExpirationTime().toInstant();
        return new TokenClaims(claims.getSubject(), roles == null ? List.of() : roles, claims.getJWTID(), exp);
    }

    public static class TokenException extends RuntimeException {
        private final String code;

        private TokenException(String code) {
            super(code, null, false, false);
            this.code = code;
        }

        static TokenException invalid() { return new TokenException("TOKEN_INVALID"); }

        static TokenException expired() { return new TokenException("TOKEN_EXPIRED"); }

        public String code() { return code; }
    }
}
