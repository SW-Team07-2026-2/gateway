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
    public static final String AUTH_REFRESH = "auth-refresh";
    public static final String OAUTH2_AUTHORIZE = "oauth2-authorize";
    public static final String INGEST = "ingest";
    public static final String MEMBER_METRICS = "member-metrics";
    public static final String SEARCH = "search";

    /** 캐시용 Redis에서 토큰 버킷으로 분당 호출 한도를 적용합니다. Redis 장애 시 요청을 허용합니다. */
    @Bean
    public RedisRateLimiter redisRateLimiter(@Qualifier("cacheRedis") ReactiveStringRedisTemplate cacheRedis,
                                             @Qualifier(RedisRateLimiter.REDIS_SCRIPT_NAME) RedisScript<List<Long>> script,
                                             ConfigurationService configurationService) {
        RedisRateLimiter limiter = new RedisRateLimiter(cacheRedis, script, configurationService);
        limiter.getConfig().put(CHATBOT, perMinute(5));            // /api/chatbot/** - 사용자 ID
        limiter.getConfig().put(AUTH_REFRESH, perMinute(10));      // /api/auth/refresh - 사용자 ID
        limiter.getConfig().put(OAUTH2_AUTHORIZE, perMinute(20));  // /oauth2/authorization/** - 클라이언트 IP
        limiter.getConfig().put(MEMBER_METRICS, perMinute(30));    // POST /api/members/me/metrics - 사용자 ID (프론트는 모아서 10초에 한 번)
        limiter.getConfig().put(INGEST, new RedisRateLimiter.Config()   // /ingest/** - 수집 키별 초당 10건 (docs/22 G7, RDS 보호)
                .setReplenishRate(10).setBurstCapacity(10).setRequestedTokens(1));
        // 검색 - 회원(비로그인은 IP)당 분당 500회 (결정): 사람이 칠 수 있는 타자 500타/분을 1타 = 1요청으로 본 값.
        // 토큰 초당 25, 요청당 3 = 초당 8.3회. 버킷 90 = 한 번에 30회 - 붙여넣기, 키를 누르고 있기(초당 약 30회), 스크롤이 겹쳐도 1초는 받습니다
        limiter.getConfig().put(SEARCH, new RedisRateLimiter.Config()
                .setReplenishRate(25).setBurstCapacity(90).setRequestedTokens(3));
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
