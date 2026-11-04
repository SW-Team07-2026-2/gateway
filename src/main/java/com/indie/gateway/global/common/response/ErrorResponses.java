package com.indie.gateway.global.common.response;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indie.gateway.global.common.exception.BaseErrorCode;
import com.indie.gateway.global.common.util.GatewayAttributes;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/** 필터 · 핸들러가 응답을 직접 쓸 때 — 본문은 ApiResponse (§7-8) */
public final class ErrorResponses {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static Mono<Void> write(ServerWebExchange exchange, BaseErrorCode code) {
        return write(exchange, code.getStatus(), failure(exchange, code, code.getMessage(), null));
    }

    public static Mono<Void> write(ServerWebExchange exchange, HttpStatusCode status, ApiResponse<?> body) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] bytes;
        try {
            bytes = MAPPER.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            bytes = "{\"isSuccess\":false,\"code\":\"INTERNAL_ERROR\"}".getBytes(StandardCharsets.UTF_8);
        }
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    public static <T> ApiResponse<T> failure(ServerWebExchange exchange, BaseErrorCode code, String message, T data) {
        return ApiResponse.onFailure(code, message, data, GatewayAttributes.correlationId(exchange));
    }

    private ErrorResponses() {}
}
