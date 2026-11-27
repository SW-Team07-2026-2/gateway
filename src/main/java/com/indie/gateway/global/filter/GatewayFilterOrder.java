package com.indie.gateway.global.filter;

/**
 * Gateway WebFilter의 실행 순서를 정의합니다.
 * 인증과 보안 검사가 Gateway 자체 API에도 적용되도록 WebFilter를 사용합니다.
 */
public final class GatewayFilterOrder {

    public static final int SECURITY_HEADER = -210;      // 응답 헤더 - 가장 먼저 beforeCommit 등록 (거절 응답에도 붙도록)
    public static final int REQUEST_SANITY = -200;       // 요청 형식 검사
    public static final int CORRELATION_ID = -190;       // 요청 추적 ID 전달
    public static final int CORS = -180;                 // 토큰과 CSRF 검사 전에 프리플라이트 응답
    public static final int TERMS_XSRF = -170;           // 허용된 약관 조회 응답에서 가입용 CSRF 토큰 전달
    public static final int CLIENT_HEADER_STRIP = -150;  // 토큰 검사 전에 클라이언트 식별 헤더 제거
    public static final int INGEST = -140;               // 식별 헤더 제거 후 서명 요청 검사와 내부 토큰 발급
    public static final int ACCESS_TOKEN = -100;         // 접근 토큰 검증
    public static final int PREEMPTIVE_REFRESH = -90;    // 만료 임박 토큰 갱신
    public static final int BLACKLIST = -80;             // 폐기된 토큰 검사
    public static final int CSRF = -70;                  // 상태 변경 요청의 CSRF 검사
    public static final int RATE_LIMIT = -60;            // 호출 한도 검사
    public static final int OAUTH2_LOGIN = -55;          // 호출 한도 검사 후 OAuth2 로그인 처리
    public static final int MEMBER_EXISTENCE = -52;      // 회원 삭제 확인 후에만 사용자 헤더 전달
    public static final int USER_HEADER_INJECT = -50;    // 검증된 회원 정보 전달
    public static final int WITHDRAWAL_COOKIE = -40;     // 탈퇴 성공 응답의 인증 쿠키 삭제

    private GatewayFilterOrder() {}
}
