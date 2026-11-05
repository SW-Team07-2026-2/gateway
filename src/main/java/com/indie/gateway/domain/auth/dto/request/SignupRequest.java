package com.indie.gateway.domain.auth.dto.request;

import java.util.List;

/** Member POST /internal/auth/signup 요청 — 사인업 토큰은 SIGNUP 쿠키에서 · 11/05 agreements 는 G13 본문 그대로 (검사는 Member) */
public record SignupRequest(String signupToken, List<Long> artistIds, List<Agreement> agreements) {

    /** 가입 화면의 약관 체크 한 줄 — termsId 는 M24 GET /api/members/terms 값 */
    public record Agreement(Long termsId, boolean agreed) {}
}
