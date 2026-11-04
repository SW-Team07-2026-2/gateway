package com.indie.gateway.domain.auth.dto.request;

import java.util.List;

/** Member POST /internal/auth/issue 요청 — agreements 는 카카오 싱크 동의 내역 (처음 로그인이면 가입 때 저장) */
public record IssueRequest(String provider, String providerUid, String email, String nickname, String profileImageUrl,
                           List<Agreement> agreements) {

    /** 카카오 GET /v2/user/service_terms 의 항목 한 건 — agreedAt 은 카카오 값 그대로 (ISO-8601) */
    public record Agreement(String tag, boolean agreed, String agreedAt) {}
}
