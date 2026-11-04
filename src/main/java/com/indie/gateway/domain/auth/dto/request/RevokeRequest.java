package com.indie.gateway.domain.auth.dto.request;

/**
 * Member POST /internal/auth/revoke 요청.
 * refreshToken 은 명세(§7-3)에 없는 필드입니다 — allDevices=false 일 때 "해당 RT 1건"을 지정할 방법이 없어 추가했습니다.
 */
public record RevokeRequest(Long userId, String jti, Long accessTokenExp, boolean allDevices, String refreshToken) {}
