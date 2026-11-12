package com.indie.gateway.global.common.util;

import org.springframework.http.HttpMethod;

import java.util.List;

/** 경로 분류 — 공개 경로 · CSRF 면제 · 인증이 걸리는 경로를 한 곳에서 정의합니다 */
public final class PathRules {

    /**
     * 인증을 따지는 경로. 여기에 없는 경로는 토큰 검사 없이 라우팅 단계에서 404 가 됩니다 (/internal/** 등)
     * ✏️ 11/04 56B · 57A — 관리자 경로(/api/studio · /api/admin-ai · /api/stage-jobs)는 KT 로 빠져 여기 없습니다
     */
    private static final List<String> KNOWN_PREFIXES = List.of(
            "/api/members", "/api/catalog", "/api/cart", "/api/concerts", "/api/payments",
            "/api/chatbot", "/api/auth");

    public static boolean isKnown(String path) {
        return KNOWN_PREFIXES.stream().anyMatch(p -> path.equals(p) || path.startsWith(p + "/"));
    }

    /** 토큰 없이 통과 (§5-②) — 토큰이 있으면 검증해서 클레임을 붙이고, 깨진 토큰은 익명으로 취급 */
    public static boolean isPublic(HttpMethod method, String path) {
        if (HttpMethod.GET.equals(method) || HttpMethod.HEAD.equals(method)) {
            if (under(path, "/api/catalog") || under(path, "/api/concerts")
                    || under(path, "/actuator/health") || under(path, "/fallback")
                    || path.equals("/api/chatbot/artists/similar")     // 가입 밴드 고르기 — 아직 회원이 아님 (X4)
                    || path.equals("/api/auth/signup")                 // 11/08 가입 화면 진입 확인 (SIGNUP 쿠키)
                    || under(path, "/api/members/terms")) {            // 11/05 가입 약관 동의 (M24) · 11/06 푸터 약관 화면 (M25 /{code})
                return true;
            }
        }
        if (HttpMethod.POST.equals(method) && path.startsWith("/api/payments/webhook/")) return true;
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
