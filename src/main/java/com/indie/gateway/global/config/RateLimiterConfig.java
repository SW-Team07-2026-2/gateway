package com.indie.gateway.global.config;

import com.indie.gateway.global.common.util.ClientIpResolver;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.support.ConfigurationService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.web.server.adapter.ForwardedHeaderTransformer;

import java.util.List;

@Configuration
public class RateLimiterConfig {

    public static final String CHATBOT = "chatbot";
    public static final String ADMIN_AI = "admin-ai";
    public static final String AUTH_REFRESH = "auth-refresh";
    public static final String OAUTH2_AUTHORIZE = "oauth2-authorize";
    public static final String INGEST = "ingest";

    /**
     * ⑥ 호출 한도 — 저장소는 캐시용 Redis (§5-⑥). 한도는 "분당 N회"라 초당 1토큰 · 버킷 60 으로 두고
     * 요청당 소모 토큰(60/N)으로 표현합니다. Redis 장애 시 RedisRateLimiter 는 통과시킵니다.
     */
    @Bean
    public RedisRateLimiter redisRateLimiter(@Qualifier("cacheRedis") ReactiveStringRedisTemplate cacheRedis,
                                             @Qualifier(RedisRateLimiter.REDIS_SCRIPT_NAME) RedisScript<List<Long>> script,
                                             ConfigurationService configurationService) {
        RedisRateLimiter limiter = new RedisRateLimiter(cacheRedis, script, configurationService);
        limiter.getConfig().put(CHATBOT, perMinute(5));            // /api/chatbot/** — 사용자 ID
        limiter.getConfig().put(ADMIN_AI, perMinute(10));          // /api/admin-ai/** — 관리자 ID
        limiter.getConfig().put(AUTH_REFRESH, perMinute(10));      // /api/auth/refresh — 사용자 ID
        limiter.getConfig().put(OAUTH2_AUTHORIZE, perMinute(20));  // /oauth2/authorization/** — 클라이언트 IP
        limiter.getConfig().put(INGEST, new RedisRateLimiter.Config()   // /ingest/** — 수집 키별 초당 10건 (docs/22 G7 · RDS 보호)
                .setReplenishRate(10).setBurstCapacity(10).setRequestedTokens(1));
        return limiter;
    }

    @Bean("forwardedHeaderTransformer")
    @ConditionalOnProperty(name = "server.forward-headers-strategy", havingValue = "framework")
    public ForwardedHeaderTransformer forwardedHeaderTransformer(GatewayProperties props) {
        return new ClientIpResolver.TrustedForwardedHeaderTransformer(props);
    }

    private static RedisRateLimiter.Config perMinute(int limit) {
        return new RedisRateLimiter.Config()
                .setReplenishRate(1)
                .setBurstCapacity(60)
                .setRequestedTokens(60 / limit);
    }
}
