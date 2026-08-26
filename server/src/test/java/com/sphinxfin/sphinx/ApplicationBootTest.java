package com.sphinxfin.sphinx;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 애플리케이션이 실제 설정으로 뜨는지만 본다. 소유: 강희진
 *
 * 이게 없어서 서버가 기동조차 못 하는 상태를 아무도 못 잡았다 — sphinx.simulator.timeseries-dir
 * 을 Path 로 주입받았더니 Spring 리소스 변환기가 상대경로의 ".." 를 null 로 정규화해
 * 기동이 실패했는데, @DataJpaTest·@WebMvcTest·standaloneSetup 은 전체 컨텍스트를 안 띄우고
 * @SpringBootTest 들은 테스트 프로퍼티로 덮여 있어서 전부 초록이었다.
 *
 * 단위 테스트가 아무리 많아도 "뜨는가"는 따로 확인해야 한다. 기본 프로파일 + 실제
 * application.yml 로 컨텍스트를 로드하는 것이 이 테스트의 전부이고, 그거면 충분하다.
 */
@SpringBootTest
@DisplayName("애플리케이션 기동")
class ApplicationBootTest {

    @Test
    @DisplayName("기본 프로파일에서 컨텍스트가 뜬다")
    void contextLoads() {
        // 컨텍스트 로딩 실패 시 이 테스트가 실패한다. 단정문은 필요 없다.
    }
}
