package com.indie.gateway.domain.auth.dto.response;

import java.util.List;

/** 처음 로그인이면 newUser=true · signupToken 만 (userId · 토큰 없음) */
public record IssueResponse(Long userId, String accessToken, String refreshToken,
                            List<String> roles, String nickname, boolean newUser, String signupToken) {}
