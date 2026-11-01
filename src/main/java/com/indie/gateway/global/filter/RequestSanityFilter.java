package com.indie.gateway.global.filter;

import com.indie.gateway.global.common.exception.CommonErrorCode;
import com.indie.gateway.global.common.response.ErrorResponses;
import com.indie.gateway.global.common.util.PathRules;
import com.indie.gateway.global.config.GatewayProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * ⓪ 코드 레벨 WAF 의 1차 — 허용 목록(positive security) 방식으로 형식이 깨진 요청을 라우팅 전에 끊습니다.
 * 🔴 SQL 키워드 · 정규식 블랙리스트는 만들지 않습니다 (챗봇 질문의 select · drop 이 차단됩니다).
 */
@Component
public class RequestSanityFilter implements WebFilter, Ordered {

    private static final Set<HttpMethod> ALLOWED_METHODS = Set.of(
            HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH,
            HttpMethod.DELETE, HttpMethod.OPTIONS, HttpMethod.HEAD);

    private final GatewayProperties.Sanity limits;
    private final MeterRegistry meterRegistry;

    public RequestSanityFilter(GatewayProperties props, MeterRegistry meterRegistry) {
        this.limits = props.sanity();
        this.meterRegistry = meterRegistry;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        Rejection rejection = inspect(exchange.getRequest());
        if (rejection == null) {
            return chain.filter(exchange);
        }
        meterRegistry.counter("gateway.sanity.reject", "rule", rejection.rule()).increment();
        return ErrorResponses.write(exchange, rejection.code());
    }

    private Rejection inspect(ServerHttpRequest request) {
        HttpMethod method = request.getMethod();
        if (!ALLOWED_METHODS.contains(method)) {
            return new Rejection("method", CommonErrorCode.METHOD_NOT_ALLOWED);
        }

        String rawPath = request.getURI().getRawPath();
        // 관리 포트(:8090) 프로브는 Host 가 Pod IP 라 Host 검사에서 제외합니다
        // 수집 경로는 클러스터 안 Host(gateway.indie…)만 받으므로 IngestFilter 가 따로 검사합니다 (외부 Host 면 404)
        if (!rawPath.startsWith("/actuator/") && !PathRules.isIngest(rawPath) && !isAllowedHost(request)) {
            return invalid("host");
        }

        // ★ 경로 검사는 "디코드 후" — %2e%2e%2f 는 디코드 전엔 깨끗해 보인다
        String decoded;
        try {
            decoded = URLDecoder.decode(rawPath, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return invalid("path_encoding");
        }
        String lower = decoded.toLowerCase(Locale.ROOT);
        if (decoded.contains("..") || decoded.contains("\\") || decoded.indexOf('\0') >= 0
                || lower.contains("%2e") || lower.contains("%2f") || lower.contains("%5c")) {   // 이중 인코딩
            return invalid("path_traversal");
        }
        long segments = List.of(decoded.split("/")).stream().filter(s -> !s.isEmpty()).count();
        if (segments > limits.maxPathSegments()) {
            return invalid("path_depth");
        }

        MultiValueMap<String, String> query = request.getQueryParams();
        int queryCount = query.values().stream().mapToInt(List::size).sum();
        if (queryCount > limits.maxQueryParams()
                || query.keySet().stream().anyMatch(k -> k.length() > limits.maxQueryKeyLength())) {
            return invalid("query");
        }

        HttpHeaders headers = request.getHeaders();
        long headerBytes = 0;
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            for (String v : e.getValue()) headerBytes += e.getKey().length() + v.length() + 4;
        }
        if (headerBytes > limits.maxHeaderBytes()) {
            return new Rejection("header_size", CommonErrorCode.HEADER_TOO_LARGE);
        }

        long contentLength = headers.getContentLength();
        boolean upload = rawPath.startsWith(limits.uploadPathPrefix());
        if (contentLength > (upload ? limits.maxUploadBytes() : limits.maxBodyBytes())) {
            return new Rejection("body_size", CommonErrorCode.PAYLOAD_TOO_LARGE);
        }

        if (PathRules.isStateChanging(method) && !isAllowedContentType(headers, contentLength)) {
            return new Rejection("content_type", CommonErrorCode.UNSUPPORTED_MEDIA_TYPE);
        }
        return null;
    }

    private boolean isAllowedHost(ServerHttpRequest request) {
        InetSocketAddress host = request.getHeaders().getHost();   // 포트 제외
        return host != null && limits.allowedHosts().stream()
                .anyMatch(h -> h.equalsIgnoreCase(host.getHostString()));
    }

    /** 본문이 없는 POST (팔로우 · 로그아웃 등)는 Content-Type 이 없어도 됩니다 */
    private static boolean isAllowedContentType(HttpHeaders headers, long contentLength) {
        MediaType type = headers.getContentType();
        if (type == null) {
            return contentLength <= 0;
        }
        return MediaType.APPLICATION_JSON.isCompatibleWith(type) || MediaType.MULTIPART_FORM_DATA.isCompatibleWith(type);
    }

    private static Rejection invalid(String rule) {
        return new Rejection(rule, CommonErrorCode.INVALID_REQUEST);
    }

    private record Rejection(String rule, CommonErrorCode code) {}

    @Override
    public int getOrder() {
        return GatewayFilterOrder.REQUEST_SANITY;
    }
}
