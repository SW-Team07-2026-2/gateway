# Gateway — 로컬 프론트 로그인 복귀

운영 Gateway 를 이용하면서 로그인 결과를 로컬 프론트로 돌려보내려면 로그인 시작 URL 에 `frontend=local` 을 붙입니다. 이 변경이 Gateway 에 배포된 뒤 사용할 수 있습니다.

```ts
window.location.href = 'https://api.indig.co.kr/oauth2/authorization/kakao?frontend=local';
```

| 결과 | 복귀 주소 |
| --- | --- |
| 신규 사용자 | `http://localhost:5173/signup` |
| 기존 회원 | `http://localhost:5173/` |
| 취소 · 인증 실패 | `http://localhost:5173/login?error=oauth_failed` |
| 회원 서비스 처리 실패 | `http://localhost:5173/login?error=signup_failed` |

파라미터를 생략하면 기존 운영 프론트 설정을 사용합니다. `local` 이외의 값이나 임의 URL 은 목적지로 사용하지 않습니다. 로그인 시작 때의 선택은 OAuth 요청 쿠키에 보관하며, 콜백의 `state` 가 일치할 때만 복원합니다. 콜백 URL 에 `frontend=local` 을 붙여 목적지를 바꿀 수는 없습니다.

카카오에 등록된 콜백은 **`https://api.indig.co.kr/oauth2/code/kakao` 그대로**입니다. 프론트는 `/`, `/signup`, `/login` 화면을 준비합니다. 카카오 인증 후 신규 사용자는 아직 가입 전이므로 `/signup` 화면을 표시하고, 가입 완료 전 `/api/auth/me` 의 401 을 로그인 재시작으로 처리하지 않습니다.

운영 CORS 는 `https://indig.co.kr` 및 `http://localhost:5173` 을 허용합니다. 로컬 연동 중에는 모든 쿠키가 `.indig.co.kr` 의 `SameSite=None; Secure` 로 발급됩니다. 브라우저의 서드파티 쿠키 차단 여부에 따라 인증이 막힐 수 있습니다. 또한 localhost JavaScript 는 `.indig.co.kr` 의 XSRF 쿠키를 읽을 수 없으므로 가입을 포함한 상태 변경 요청의 CSRF 토큰 전달은 별도로 처리해야 합니다. 연동 완료 후 SameSite 정책을 되돌려야 합니다.

로그인 · 가입 전체 연동 순서는 [`프론트 연동 플로우`](../../docs/31_프론트_연동_플로우.md)를 참고하세요.
