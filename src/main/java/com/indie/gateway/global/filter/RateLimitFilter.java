package com.indie.gateway.global.filter;

import com.indie.gateway.domain.auth.entity.TokenClaims;
import com.indie.gateway.global.common.exception.CommonErrorCode;
import com.indie.gateway.global.common.response.ErrorResponses;
import com.indie.gateway.global.common.util.ClientIpResolver;
import com.indie.gateway.global.common.util.GatewayAttributes;
import com.indie.gateway.global.config.RateLimiterConfig;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Gateway 자체 API를 포함해 요청 호출 한도를 검사합니다.
 * 로그인 회원은 회원 ID, 비로그인 요청은 클라이언트 IP를 기준으로 제한합니다.
 */
@Component
public class RateLimitFilter implements WebFilter, Ordered {

    private final RedisRateLimiter limiter;
    private final ClientIpResolver clientIpResolver;

    public RateLimitFilter(RedisRateLimiter limiter, ClientIpResolver clientIpResolver) {
        this.limiter = limiter;
        this.clientIpResolver = clientIpResolver;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String bucket = bucket(exchange.getRequest());
        if (bucket == null) {
            return chain.filter(exchange);
        }
        String key = RateLimiterConfig.OAUTH2_AUTHORIZE.equals(bucket) ? "ip:" + clientIpResolver.resolve(exchange) : userKey(exchange);
        return limiter.isAllowed(bucket, bucket + ":" + key)
                .flatMap(response -> response.isAllowed()
                        ? chain.filter(exchange)
                        : ErrorResponses.write(exchange, CommonErrorCode.TOO_MANY_REQUESTS));
    }

    private static String bucket(ServerHttpRequest request) {
        String path = request.getPath().value();
        if (path.equals("/api/chatbot") || path.startsWith("/api/chatbot/")) return RateLimiterConfig.CHATBOT;
        if (HttpMethod.POST.equals(request.getMethod()) && path.equals("/api/auth/refresh")) return RateLimiterConfig.AUTH_REFRESH;
        if (path.startsWith("/oauth2/authorization/")) return RateLimiterConfig.OAUTH2_AUTHORIZE;
        if (HttpMethod.POST.equals(request.getMethod()) && path.equals("/api/members/me/metrics")) return RateLimiterConfig.MEMBER_METRICS;
        if (HttpMethod.GET.equals(request.getMethod()) && isSearch(request, path)) return RateLimiterConfig.SEARCH;
        return null;
    }

    /**
     * 검색 - 검색, 팔로우 탭 경로 전부(칩만 바꿔도 다시 부름)와, 검색어(q)를 붙인 그 밖의 catalog, concert 조회
     * (가입 화면 아티스트 검색 /api/catalog/artists?q= 등). 상세, 장바구니, 예매, 대기열은 대상이 아닙니다
     */
    private static boolean isSearch(ServerHttpRequest request, String path) {
        if (path.equals("/api/catalog/search") || path.startsWith("/api/catalog/search/") || path.startsWith("/api/catalog/following/")
                || path.equals("/api/concerts/search") || path.equals("/api/concerts/following")) {
            return true;
        }
        return request.getQueryParams().containsKey("q")
                && (path.startsWith("/api/catalog/") || path.equals("/api/concerts") || path.startsWith("/api/concerts/"));
    }

    private String userKey(ServerWebExchange exchange) {
        TokenClaims claims = GatewayAttributes.claims(exchange);
        return claims != null ? "user:" + claims.sub() : "ip:" + clientIpResolver.resolve(exchange);
    }

    @Override
    public int getOrder() {
        return GatewayFilterOrder.RATE_LIMIT;
    }
}
