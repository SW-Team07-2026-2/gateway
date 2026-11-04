package com.indie.gateway.global.common.exception;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.net.ConnectException;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayErrorHandlerTest {

    private final GatewayErrorHandler handler = new GatewayErrorHandler();

    private int status(Throwable ex) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/payments/webhook/toss").build());
        handler.handle(exchange, ex).block();
        return exchange.getResponse().getStatusCode().value();
    }

    /** 폴백 없는 라우트(payment)의 하위 서비스가 꺼져 있으면 500 이 아니라 503 */
    @Test
    void refusedConnectionIsServiceUnavailable() {
        assertThat(status(new IllegalStateException("wrapped", new ConnectException("Connection refused")))).isEqualTo(503);
    }

    @Test
    void unexpectedErrorStaysInternal() {
        assertThat(status(new IllegalStateException("bug"))).isEqualTo(500);
    }

    /** 본문은 서비스와 같은 ApiResponse 실패 포맷 */
    @Test
    void bodyIsApiResponseFailure() throws Exception {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/x").build());
        handler.handle(exchange, new ConnectException("Connection refused")).block();
        JsonNode body = new ObjectMapper().readTree(exchange.getResponse().getBodyAsString().block());
        assertThat(body.get("isSuccess").asBoolean()).isFalse();
        assertThat(body.get("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(body.has("result")).isFalse();
    }
}
