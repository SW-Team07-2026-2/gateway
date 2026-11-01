package com.indie.gateway.domain.ingest.exception;

import com.indie.gateway.global.common.exception.BaseErrorCode;
import org.springframework.http.HttpStatus;

/** 수집 경로 (/ingest/**) 에러 — 나머지(404 · 413 · 415 · 429 · 503 · 504)는 CommonErrorCode 를 씁니다 */
public enum IngestErrorCode implements BaseErrorCode {

    INGEST_SIGNATURE_INVALID(HttpStatus.UNAUTHORIZED, "수집 서명을 확인할 수 없습니다"),
    INGEST_REPLAYED(HttpStatus.UNAUTHORIZED, "이미 받은 요청이거나 요청 시각이 허용 범위를 벗어났습니다"),
    INGEST_BODY_TIMEOUT(HttpStatus.REQUEST_TIMEOUT, "요청 본문을 제시간에 받지 못했습니다");

    private final HttpStatus status;
    private final String message;

    IngestErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    @Override
    public HttpStatus getStatus() { return status; }

    @Override
    public String getCode() { return name(); }

    @Override
    public String getMessage() { return message; }
}
