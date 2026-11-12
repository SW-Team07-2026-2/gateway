package com.indie.gateway.domain.ingest.service;

import com.indie.gateway.domain.ingest.entity.IngestRoute;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * 수집 서명 (docs/22 G4) — Payment 웹훅 HMAC 검증과 같은 방식.
 * <pre>
 * base64( HMAC-SHA256( key, "{METHOD}\n{path}[?{query}]\n{timestamp}\n{nonce}\n{hex(sha256(body))}" ) )
 * </pre>
 * 쿼리는 보낸 그대로(인코딩된 원문)이고, 없으면 "?" 도 붙이지 않습니다 — 수집(POST · 쿼리 없음)의 서명 문자열은 전과 같습니다.
 * 키는 configtree 파일 {키 묶음}.{키 ID} 에서 읽습니다 — 수집은 ingest.keys.* · 회원 데이터는 ingest.member-keys.* (IngestRoute.KeySet).
 * 키 회전 — 현재 · 직전 키를 같이 둡니다. 시크릿이라 GatewayProperties(record · toString 에 값이 찍힘)에 넣지 않습니다.
 */
@Component
public class IngestSignatureVerifier {

    private static final Logger log = LoggerFactory.getLogger(IngestSignatureVerifier.class);
    private static final String HMAC = "HmacSHA256";

    private final Map<IngestRoute.KeySet, Map<String, SecretKeySpec>> keys = new EnumMap<>(IngestRoute.KeySet.class);

    public IngestSignatureVerifier(Environment environment) {
        for (IngestRoute.KeySet keySet : IngestRoute.KeySet.values()) {
            Map<String, SecretKeySpec> loaded = new HashMap<>();
            Binder.get(environment).bind(keySet.property(), Bindable.mapOf(String.class, String.class)).orElse(Map.of())
                    .forEach((keyId, value) -> {
                        byte[] secret = value.trim().getBytes(StandardCharsets.UTF_8);
                        if (secret.length < 32) {
                            throw new IllegalStateException(keySet.property() + "." + keyId + " 는 32바이트 이상이어야 합니다");
                        }
                        loaded.put(keyId, new SecretKeySpec(secret, HMAC));
                    });
            if (loaded.isEmpty()) {
                log.warn("ingest_keys_missing keys={} — 이 키를 쓰는 /ingest/** 경로는 전부 401 입니다", keySet.property());
            }
            keys.put(keySet, loaded);
        }
    }

    /**
     * 키 ID 가 없거나 서명이 다르면 false (둘을 구분하지 않습니다 — 키 ID 존재 여부를 알려주지 않게).
     * 다른 묶음의 키 ID 도 "없는 키" 입니다. target 은 경로 + (쿼리가 있으면 "?" + 쿼리 원문).
     */
    public boolean verify(IngestRoute.KeySet keySet, String keyId, String method, String target,
                          String timestamp, String nonce, String bodySha256Hex, String signature) {
        SecretKeySpec key = keys.get(keySet).get(keyId);
        if (key == null) {
            return false;
        }
        byte[] provided;
        try {
            provided = Base64.getDecoder().decode(signature);
        } catch (IllegalArgumentException e) {
            return false;
        }
        String canonical = method + "\n" + target + "\n" + timestamp + "\n" + nonce + "\n" + bodySha256Hex;
        return MessageDigest.isEqual(hmac(key, canonical), provided);   // ★ 상수 시간 비교
    }

    private static byte[] hmac(SecretKeySpec key, String data) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(key);
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }
}
