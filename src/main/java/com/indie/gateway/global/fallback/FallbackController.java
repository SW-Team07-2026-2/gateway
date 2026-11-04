package com.indie.gateway.global.fallback;

import com.indie.gateway.global.common.exception.CommonErrorCode;
import com.indie.gateway.global.common.response.ApiResponse;
import com.indie.gateway.global.common.response.ErrorResponses;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/**
 * /fallback/{service} — Circuit Breaker 개방 · 다운스트림 장애 시 (§7-7 · §10).
 * 예외: catalog 검색은 503 대신 200 + 빈 결과 + degraded:true (검색창이 에러를 뱉는 것보다 "결과 없음"이 낫다).
 */
@RestController
public class FallbackController {

    @RequestMapping("/fallback/{service}")
    public Mono<Void> fallback(@PathVariable String service, ServerWebExchange exchange) {
        if ("catalog".equals(service) && isCatalogSearch(exchange)) {
            return ErrorResponses.write(exchange, HttpStatus.OK, ApiResponse.onSuccess(Map.of("items", List.of(), "degraded", true)));
        }
        if ("ingest".equals(service)) {
            return ingest(exchange);
        }
        String message = "chatbot".equals(service) ? "잠시 후 다시 시도해주세요" : "일시적으로 이용할 수 없습니다";
        return ErrorResponses.write(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                ErrorResponses.failure(exchange, CommonErrorCode.SERVICE_UNAVAILABLE, message, Map.of("service", service)));
    }

    /**
     * 수집 (docs/22) — 응답 지연은 504, 연결 실패(재시도 1회 뒤) · CB 개방 · 인스턴스 없음은 503. 둘 다 Retry-After 30초.
     * 504 는 하위 서비스가 끝까지 처리했을 수도 있지만 upsert 가 멱등(S6)이라 같은 본문을 다시 보내도 안전합니다.
     */
    private static Mono<Void> ingest(ServerWebExchange exchange) {
        Throwable cause = exchange.getAttribute(ServerWebExchangeUtils.CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR);
        CommonErrorCode code = isTimeout(cause) ? CommonErrorCode.GATEWAY_TIMEOUT : CommonErrorCode.SERVICE_UNAVAILABLE;
        exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER, "30");   // CB 개방 대기(10초)보다 길게
        return ErrorResponses.write(exchange, code.getStatus(),
                ErrorResponses.failure(exchange, code, code.getMessage(), Map.of("service", "ingest")));
    }

    /** NettyRoutingFilter 의 응답 타임아웃은 504 ResponseStatusException(원인 TimeoutException), TimeLimiter 는 TimeoutException */
    private static boolean isTimeout(Throwable t) {
        for (Throwable e = t; e != null; e = e.getCause()) {
            if (e instanceof TimeoutException
                    || (e instanceof ResponseStatusException rse && rse.getStatusCode().value() == 504)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCatalogSearch(ServerWebExchange exchange) {
        Set<URI> originals = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ORIGINAL_REQUEST_URL_ATTR);
        return originals != null && originals.stream().anyMatch(u -> u.getPath().startsWith("/api/catalog/search"));
    }
}
