package com.indie.gateway;

import com.indie.gateway.global.config.GatewayProperties;

import java.time.Duration;
import java.util.List;

/** 단위 테스트용 설정 — application.yml 의 기본값과 같습니다 */
public final class TestProps {

    public static GatewayProperties create(String fallbackKeyPath) {
        return new GatewayProperties("example.com",
                new GatewayProperties.Cookie(".example.com", true, "Lax", 1800, 1209600, 1800),
                new GatewayProperties.Token("indie-member", 300, "http://127.0.0.1:1/.well-known/jwks.json",
                        Duration.ofMinutes(10), Duration.ofSeconds(30), fallbackKeyPath),
                new GatewayProperties.InternalToken("gateway", "member", 300),
                "http://127.0.0.1:1",
                new GatewayProperties.OAuth("https://api.example.com", "https://example.com/", "https://example.com/signup", "https://example.com/login"),
                new GatewayProperties.Sanity(1048576, 30, 64, 8192, 20, List.of("api.example.com")),
                new GatewayProperties.Trust(1),
                new GatewayProperties.Redis(new GatewayProperties.Redis.Endpoint("localhost", 6380)),
                new GatewayProperties.Ingest(List.of("gateway", "gateway.indie.svc.cluster.local"), 300,
                        Duration.ofSeconds(600), Duration.ofSeconds(10), Duration.ofSeconds(30), 60, 10));
    }

    private TestProps() {}
}
