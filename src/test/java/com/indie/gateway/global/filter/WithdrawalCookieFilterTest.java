package com.indie.gateway.global.filter;

import com.indie.gateway.TestProps;
import com.indie.gateway.domain.auth.service.CookieFactory;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.util.MultiValueMap;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** 회원 탈퇴 — Member 가 회원을 지우면 Gateway 가 인증 쿠키를 지웁니다 (member docs/v2_member_service_re.md §6.2) */
class WithdrawalCookieFilterTest {

    private final CookieFactory cookieFactory = new CookieFactory(TestProps.create(null));
    private final WithdrawalCookieFilter filter = new WithdrawalCookieFilter(cookieFactory);

    /** 하위(Member) 응답 상태를 정하고 응답을 내보냅니다 — 쿠키는 내보내는 시점(beforeCommit)에 붙습니다 */
    private MultiValueMap<String, ResponseCookie> respond(HttpMethod method, String path, HttpStatus downstream) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.method(method, path));
        filter.filter(exchange, e -> {
            e.getResponse().setStatusCode(downstream);
            return e.getResponse().setComplete();
        }).block(Duration.ofSeconds(5));
        return exchange.getResponse().getCookies();
    }

    @Test
    void successfulWithdrawalExpiresAuthCookies() {
        MultiValueMap<String, ResponseCookie> cookies = respond(HttpMethod.DELETE, "/api/members/me", HttpStatus.OK);

        assertThat(cookies.keySet()).containsExactlyInAnyOrder("AT", "RT", "XSRF-TOKEN");
        for (String name : cookies.keySet()) {
            ResponseCookie cookie = cookies.getFirst(name);
            assertThat(cookie.getValue()).as(name).isEmpty();
            assertThat(cookie.getMaxAge()).as(name).isEqualTo(Duration.ZERO);
            assertThat(cookie.getDomain()).as(name).isEqualTo(".example.com");   // 구울 때와 같은 Domain · Path 여야 지워집니다
            assertThat(cookie.getPath()).as(name).isEqualTo("/");
        }
    }

    /** 🔴 탈퇴가 안 됐으면(인증 실패 · Member 오류 · 폴백) 쿠키를 건드리지 않습니다 — 실패가 로그아웃이 되면 안 됩니다 */
    @Test
    void failedWithdrawalKeepsCookies() {
        for (HttpStatus status : new HttpStatus[]{HttpStatus.UNAUTHORIZED, HttpStatus.NOT_FOUND,
                HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.SERVICE_UNAVAILABLE}) {
            assertThat(respond(HttpMethod.DELETE, "/api/members/me", status)).as(status.toString()).isEmpty();
        }
    }

    /** 같은 요청에서 선제 갱신이 새 AT · RT 를 붙였어도 만료 쿠키만 나갑니다 (새 토큰이 남으면 탈퇴 후에도 로그인 상태) */
    @Test
    void refreshedCookiesAreReplacedNotAppended() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.delete("/api/members/me"));
        exchange.getResponse().addCookie(cookieFactory.at("refreshed-at"));
        exchange.getResponse().addCookie(cookieFactory.rt("refreshed-rt"));

        filter.filter(exchange, e -> {
            e.getResponse().setStatusCode(HttpStatus.OK);
            return e.getResponse().setComplete();
        }).block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getCookies().get("AT")).hasSize(1);
        assertThat(exchange.getResponse().getCookies().getFirst("AT").getValue()).isEmpty();
        assertThat(exchange.getResponse().getCookies().get("RT")).hasSize(1);
        assertThat(exchange.getResponse().getCookies().getFirst("RT").getValue()).isEmpty();
    }

    @Test
    void otherRequestsAreUntouched() {
        assertThat(respond(HttpMethod.GET, "/api/members/me", HttpStatus.OK)).isEmpty();
        assertThat(respond(HttpMethod.PATCH, "/api/members/me", HttpStatus.OK)).isEmpty();
        assertThat(respond(HttpMethod.DELETE, "/api/members/collections/7", HttpStatus.OK)).isEmpty();
        assertThat(respond(HttpMethod.DELETE, "/api/members/me/addresses/3", HttpStatus.OK)).isEmpty();
    }
}
