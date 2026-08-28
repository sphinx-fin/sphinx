package com.sphinxfin.sphinx.evidence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 설문 문항이 전부 <b>선택지</b>인가 — 불변 기록에 원문을 싣는 근거다 (이슈 #169). 소유: 강희진
 *
 * <h2>왜 이걸 잠가야 하나</h2>
 *
 * <p>{@code appendMismatch} 가 {@code surveyResult} 를 <b>원문</b>으로 싣는다. 해시만 실으면
 * <i>"왜 모순인가"</i> 를 못 읽어서 그 기록의 목적을 못 채우기 때문이다.
 *
 * <p>그 판단이 서는 근거는 <b>전 문항이 선택지라 고객 자유서술이 없다</b>는 것 하나다. 그런데
 * 그건 <b>문항 구성의 사실이지 타입의 보장이 아니다</b> — {@code survey.ts} 주석도
 * {@code surveyResult} 를 <i>"freeform {@code Map<String,Object>}"</i> 라고 부른다.
 *
 * <p>그리고 {@code evidence/} 는 <b>append-only 라 한 번 들어간 것은 못 뺀다.</b> 문항 세트에
 * 자유서술이 하나 생기는 날 고객 원문이 불변 기록에 <b>영구히</b> 들어가는데, 그 변경은
 * {@code survey.ts} 에서 일어나므로 <b>이 코드 근처를 아무도 안 본다.</b> 그날 여기가 먼저
 * 빨개지게 해 둔다 (#186 리뷰).
 *
 * <p>P3 는 <i>고객 텍스트가 ai-service 로 나가는 경로</i>를 막는데, 이건 방향이 다르다 —
 * 나가는 것이 아니라 <b>영구히 쌓이는</b> 것이다.
 */
@DisplayName("설문 원문 적재의 전제 — 전 문항이 선택지다 (이슈 #169)")
class SurveyIsOptionOnlyTest {

    private static final Path SURVEY = Path.of("../web/src/lib/survey.ts");

    /** {@code id: "..."} 로 시작하는 문항 블록. */
    private static final Pattern QUESTION =
            Pattern.compile("\\{\\s*\\n\\s*id:\\s*\"([A-Z0-9-]+)\"(.*?)\\n\\s*\\},", Pattern.DOTALL);

    @Test
    @DisplayName("❗자유서술 문항이 생기면 여기가 먼저 깨진다 — 원문 적재를 다시 판단해야 한다")
    void everyQuestionIsOptionBased() throws Exception {
        String src = Files.readString(SURVEY);
        Matcher m = QUESTION.matcher(src);

        List<String> withoutOptions = new ArrayList<>();
        int seen = 0;
        while (m.find()) {
            seen++;
            if (!m.group(2).contains("options:")) {
                withoutOptions.add(m.group(1));
            }
        }

        assertThat(seen)
                .as("survey.ts 에서 문항을 하나도 못 읽었다 — 파일 모양이 바뀌었으면 이 정규식도 "
                        + "같이 고친다. 안 그러면 0건을 검사하고 조용히 통과한다")
                .isGreaterThan(0);

        assertThat(withoutOptions)
                .as("StoredEvidenceRecorder 가 surveyResult 를 원문으로 싣는 근거가 "
                        + "'전 문항이 선택지라 자유서술이 없다' 하나다. 자유서술 문항이 생기면 "
                        + "고객 원문이 append-only 기록에 영구히 들어간다 — 한 번 들어가면 "
                        + "못 뺀다. 그 판단을 여기서 다시 한다(이슈 #169 · #186 리뷰)")
                .isEmpty();
    }
}
