package com.indie.gateway.domain.auth.dto.request;

/** Member POST /internal/auth/issue 요청 — 11/05 카카오 싱크 폐기로 약관 동의 내역은 싣지 않습니다 (가입 때 SignupRequest) */
public record IssueRequest(String provider, String providerUid, String email, String nickname, String profileImageUrl) {}
