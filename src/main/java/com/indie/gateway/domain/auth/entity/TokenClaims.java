package com.indie.gateway.domain.auth.entity;

import java.time.Instant;
import java.util.List;

/** 검증을 통과한 AT 의 클레임 — exchange attribute 로 필터 사이를 이동합니다 */
public record TokenClaims(String sub, List<String> roles, String jti, Instant exp) {

    public boolean hasRole(String role) {
        return roles != null && roles.contains(role);
    }
}
