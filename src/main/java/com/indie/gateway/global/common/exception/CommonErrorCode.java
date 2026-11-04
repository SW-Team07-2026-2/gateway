package com.indie.gateway.global.common.exception;

import org.springframework.http.HttpStatus;

/** 요청 검사 · 라우팅 · 하위 서비스 장애 에러 (§7-8) */
public enum CommonErrorCode implements BaseErrorCode {

    INVALID_REQUEST(400, "잘못된 요청입니다"),
    BAD_REQUEST(400, "잘못된 요청입니다"),
    FORBIDDEN(403, "권한이 없습니다"),
    NOT_FOUND(404, "찾을 수 없습니다"),
    METHOD_NOT_ALLOWED(405, "허용되지 않는 메서드입니다"),
    PAYLOAD_TOO_LARGE(413, "요청 본문이 너무 큽니다"),
    UNSUPPORTED_MEDIA_TYPE(415, "지원하지 않는 형식입니다"),
    TOO_MANY_REQUESTS(429, "요청이 너무 많습니다. 잠시 후 다시 시도해주세요"),
    HEADER_TOO_LARGE(431, "요청 헤더가 너무 큽니다"),
    INTERNAL_ERROR(500, "일시적인 오류가 발생했습니다"),
    SERVICE_UNAVAILABLE(503, "일시적으로 이용할 수 없습니다"),
    GATEWAY_TIMEOUT(504, "응답이 지연되고 있습니다");

    private final int status;
    private final String message;

    CommonErrorCode(int status, String message) {
        this.status = status;
        this.message = message;
    }

    @Override
    public HttpStatus getStatus() { return HttpStatus.valueOf(status); }

    @Override
    public String getCode() { return name(); }

    @Override
    public String getMessage() { return message; }
}
