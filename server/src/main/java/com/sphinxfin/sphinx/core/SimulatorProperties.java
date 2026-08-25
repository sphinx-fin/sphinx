package com.sphinxfin.sphinx.core;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * F-SIM-001 시계열 적재 경로. 소유: 강희진 (설정·주입) / 계산 엔진: 정세현
 *
 * {@code SimulatorService.loadSeries(Path, String)} 가 경로를 주입받게 설계돼 있어서
 * 계산 엔진은 배포 형태를 모른다. 그 경로를 어디서 가져올지가 여기다.
 *
 * CSV 를 클래스패스 리소스로 복사하지 않는 이유는 SimulatorService 주석에 있다 — 18,089줄을
 * 한 벌 더 두면 {@code data/timeseries/VERSION} 의 sha256 으로 고정한 원본과 조용히 갈라지고,
 * 출력이 달라진 원인이 코드인지 데이터인지 구분할 수 없게 된다(P2). 컨테이너에서도 이미지에
 * 굽지 않고 읽기 전용 볼륨으로 마운트한다(이슈 #37).
 */
@Slf4j
@Component
public class SimulatorProperties {

    private final Path timeseriesDir;

    public SimulatorProperties(@Value("${sphinx.simulator.timeseries-dir}") Path timeseriesDir) {
        this.timeseriesDir = timeseriesDir;
    }

    public Path timeseriesDir() {
        return timeseriesDir;
    }

    /**
     * 기동 시 해석된 절대경로와 존재 여부를 남긴다.
     *
     * 기동을 막지는 않는다 — 아직 SimulatorService 를 부르는 코드가 없어서, 없다고 죽이면
     * 무관한 기능까지 못 뜬다. 대신 **없다는 사실이 로그에 보이게** 한다. 이슈 #37 이 지적한
     * 실패 양식이 "컨테이너에 data/ 가 없는데 조용히 넘어가는 것"이라, 배선이 붙는 시점에
     * 이 경고를 기동 거부로 올린다.
     */
    @PostConstruct
    void reportResolvedPath() {
        Path abs = timeseriesDir.toAbsolutePath().normalize();
        if (Files.isDirectory(abs)) {
            log.info("F-SIM-001 시계열 디렉토리: {}", abs);
        } else {
            log.warn("F-SIM-001 시계열 디렉토리가 없다: {} — 시뮬레이터 계산이 불가능하다. "
                    + "로컬은 python3 scripts/fetch_timeseries.py, 컨테이너는 읽기 전용 볼륨 마운트 확인.", abs);
        }
    }
}
