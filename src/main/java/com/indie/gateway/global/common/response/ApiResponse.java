package com.indie.gateway.global.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.indie.gateway.global.common.exception.BaseErrorCode;

/**
 * 모든 HTTP 응답 본문의 공통 포맷 (각 서비스와 동일).
 * 성공 { isSuccess: true, code: "OK", message, result } · 실패 { isSuccess: false, code, message, result?, correlationId }.
 * Member 응답을 받을 때도 이 타입으로 읽고 result 를 꺼냅니다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"isSuccess", "code", "message", "result", "correlationId"})
public record ApiResponse<T>(@JsonProperty("isSuccess") Boolean isSuccess, String code, String message, T result,
                             String correlationId) {

    public static final String SUCCESS_CODE = "OK";
    private static final String SUCCESS_MESSAGE = "성공";

    public static <T> ApiResponse<T> onSuccess(T result) {
        return new ApiResponse<>(true, SUCCESS_CODE, SUCCESS_MESSAGE, result, null);
    }

    public static <T> ApiResponse<T> onFailure(BaseErrorCode code, String message, T data, String correlationId) {
        return new ApiResponse<>(false, code.getCode(), message, data, correlationId);
    }
}
