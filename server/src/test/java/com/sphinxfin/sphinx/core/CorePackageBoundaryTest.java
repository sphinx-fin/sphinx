package com.sphinxfin.sphinx.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code core/} 의 패키지 경계를 지킨다. 소유: 강희진
 *
 * <h2>왜 문서만으로는 안 되는가</h2>
 *
 * <p>#140 이 core 를 성격별 하위 패키지로 가르면서 CLAUDE.md 에 <i>"루트에는
 * {@code EvidenceRecorder} 하나만 남는다"</i>고 적었다. 그런데 그 문장은 <b>머지 순간까지만
 * 참이다.</b> 같은 시기에 열려 있던 다른 브랜치가 루트에 클래스를 새로 만들면 두 변경은
 * <b>텍스트 충돌 없이 합쳐진다</b> — git 은 서로 다른 파일이라 조용하고, 문서만 거짓이 된다.
 *
 * <p>가정이 아니다. #142 가 실제로 그렇게 됐다(루트에 {@code UnfairSalesSignalEvent}·
 * {@code UnfairSalesTypes} 두 개). 사람이 리뷰에서 잡았으니 이번엔 막혔지만,
 * <b>사람이 잡아서 막힌 것은 다음에 또 난다.</b>
 *
 * <h2>두 사본을 같이 본다</h2>
 *
 * <p>{@code ErrorCodeContractTest} 와 같은 이유로 CLAUDE.md 의 하위 패키지 표까지 대조한다.
 * 패키지를 새로 만들고 표에 안 적으면 <b>표를 전부라고 믿는 사람이 생긴다</b>. 반대로 표에만
 * 있고 실물이 없으면 없는 자리를 찾게 된다.
 *
 * <p>디렉토리가 아니라 <b>컴파일 산출물</b>을 읽는다. 소스 경로를 상대경로로 박으면 테스트의
 * 작업 디렉토리에 묶이고, 무엇보다 {@code test} 태스크의 입력이 아니라서 파일만 옮겼을 때
 * Gradle 이 UP-TO-DATE 로 건너뛴다. 클래스 출력 디렉토리는 이미 테스트 classpath 다.
 */
@DisplayName("core 패키지 경계")
class CorePackageBoundaryTest {

    /** 루트에 남아도 되는 것. 늘리려면 왜 어느 하위 패키지에도 안 속하는지 근거가 있어야 한다. */
    private static final Set<String> ALLOWED_AT_ROOT = Set.of("EvidenceRecorder");

    @Test
    @DisplayName("❗루트에는 경계 인터페이스만 남는다 — 하위 패키지에 속하는 것은 거기 둔다")
    void rootHoldsBoundaryInterfacesOnly() throws Exception {
        assertThat(classesDirectlyInCore())
                .as("core 루트는 어느 하위 패키지에도 속하지 않는 경계 인터페이스 자리다(ADR-003). "
                        + "세션 판정에서 나는 사건이면 core/session, 게이트면 core/gate 로 간다. "
                        + "여기 늘었다면 CLAUDE.md 의 '루트에는 EvidenceRecorder 하나만 남는다'가 "
                        + "이미 거짓이다 — git 은 충돌을 안 내므로 이 단정이 유일한 경보다.")
                .containsExactlyInAnyOrderElementsOf(new TreeSet<>(ALLOWED_AT_ROOT));
    }

    @Test
    @DisplayName("❗CLAUDE.md 의 하위 패키지 표가 실물과 같다")
    void documentedSubpackagesMatchReality() throws Exception {
        Set<String> documented = new TreeSet<>();
        Matcher m = Pattern.compile("^\\| `core/([a-z]+)/` \\|", Pattern.MULTILINE)
                .matcher(Files.readString(Path.of("../CLAUDE.md")));
        while (m.find()) {
            documented.add(m.group(1));
        }

        assertThat(documented)
                .as("CLAUDE.md 의 하위 패키지 표를 하나도 못 읽었다 — 표 모양이 바뀌었으면 "
                        + "이 정규식도 같이 고친다. 안 그러면 양쪽이 다 비어서 조용히 통과한다.")
                .isNotEmpty();

        assertThat(documented)
                .as("패키지를 새로 만들었으면 CLAUDE.md 표에도 올린다. 표에만 있고 실물이 없으면 "
                        + "다음 사람이 없는 자리를 찾는다.")
                .isEqualTo(subpackagesOfCore());
    }

    /** {@code com.sphinxfin.sphinx.core} 에 <b>직접</b> 든 클래스 이름. 중첩 클래스는 바깥 이름으로 접는다. */
    private static Set<String> classesDirectlyInCore() throws URISyntaxException, IOException {
        try (Stream<Path> files = Files.list(coreOutputDir())) {
            return files.filter(p -> p.getFileName().toString().endsWith(".class"))
                    .map(p -> p.getFileName().toString().replaceFirst("\\.class$", ""))
                    .map(n -> n.contains("$") ? n.substring(0, n.indexOf('$')) : n)
                    .collect(TreeSet::new, Set::add, Set::addAll);
        }
    }

    private static Set<String> subpackagesOfCore() throws URISyntaxException, IOException {
        try (Stream<Path> entries = Files.list(coreOutputDir())) {
            return entries.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .collect(TreeSet::new, Set::add, Set::addAll);
        }
    }

    private static Path coreOutputDir() throws URISyntaxException {
        return Path.of(EvidenceRecorder.class.getResource("EvidenceRecorder.class").toURI()).getParent();
    }
}
