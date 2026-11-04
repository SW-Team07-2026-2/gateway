package com.indie.gateway.domain.auth.exception;

import com.indie.gateway.global.common.exception.BaseErrorCode;
import org.springframework.http.HttpStatus;

/** 인증 · 토큰 · CSRF 에러 — code 는 enum 이름 그대로 응답에 나갑니다 */
public enum AuthErrorCode implements BaseErrorCode {

    TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다"),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "인증이 만료되었습니다"),
    TOKEN_REVOKED(HttpStatus.UNAUTHORIZED, "로그아웃된 토큰입니다"),
    REFRESH_INVALID(HttpStatus.UNAUTHORIZED, "다시 로그인해주세요"),
    REFRESH_REUSED(HttpStatus.UNAUTHORIZED, "다시 로그인해주세요"),
    SIGNUP_EXPIRED(HttpStatus.UNAUTHORIZED, "가입 시간이 지났어요. 다시 로그인해주세요"),
    CSRF_MISMATCH(HttpStatus.FORBIDDEN, "요청을 확인할 수 없습니다");

    private final HttpStatus status;
    private final String message;

    AuthErrorCode(HttpStatus status, String message) {
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
