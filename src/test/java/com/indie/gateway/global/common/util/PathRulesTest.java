package com.indie.gateway.global.common.util;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;

/** 11/01 — 어시스턴트 → 챗봇 경로 변경 · 관리자 AI 추가 (v49) */
class PathRulesTest {

    @Test
    void chatbotAndAdminAiAreProtected() {
        assertThat(PathRules.isKnown("/api/chatbot/chat")).isTrue();
        assertThat(PathRules.isKnown("/api/admin-ai/chat")).isTrue();
        assertThat(PathRules.isPublic(HttpMethod.POST, "/api/chatbot/chat")).isFalse();
        assertThat(PathRules.isPublic(HttpMethod.GET, "/api/admin-ai/tools")).isFalse();
    }

    /** 옛 경로와 /api/ai/** 는 인증 단계를 건너 라우팅에서 404 */
    @Test
    void oldAndInternalAiPathsAreUnknown() {
        assertThat(PathRules.isKnown("/api/assistant/chat")).isFalse();
        assertThat(PathRules.isKnown("/api/ai/documents")).isFalse();
    }
}
