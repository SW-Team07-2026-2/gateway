package com.indie.gateway.global.filter;

/**
 * 필터 순서 — ★ 숫자가 곧 보안 설계입니다 (§5). 여기 말고 다른 곳에 숫자를 쓰지 마세요.
 *
 * <p>GlobalFilter 는 라우팅되는 요청에만 실행되어 Gateway 자체 엔드포인트(/api/auth/**, OAuth 콜백)를
 * 건너뜁니다. 그래서 전부 WebFilter 로 구현하고 순서 번호는 명세 그대로 둡니다.
 */
public final class GatewayFilterOrder {

    public static final int SECURITY_HEADER = -210;      // 응답 헤더 — 가장 먼저 beforeCommit 등록 (거절 응답에도 붙도록)
    public static final int REQUEST_SANITY = -200;       // ⓪
    public static final int CORRELATION_ID = -190;       // ⓪'
    public static final int CORS = -180;                 // ⓪'' 프리플라이트는 ② 토큰 · ⑤ CSRF 검사 전에 응답
    public static final int TERMS_XSRF = -170;           // 허용된 약관 조회 응답에서 가입용 CSRF 토큰 전달
    public static final int CLIENT_HEADER_STRIP = -150;  // ① 🔴 반드시 ② 보다 먼저
    public static final int INGEST = -140;               // 수집 /ingest/** (docs/22) — ① 뒤라야 이 필터가 붙인 X-Internal-Token 이 지워지지 않음
    public static final int ACCESS_TOKEN = -100;         // ②
    public static final int PREEMPTIVE_REFRESH = -90;    // ③
    public static final int BLACKLIST = -80;             // ④
    public static final int CSRF = -70;                  // ⑤
    public static final int RATE_LIMIT = -60;            // ⑥
    public static final int OAUTH2_LOGIN = -55;          // Spring Security OAuth2 (⑥ 뒤에서 실행돼야 IP 한도가 걸림)
    public static final int USER_HEADER_INJECT = -50;    // ⑦
    public static final int WITHDRAWAL_COOKIE = -40;     // 탈퇴 성공 응답에서 인증 쿠키 삭제 — ②~⑦ 을 통과한 요청에만 걸리도록 맨 뒤

    private GatewayFilterOrder() {}
}
