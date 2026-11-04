package com.indie.gateway.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties("app")
public record GatewayProperties(
        String domain,
        Cookie cookie,
        Token token,
        InternalToken internalToken,
        String memberBaseUrl,
        OAuth oauth,
        Sanity sanity,
        Trust trust,
        Redis redis,
        Ingest ingest) {

    public record Cookie(String domain, boolean secure, String sameSite, long atMaxAge, long rtMaxAge, long signupMaxAge) {}

    public record Token(String issuer, long refreshThresholdSeconds, String jwksUri, Duration jwksCacheTtl,
                        Duration jwksRefetchCooldown, String fallbackPublicKeyPath) {}

    public record InternalToken(String issuer, String audience, long ttlSeconds) {}

    public record OAuth(String redirectBase, String successRedirect, String signupRedirect, String failureRedirect) {}

    public record Sanity(long maxBodyBytes, int maxQueryParams,
                         int maxQueryKeyLength, int maxHeaderBytes, int maxPathSegments, List<String> allowedHosts) {}

    public record Trust(int proxyDepth) {}

    public record Redis(Endpoint cache) {
        public record Endpoint(String host, int port) {}
    }

    /** 수집 경로 /ingest/** (docs/22 §1-4) — 키는 시크릿이라 여기 두지 않습니다 (IngestSignatureVerifier) */
    public record Ingest(List<String> hosts, long clockSkewSeconds, Duration nonceTtl, Duration bodyReadTimeout,
                         Duration responseTimeout, long tokenTtlSeconds, int maxDepth) {}
}
