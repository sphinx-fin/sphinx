package com.sphinxfin.sphinx.core.gate;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * GateEngine을 스프링 빈으로 등록한다. 소유: 강희진
 * GateEngine 자체에 스프링 애노테이션을 붙이지 않고 여기서 조립해, 룰 엔진을 프레임워크와
 * 분리된 순수 클래스로 유지한다(단위 테스트도 new GateEngine()으로 그대로).
 */
@Configuration
public class GateConfig {

    @Bean
    public GateEngine gateEngine() {
        return new GateEngine();   // 생성 시 classpath의 gate_rules.yaml 로드
    }
}
