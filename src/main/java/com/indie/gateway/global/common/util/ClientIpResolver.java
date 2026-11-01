package com.indie.gateway.global.common.util;

import com.indie.gateway.global.config.GatewayProperties;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.adapter.ForwardedHeaderTransformer;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;

/**
 * 클라이언트 IP 해석 — 이 분기를 한 곳에서 담당합니다 (§5-⑥).
 * <pre>
 * Cloudflare 경유 (운영)   CF-Connecting-IP — 엣지가 덮어쓰므로 위조 불가
 * ALB 직결                 XFF 의 오른쪽에서 proxy-depth 번째 (TrustedForwardedHeaderTransformer 가 remoteAddress 에 반영)
 * 로컬                     remoteAddress
 * </pre>
 */
@Component
public class ClientIpResolver {

    public String resolve(ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest();
        String cf = request.getHeaders().getFirst("CF-Connecting-IP");
        if (cf != null && !cf.isBlank()) {
            return cf.trim();
        }
        InetSocketAddress remote = request.getRemoteAddress();
        return remote == null ? "unknown" : remote.getHostString();
    }

    /**
     * forward-headers-strategy=framework 의 기본 변환기는 XFF 의 <b>맨 왼쪽</b>(클라이언트가 위조 가능한 값)을
     * remoteAddress 로 쓰고 XFF 헤더를 지웁니다. 신뢰하는 프록시가 덧붙인 값만 쓰도록 오른쪽에서 고릅니다.
     * 빈 이름이 "forwardedHeaderTransformer" 여야 WebHttpHandlerBuilder 가 집어 갑니다.
     */
    public static class TrustedForwardedHeaderTransformer extends ForwardedHeaderTransformer {

        private final int proxyDepth;

        public TrustedForwardedHeaderTransformer(GatewayProperties props) {
            this.proxyDepth = Math.max(1, props.trust().proxyDepth());
        }

        @Override
        public ServerHttpRequest apply(ServerHttpRequest request) {
            List<String> xff = request.getHeaders().getOrEmpty("X-Forwarded-For").stream()
                    .flatMap(v -> Arrays.stream(v.split(",")))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
            ServerHttpRequest transformed = super.apply(request);
            if (xff.size() < proxyDepth) {
                return transformed;
            }
            String trusted = xff.get(xff.size() - proxyDepth);
            return transformed.mutate().remoteAddress(InetSocketAddress.createUnresolved(trusted, 0)).build();
        }
    }
}
