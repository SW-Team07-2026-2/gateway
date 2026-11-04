package com.indie.gateway.global.common.exception;

import com.indie.gateway.global.common.response.ErrorResponses;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.ConnectException;

/**
 * 프레임워크가 내는 에러(404 라우트 없음 · 503 인스턴스 없음 · 504 타임아웃 …)를 공통 포맷으로 통일합니다 (§7-8).
 * 기본 Spring 에러 본문은 path · trace 를 노출하므로 교체합니다. 기본 핸들러(-1)보다 먼저 실행됩니다.
 */
@Component
@Order(-2)
public class GatewayErrorHandler implements ErrorWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayErrorHandler.class);

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(ex);
        }
        HttpStatusCode status = ex instanceof ResponseStatusException rse ? rse.getStatusCode()
                : isConnectFailure(ex) ? HttpStatusCode.valueOf(503) : HttpStatusCode.valueOf(500);
        if (status.value() == 500) {
            log.error("gateway_error status=500", ex);          // 예상 못 한 오류 — 스택 필요
        } else if (status.is5xxServerError()) {
            log.error("gateway_error status={} error={}", status.value(), ex.toString());
        }
        CommonErrorCode code = switch (status.value()) {
            case 400 -> CommonErrorCode.INVALID_REQUEST;
            case 404 -> CommonErrorCode.NOT_FOUND;
            case 405 -> CommonErrorCode.METHOD_NOT_ALLOWED;
            case 413 -> CommonErrorCode.PAYLOAD_TOO_LARGE;
            case 415 -> CommonErrorCode.UNSUPPORTED_MEDIA_TYPE;
            case 429 -> CommonErrorCode.TOO_MANY_REQUESTS;
            case 503 -> CommonErrorCode.SERVICE_UNAVAILABLE;
            case 504 -> CommonErrorCode.GATEWAY_TIMEOUT;
            default -> status.is4xxClientError() ? CommonErrorCode.BAD_REQUEST : CommonErrorCode.INTERNAL_ERROR;
        };
        // 상태는 프레임워크가 정한 값 그대로 (408 · 502 등도 code 만 공통으로)
        return ErrorResponses.write(exchange, status, ErrorResponses.failure(exchange, code, code.getMessage(), null));
    }

    /**
     * 하위 서비스 연결 거부 — 폴백이 없는 라우트(payment)는 서킷이 열리기 전까지 이 예외가 그대로 올라옵니다.
     * 500 이 아니라 503 입니다 (서킷이 열린 뒤의 응답과 같게).
     */
    static boolean isConnectFailure(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof ConnectException) return true;
        }
        return false;
    }
}
