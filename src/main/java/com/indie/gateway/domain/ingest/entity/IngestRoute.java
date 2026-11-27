package com.indie.gateway.domain.ingest.entity;

import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.Arrays;
import java.util.List;

/**
 * 서명 요청의 외부 경로와 내부 서비스 경로를 고정 매핑합니다.
 * 수집 키와 회원 데이터 키를 구분하며, maxItems가 0이면 묶음 검사를 생략합니다.
 * TODO: Catalog에 굿즈 수집 API가 생기면 매핑을 추가합니다.
 */
public enum IngestRoute {

    CATALOG_ARTISTS(HttpMethod.POST, "/ingest/catalog/artists", "catalog", "catalog", "/internal/catalog/artists", 500, KeySet.COLLECT),
    CATALOG_ALBUMS(HttpMethod.POST, "/ingest/catalog/albums", "catalog", "catalog", "/internal/catalog/albums", 500, KeySet.COLLECT),
    CATALOG_DIFF(HttpMethod.POST, "/ingest/catalog/diff", "catalog", "catalog", "/internal/catalog/ingest/diff", 1000, KeySet.COLLECT),
    // 회원 데이터 (member-service docs/v2_member_service_re.md §6.5, §6.6) - {userId} 는 그대로 Member 로 넘깁니다 (형식 검사는 Member)
    MEMBER_METRICS(HttpMethod.GET, "/ingest/members/{userId}/metrics", "member", "member", "/internal/members/{userId}/metrics", 0, KeySet.MEMBER),
    MEMBER_EMBEDDING(HttpMethod.GET, "/ingest/members/{userId}/embedding", "member", "member", "/internal/members/{userId}/embedding", 0, KeySet.MEMBER),
    MEMBER_EMBEDDING_SAVE(HttpMethod.PUT, "/ingest/members/{userId}/embedding", "member", "member", "/internal/members/{userId}/embedding", 0, KeySet.MEMBER);

    /**
     * 서명 키 묶음 - 경로마다 받는 키가 다릅니다. 수집 키로는 회원 데이터를 읽을 수 없고, 회원 데이터 키로는 수집 경로에 쓸 수 없습니다
     * (수집기는 KT 에 있고, AI 가 수집 키를 가지면 승인 없이 /ingest 로 쓸 수 있습니다 - docs/29).
     */
    public enum KeySet {
        COLLECT("ingest.keys"), MEMBER("ingest.member-keys");

        private final String property;   // configtree 파일 이름 = {property}.{키 ID}

        KeySet(String property) {
            this.property = property;
        }

        public String property() { return property; }
    }

    private final HttpMethod method;
    private final String path;           // {변수} 가 있으면 한 구간과 맞습니다
    private final PathPattern pattern;
    private final String service;        // lb://<k8s Service 이름>
    private final String audience;       // 내부 토큰 aud
    private final String internalPath;
    private final int maxItems;
    private final KeySet keySet;

    IngestRoute(HttpMethod method, String path, String service, String audience, String internalPath, int maxItems, KeySet keySet) {
        this.method = method;
        this.path = path;
        this.pattern = PathPatternParser.defaultInstance.parse(path);
        this.service = service;
        this.audience = audience;
        this.internalPath = internalPath;
        this.maxItems = maxItems;
        this.keySet = keySet;
    }

    /** 경로가 맞는 매핑 전부 - 같은 경로에 메서드만 다른 매핑이 있을 수 있습니다 (비면 404, 메서드가 없으면 405) */
    public static List<IngestRoute> of(String path) {
        PathContainer container = PathContainer.parsePath(path);
        return Arrays.stream(values()).filter(r -> r.pattern.matches(container)).toList();
    }

    public HttpMethod method() { return method; }

    public String path() { return path; }

    public String service() { return service; }

    public String audience() { return audience; }

    public String internalPath() { return internalPath; }

    public int maxItems() { return maxItems; }

    public KeySet keySet() { return keySet; }

    /** Circuit Breaker 이름 - 사용자 경로(catalog, concert, member)와 나눠서, 큰 배치가 느려도 사용자 화면 쪽 CB 가 열리지 않게 */
    public String circuitBreaker() { return "ingest-" + audience; }
}
