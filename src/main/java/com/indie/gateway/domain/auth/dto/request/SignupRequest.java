package com.indie.gateway.domain.auth.dto.request;

import java.util.List;

/** Member POST /internal/auth/signup 요청 — 사인업 토큰은 SIGNUP 쿠키에서 */
public record SignupRequest(String signupToken, List<Long> artistIds) {}
