# Gateway — 로컬 · Vercel 프론트 로그인 복귀

운영 Gateway 를 이용하면서 로그인 결과를 로컬 프론트나 Vercel 기본 주소로 돌려보내려면 로그인 시작 URL 에 `frontend` 파라미터를 붙입니다.

```ts
window.location.href = 'https://api.indig.co.kr/oauth2/authorization/kakao?frontend=local';    // 로컬
window.location.href = 'https://api.indig.co.kr/oauth2/authorization/kakao?frontend=vercel';   // Vercel 기본 주소
```

| `frontend` | 복귀할 프론트 (`{프론트}`) |
| --- | --- |
| 없음 | 운영 설정값 `https://indig.co.kr` |
| `local` | `http://localhost:5173` |
| `vercel` | `https://sw-academy-workspace-front.vercel.app` (11/23 추가) |

| 결과 | 복귀 주소 |
| --- | --- |
| 신규 사용자 | `{프론트}/welcome` |
| 기존 회원 | `{프론트}/` |
| 취소 · 인증 실패 | `{프론트}/login?error=oauth_failed` |
| 회원 서비스 처리 실패 | `{프론트}/login?error=signup_failed` |

위 표 밖의 값이나 임의 URL 은 목적지로 사용하지 않습니다. OAuth 요청 쿠키에는 `local` · `vercel` 이름만 보관하고 주소는 코드의 고정 목록에서 고릅니다. 콜백의 `state` 가 일치할 때만 복원하며, 콜백 URL 에 `frontend` 를 붙여 목적지를 바꿀 수는 없습니다.

카카오에 등록된 콜백은 **`https://api.indig.co.kr/oauth2/code/kakao` 그대로**입니다. 프론트는 `/`, `/welcome`, `/login` 화면을 준비합니다. 카카오 인증 후 신규 사용자는 아직 가입 전이므로 `/welcome` 화면을 표시하고, 가입 완료 전 `/api/auth/me` 의 401 을 로그인 재시작으로 처리하지 않습니다.

운영 CORS 는 `https://indig.co.kr` · `http://localhost:5173` · `https://sw-academy-workspace-front.vercel.app` 을 허용합니다 (뒤 2개는 임시). Vercel 미리보기 주소(배포마다 바뀌는 주소)는 허용하지 않습니다. 로컬 연동 중에는 모든 쿠키가 `.indig.co.kr` 의 `SameSite=None; Secure` 로 발급됩니다. 브라우저의 서드파티 쿠키 차단 여부에 따라 인증이 막힐 수 있습니다. 연동 완료 후 SameSite 정책을 되돌려야 합니다.

가입 화면에서는 `GET /api/members/terms` 를 `credentials: 'include'` 로 호출하고, 성공 응답의 `X-XSRF-TOKEN` 헤더를 읽어 메모리에 보관합니다. Gateway 는 요청에 실린 `XSRF-TOKEN` 쿠키의 값을 이 헤더에 넣으며 CORS 에서 헤더 읽기를 허용합니다. 응답은 `Cache-Control: no-store` 로 캐시하지 않습니다. 쿠키가 없거나 약관 조회가 실패하면 토큰 헤더도 없으므로 가입 요청을 보내지 말고 로그인 및 브라우저 쿠키 차단 여부를 확인하세요. 토큰은 URL 에 넣지 않습니다.

`POST /api/auth/signup` 에는 `credentials: 'include'` 및 `X-XSRF-TOKEN` 요청 헤더와 함께 `{ artistIds, agreements }` 를 보냅니다. 기존 CSRF 검증은 그대로 적용되므로 쿠키와 헤더가 같아야 합니다. 새로고침하면 약관을 다시 조회해 토큰을 받습니다.

로그인 · 가입 전체 연동 순서는 [`프론트 연동 플로우`](../../docs/31_프론트_연동_플로우.md)를 참고하세요.
