package com.indie.gateway.domain.auth.dto.response;

public record RefreshResponse(Long userId, String accessToken, String refreshToken) {}
