package com.indie.gateway.domain.ingest.entity;

import java.util.Arrays;
import java.util.Optional;

/**
 * 수집 경로 고정 매핑 (docs/22 G2) — 여기 없는 /ingest/** 는 404. 와일드카드 전달은 없습니다.
 * maxItems 는 하위 서비스 DTO 의 @Size 와 같게 둡니다 (upsert 500 · diff 1000).
 * TODO(설계): /ingest/catalog/goods → /internal/catalog/goods (I26) — Catalog 에 엔드포인트가 생기면 추가
 */
public enum IngestRoute {

    CATALOG_ARTISTS("/ingest/catalog/artists", "catalog", "catalog", "/internal/catalog/artists", 500),
    CATALOG_ALBUMS("/ingest/catalog/albums", "catalog", "catalog", "/internal/catalog/albums", 500),
    CATALOG_DIFF("/ingest/catalog/diff", "catalog", "catalog", "/internal/catalog/ingest/diff", 1000),
    CONCERTS("/ingest/concerts", "concert", "concert", "/internal/concerts", 500),
    CONCERTS_DIFF("/ingest/concerts/diff", "concert", "concert", "/internal/concerts/ingest/diff", 1000),
    AI_DOCUMENTS("/ingest/ai/documents", "chatbot-service", "chatbot", "/internal/ai/documents", 500);   // I21 · 챗봇 담당

    private final String path;
    private final String service;        // lb://<k8s Service 이름>
    private final String audience;       // 내부 토큰 aud
    private final String internalPath;
    private final int maxItems;

    IngestRoute(String path, String service, String audience, String internalPath, int maxItems) {
        this.path = path;
        this.service = service;
        this.audience = audience;
        this.internalPath = internalPath;
        this.maxItems = maxItems;
    }

    public static Optional<IngestRoute> of(String path) {
        return Arrays.stream(values()).filter(r -> r.path.equals(path)).findFirst();
    }

    public String path() { return path; }

    public String service() { return service; }

    public String audience() { return audience; }

    public String internalPath() { return internalPath; }

    public int maxItems() { return maxItems; }

    /** Circuit Breaker 이름 — 사용자 경로(catalog · concert)와 나눠서, 큰 배치가 느려도 사용자 화면 쪽 CB 가 열리지 않게 */
    public String circuitBreaker() { return "ingest-" + audience; }
}
