package com.indie.gateway.global.common.util;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;

/** 11/01 — 어시스턴트 → 챗봇 경로 변경 (v49) · ✏️ 11/04 관리자 경로는 KT 로 (56B · 57A) */
class PathRulesTest {

    @Test
    void chatbotIsProtected() {
        assertThat(PathRules.isKnown("/api/chatbot/chat")).isTrue();
        assertThat(PathRules.isPublic(HttpMethod.POST, "/api/chatbot/chat")).isFalse();
    }

    /** 옛 경로와 /api/ai/** 는 인증 단계를 건너 라우팅에서 404 */
    @Test
    void oldAndInternalAiPathsAreUnknown() {
        assertThat(PathRules.isKnown("/api/assistant/chat")).isFalse();
        assertThat(PathRules.isKnown("/api/ai/documents")).isFalse();
    }

    /** 관리자 · GPU 경로는 Gateway 에 없음 — 토큰이 있어도 404 (공개 라우트였던 stage-jobs 포함) */
    @Test
    void adminPathsAreUnknown() {
        assertThat(PathRules.isKnown("/api/studio/venues")).isFalse();
        assertThat(PathRules.isKnown("/api/admin-ai/chat")).isFalse();
        assertThat(PathRules.isKnown("/api/stage-jobs/next")).isFalse();
        assertThat(PathRules.isPublic(HttpMethod.GET, "/api/stage-jobs/next")).isFalse();
    }
}
