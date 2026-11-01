package com.indie.gateway.domain.auth.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** GET /api/auth/me 응답 (§7-3) — stale 은 Member 장애로 클레임만 돌려줄 때만 붙습니다 */
public record MeResponse(String userId, String nickname, String profileImageUrl, List<String> roles,
                         String xsrfToken, @JsonInclude(JsonInclude.Include.NON_NULL) Boolean stale) {}
