package com.indie.gateway.domain.auth.dto.response;

import java.util.List;

/** Member GET /internal/members/{id}/profile 응답 — /api/auth/me 의 닉네임 · 프로필 출처 */
public record ProfileResponse(Long userId, String nickname, String profileImageUrl, List<String> roles, String status) {}
