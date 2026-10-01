package io.github.timelinear.algoreview.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 시각은 Clock 빈을 통해 읽는다. 테스트에서 고정 시각으로 바꿀 수 있게 하기 위함(일일 한도의 자정 경계 등).
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
