package com.indie.gateway;

import com.indie.gateway.global.config.GatewayProperties;
import com.indie.gateway.global.filter.CorrelationIdFilter;
import io.micrometer.context.ContextRegistry;
import org.slf4j.MDC;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(GatewayProperties.class)
public class GatewayApplication {

    public static void main(String[] args) {
        // Reactor Context 의 correlationId 를 MDC 로 전파 (spring.reactor.context-propagation=auto 와 짝)
        ContextRegistry.getInstance().registerThreadLocalAccessor(
                CorrelationIdFilter.CONTEXT_KEY,
                () -> MDC.get(CorrelationIdFilter.CONTEXT_KEY),
                value -> MDC.put(CorrelationIdFilter.CONTEXT_KEY, value),
                () -> MDC.remove(CorrelationIdFilter.CONTEXT_KEY));
        SpringApplication.run(GatewayApplication.class, args);
    }
}
