package com.indie.gateway.domain.auth.service;

import com.indie.gateway.TestProps;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** TTL 재조회는 쿨다운(30초)에 한 번만 - Member 가 느리거나 죽어도 요청마다 JWKS 를 다시 부르지 않음 (k6 S7) */
class JwksKeyStoreTest {

    private final AtomicInteger calls = new AtomicInteger();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-25T05:28:40Z"));

    private JwksKeyStore store(ExchangeFunction member) {
        ExchangeFunction counted = request -> {
            calls.incrementAndGet();
            return member.exchange(request);
        };
        return new JwksKeyStore(WebClient.builder().exchangeFunction(counted), TestProps.create(null), clock);
    }

    @Test
    void slowMemberGetsOneRefetchNotOnePerRequest() {
        JwksKeyStore store = store(request -> Mono.never());   // 응답 없음 (2초 타임아웃 전)

        for (int i = 0; i < 1000; i++) store.find("k1");

        assertThat(calls).hasValue(1);
    }

    @Test
    void failedRefetchWaitsForCooldown() {
        JwksKeyStore store = store(request -> Mono.error(new IllegalStateException("member down")));

        store.find("k1");
        clock.advance(Duration.ofSeconds(29));
        store.find("k1");
        assertThat(calls).hasValue(1);

        clock.advance(Duration.ofSeconds(2));
        store.find("k1");
        assertThat(calls).hasValue(2);
    }

    @Test
    void loadedKeysAreNotRefetchedUntilTtl() throws Exception {
        RSAKey key = new RSAKeyGenerator(2048).keyID("k1").generate();
        String jwks = new JWKSet(key.toPublicJWK()).toString();
        JwksKeyStore store = store(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "application/json").body(jwks).build()));

        assertThat(store.find("k1").block()).isEqualTo(key.toRSAPublicKey());
        clock.advance(Duration.ofMinutes(9));
        store.find("k1");
        assertThat(calls).hasValue(1);

        clock.advance(Duration.ofMinutes(2));
        store.find("k1");
        assertThat(calls).hasValue(2);
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
