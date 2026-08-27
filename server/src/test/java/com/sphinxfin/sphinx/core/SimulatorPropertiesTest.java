package com.sphinxfin.sphinx.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F-SIM-001 시계열 경로 설정. 소유: 강희진
 *
 * <p>여기서 가르는 것은 <b>"디렉토리가 없다" 와 "값이 비어 있다"</b>다. 앞엣것은 배포 상태의
 * 문제라 경고만 하고 뜬다(시뮬레이터를 부르는 코드가 아직 없어서, 없다고 죽이면 무관한
 * 기능까지 못 뜬다). 뒤엣것은 설정 자체가 틀린 것이라 기동을 막는다.
 */
@DisplayName("F-SIM-001 시계열 경로 설정")
class SimulatorPropertiesTest {

    @Test
    @DisplayName("❗빈 값은 기동을 막는다 — 안 막으면 작업 디렉토리가 정상 경로로 보고된다")
    void blankIsRejected() {
        // Spring 의 ${VAR:기본값} 은 환경변수가 빈 문자열로 존재하면 그것을 값으로 쓴다.
        // 그러면 Path.of("") 가 작업 디렉토리가 되는데, 그건 실재하므로 존재 검사를
        // 통과한다 — 틀린 경로가 INFO 로 "정상" 보고되고 아무도 모른다(실측).
        assertThatThrownBy(() -> new SimulatorProperties(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SPHINX_TIMESERIES_DIR");
        assertThatThrownBy(() -> new SimulatorProperties("   "))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("없는 디렉토리는 막지 않는다 — 배포 상태의 문제라 경고로 남긴다")
    void missingDirectoryStillBoots() {
        assertThatCode(() -> new SimulatorProperties("/no/such/timeseries"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("상대경로의 .. 를 그대로 보존한다 — Path 주입이면 null 로 정규화됐다 (#82)")
    void relativeParentPathSurvives() {
        assertThat(new SimulatorProperties("../data/timeseries").timeseriesDir())
                .isEqualTo(Path.of("../data/timeseries"));
    }
}
