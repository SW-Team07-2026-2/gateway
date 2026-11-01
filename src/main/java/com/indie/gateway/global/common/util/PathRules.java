package com.indie.gateway.global.common.util;

import org.springframework.http.HttpMethod;

import java.util.List;

/** 경로 분류 — 공개 경로 · CSRF 면제 · 인증이 걸리는 경로를 한 곳에서 정의합니다 */
public final class PathRules {

    /** 인증을 따지는 경로. 여기에 없는 경로는 토큰 검사 없이 라우팅 단계에서 404 가 됩니다 (/internal/** 등) */
    private static final List<String> KNOWN_PREFIXES = List.of(
            "/api/members", "/api/catalog", "/api/concerts", "/api/payments",
            "/api/chatbot", "/api/admin-ai", "/api/studio", "/api/stage-jobs", "/api/auth");

    public static boolean isKnown(String path) {
        return KNOWN_PREFIXES.stream().anyMatch(p -> path.equals(p) || path.startsWith(p + "/"));
    }

    /** 토큰 없이 통과 (§5-②) — 토큰이 있으면 검증해서 클레임을 붙이고, 깨진 토큰은 익명으로 취급 */
    public static boolean isPublic(HttpMethod method, String path) {
        if (HttpMethod.GET.equals(method) || HttpMethod.HEAD.equals(method)) {
            if (under(path, "/api/catalog") || under(path, "/api/concerts")
                    || under(path, "/actuator/health") || under(path, "/fallback")
                    || path.equals("/api/chatbot/artists/similar")) {   // 가입 밴드 고르기 — 아직 회원이 아님 (X4)
                return true;
            }
        }
        if (HttpMethod.POST.equals(method) && path.startsWith("/api/payments/webhook/")) return true;
        // TODO(결정대기): §18-1 무대 작업(GPU) 진입 경로 — 기본값대로 공개 라우트. Studio 가 GPU 전용 토큰을 검증해야 합니다
        if (under(path, "/api/stage-jobs")) return true;
        // 만료된 AT 로도 호출돼야 하는 Gateway 자체 엔드포인트 (선제 갱신 실패 시 폴백 · 로그아웃) · AT 가 아직 없는 가입 완료 (SIGNUP 쿠키)
        if (HttpMethod.POST.equals(method) && (path.equals("/api/auth/refresh") || path.equals("/api/auth/logout")
                || path.equals("/api/auth/signup"))) {
            return true;
        }
        return isOAuth(path);
    }

    public static boolean isOAuth(String path) {
        return path.startsWith("/oauth2/");   // 시작 /oauth2/authorization/* · 콜백 /oauth2/code/*
    }

    /** 수집 경로 (docs/22) — 쿠키 · AT 가 아니라 수집 서명으로 인증합니다 (IngestFilter) */
    public static boolean isIngest(String path) {
        return path.startsWith("/ingest/");
    }

    /** CSRF 면제 (§5-⑤) — 웹훅은 PG 서버라 쿠키가 없고, OAuth 는 state 가 대신합니다. 수집은 서명이 대신합니다 */
    public static boolean isCsrfExempt(String path) {
        return path.startsWith("/api/payments/webhook/") || isOAuth(path) || isIngest(path);
    }

    public static boolean isStateChanging(HttpMethod method) {
        return HttpMethod.POST.equals(method) || HttpMethod.PUT.equals(method)
                || HttpMethod.PATCH.equals(method) || HttpMethod.DELETE.equals(method);
    }

    private static boolean under(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }

    private PathRules() {}
}
