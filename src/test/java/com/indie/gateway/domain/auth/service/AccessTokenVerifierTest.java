package com.indie.gateway.domain.auth.service;

import com.indie.gateway.TestProps;
import com.indie.gateway.domain.auth.entity.TokenClaims;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** JWKS 는 닿지 않는 주소 → 백업 공개키(파일)로 검증되는 경로까지 함께 확인합니다 */
class AccessTokenVerifierTest {

    @TempDir
    static Path dir;
    static RSAKey key;
    static AccessTokenVerifier verifier;

    @BeforeAll
    static void setUp() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("k1").generate();
        String pem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(key.toRSAPublicKey().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
        Path file = Files.writeString(dir.resolve("public.pem"), pem);
        var props = TestProps.create(file.toString());
        verifier = new AccessTokenVerifier(new JwksKeyStore(WebClient.builder(), props, Clock.systemUTC()), props, Clock.systemUTC());
    }

    @Test
    void validRs256TokenIsAccepted() throws Exception {
        TokenClaims claims = verifier.verify(rs256(Instant.now().plusSeconds(600))).block();
        assertThat(claims.sub()).isEqualTo("1");
        assertThat(claims.roles()).containsExactly("USER");
    }

    @Test
    void expiredTokenIsReportedAsExpired() throws Exception {
        assertThatThrownBy(() -> verifier.verify(rs256(Instant.now().minusSeconds(1))).block())
                .isInstanceOfSatisfying(AccessTokenVerifier.TokenException.class, e -> assertThat(e.code()).isEqualTo("TOKEN_EXPIRED"));
    }

    /** 만료 시각이 없는 토큰은 만료(재발급 유도)가 아니라 잘못된 토큰 */
    @Test
    void tokenWithoutExpIsInvalidNotExpired() throws Exception {
        JWTClaimsSet noExp = new JWTClaimsSet.Builder().issuer("indie-member").subject("1").jwtID("j1").build();
        assertThatThrownBy(() -> verifier.verify(sign(noExp)).block())
                .isInstanceOfSatisfying(AccessTokenVerifier.TokenException.class, e -> assertThat(e.code()).isEqualTo("TOKEN_INVALID"));
    }

    @Test
    void tokenFromOtherIssuerIsInvalid() throws Exception {
        JWTClaimsSet other = new JWTClaimsSet.Builder().issuer("someone-else").subject("1").jwtID("j1")
                .expirationTime(Date.from(Instant.now().plusSeconds(600))).build();
        assertThatThrownBy(() -> verifier.verify(sign(other)).block())
                .isInstanceOfSatisfying(AccessTokenVerifier.TokenException.class, e -> assertThat(e.code()).isEqualTo("TOKEN_INVALID"));
    }

    /** DoD 4 */
    @Test
    void algNoneIsRejected() {
        String header = b64("{\"alg\":\"none\"}");
        String payload = b64("{\"iss\":\"indie-member\",\"sub\":\"1\",\"jti\":\"x\",\"exp\":9999999999}");
        assertThatThrownBy(() -> verifier.verify(header + "." + payload + ".").block())
                .isInstanceOf(AccessTokenVerifier.TokenException.class);
    }

    /** DoD 5 — 공개키를 HMAC 키로 쓴 알고리즘 혼동 공격 */
    @Test
    void hs256SignedWithPublicKeyIsRejected() throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID("k1").build(), claims(Instant.now().plusSeconds(600)));
        jwt.sign(new MACSigner(key.toRSAPublicKey().getEncoded()));
        assertThatThrownBy(() -> verifier.verify(jwt.serialize()).block())
                .isInstanceOf(AccessTokenVerifier.TokenException.class);
    }

    @Test
    void tokenSignedWithOtherKeyIsRejected() throws Exception {
        RSAKey other = new RSAKeyGenerator(2048).keyID("k1").generate();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").build(), claims(Instant.now().plusSeconds(600)));
        jwt.sign(new RSASSASigner(other));
        assertThatThrownBy(() -> verifier.verify(jwt.serialize()).block())
                .isInstanceOf(AccessTokenVerifier.TokenException.class);
    }

    private static String rs256(Instant exp) throws Exception {
        return sign(claims(exp));
    }

    private static String sign(JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private static JWTClaimsSet claims(Instant exp) {
        return new JWTClaimsSet.Builder().issuer("indie-member").subject("1").jwtID("j1")
                .claim("roles", List.of("USER")).expirationTime(Date.from(exp)).build();
    }

    private static String b64(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes());
    }
}
