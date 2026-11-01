package com.indie.gateway.global.config;

import io.netty.channel.ChannelOption;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Clock;
import java.time.Duration;

@Configuration
public class WebClientConfig {

    /** Member 내부 호출용 (§8-4) — connect 1s · read 2s · 재시도 없음 */
    @Bean
    public WebClient memberWebClient(WebClient.Builder builder, GatewayProperties props) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 1000)
                .responseTimeout(Duration.ofSeconds(2));
        return builder.clone()
                .baseUrl(props.memberBaseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    /** 카카오 API (로그인 콜백의 약관 동의 내역 조회) — connect 1s · read 2s · 재시도 없음 */
    @Bean
    public WebClient kakaoWebClient(WebClient.Builder builder) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 1000)
                .responseTimeout(Duration.ofSeconds(2));
        return builder.clone()
                .baseUrl("https://kapi.kakao.com")
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
