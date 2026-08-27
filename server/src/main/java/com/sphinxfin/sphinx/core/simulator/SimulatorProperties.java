package com.sphinxfin.sphinx.core.simulator;

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

    /**
     * 경로를 String 으로 받아 직접 변환한다.
     *
     * Path 로 바로 주입받으면 Spring 이 리소스 경로 변환기를 태우는데, 그 변환기가
     * 상대경로의 {@code ..} 를 루트 이탈로 보고 null 로 정규화한다 — 기본값
     * {@code ../data/timeseries} 에서 기동이 실패한다("has been normalized to [null]").
     * 여기서 필요한 것은 리소스 해석이 아니라 파일 경로라 Path.of 로 직접 만든다.
     */
    public SimulatorProperties(@Value("${sphinx.simulator.timeseries-dir}") String timeseriesDir) {
        // ❗빈 값은 설정 오류다 — 아래 경고로는 안 잡힌다. Spring 의 ${VAR:기본값} 은 환경변수가
        // **빈 문자열로 존재하면 그것을 값으로 취급**해 기본값이 죽는다. 그러면 Path.of("") 가
        // 작업 디렉토리가 되고, 작업 디렉토리는 실재하므로 isDirectory 가 참이라
        // "시계열 디렉토리: .../server" 를 INFO 로 남기고 지나간다 — 틀린 경로가 정상으로
        // 보고된다(실측). ".env.example 에 SPHINX_TIMESERIES_DIR= 을 적어두지 않는다"(#122)가
        // 이 함정을 피하는 규약인데, 규약만으로는 한 번 잘못 넣으면 조용하다.
        //
        // 디렉토리가 없는 것은 기동을 막지 않는다(아래 주석) — 그건 배포 상태의 문제다.
        // 값이 비어 있는 것은 설정 자체가 틀린 것이라 다르게 다룬다.
        if (timeseriesDir == null || timeseriesDir.isBlank()) {
            throw new IllegalStateException(
                    "sphinx.simulator.timeseries-dir 이 비어 있다. 환경변수 SPHINX_TIMESERIES_DIR 을 "
                    + "빈 값으로 두면 기본값이 적용되지 않는다 — 지우거나 실제 경로를 넣는다.");
        }
        this.timeseriesDir = Path.of(timeseriesDir);
    }

    public Path timeseriesDir() {
        return timeseriesDir;
    }

    /**
     * 기동 시 해석된 절대경로와 존재 여부를 남긴다.
     *
     * 디렉토리가 <b>없는 것</b>은 기동을 막지 않는다
     * — 아직 SimulatorService 를 부르는 코드가 없어서, 없다고 죽이면 무관한 기능까지
     * 못 뜬다. 대신 **없다는 사실이 로그에 보이게** 한다. 이슈 #37 이 지적한
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
