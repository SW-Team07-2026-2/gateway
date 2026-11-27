package com.indie.gateway.global.common.util;

import com.indie.gateway.global.config.GatewayProperties;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.adapter.ForwardedHeaderTransformer;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;

/** Cloudflare의 CF-Connecting-IP 또는 신뢰하는 프록시 기준의 원격 주소에서 클라이언트 IP를 구합니다. */
@Component
public class ClientIpResolver {

    /**
     * 신뢰하는 프록시 헤더 또는 원격 주소에서 클라이언트 IP를 구합니다.
     *
     * @param exchange 현재 HTTP 요청과 응답
     */
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
     * XFF의 오른쪽에서 신뢰하는 프록시 수만큼 이동해 원격 주소를 구합니다.
     * Spring이 이 구현을 사용하도록 빈 이름을 forwardedHeaderTransformer로 지정합니다.
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
