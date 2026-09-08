package com.sphinxfin.sphinx.api;

import com.sphinxfin.sphinx.api.dto.SurveySchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 서버 허용 세트 ≡ web {@code survey.ts} 정본 (이슈 #546). 소유: 강희진
 *
 * <h2>왜 서버 테스트가 web 파일을 읽나</h2>
 *
 * <p>{@code surveySchemaVersion} 정본은 {@code web/src/lib/survey.ts} 하나이고, 서버는
 * {@link SurveySchema#ALLOWED_VERSIONS} 로 그 지식을 <b>옮겨 적는다</b>. 두 벌이라 갈릴 수
 * 있는데, {@code DemoModeAccountMapTest} 가 개방 모드 지도(web 파일)를 읽어 대조하는 것과
 * 같은 방식으로 여기서 잡는다 — <b>세트를 올릴 때 서버 허용 집합도 같이 올리지 않으면
 * 빨개진다.</b> 안 그러면 배포된 화면이 보내는 값을 서버가 거부하거나, 죽은 값이 통과한다.
 */
@DisplayName("설문 세트 버전: 서버 허용 집합 ≡ web survey.ts (이슈 #546)")
class SurveySchemaVersionMirrorsWebTest {

    private static final Path SURVEY_TS = Path.of("../web/src/lib/survey.ts");
    /** {@code export const SURVEY_SCHEMA_VERSION = "s02-survey-v2";} 에서 값만. */
    private static final Pattern VERSION =
            Pattern.compile("SURVEY_SCHEMA_VERSION\\s*=\\s*\"([^\"]+)\"");

    @Test
    @DisplayName("❗web 이 보내는 살아 있는 세트를 서버가 받아들인다")
    void serverAcceptsTheVersionWebSends() throws Exception {
        String ts = Files.readString(SURVEY_TS);
        Matcher m = VERSION.matcher(ts);
        assertThat(m.find())
                .as("survey.ts 에서 SURVEY_SCHEMA_VERSION 을 못 읽었다 — 문면이 바뀌었으면 이 "
                        + "정규식도 같이 고친다. 안 그러면 0건을 검사하고 조용히 통과한다")
                .isTrue();
        String webVersion = m.group(1);

        assertThat(SurveySchema.isAcceptable(webVersion))
                .as("web 이 보내는 세트(" + webVersion + ")를 서버가 거부한다 — 세트를 올렸으면 "
                        + "SurveySchema.ALLOWED_VERSIONS 도 같이 올린다. 안 그러면 배포된 화면의 "
                        + "세션 생성이 전부 400 이다")
                .isTrue();

        // 포함만 보면 집합에 뭐가 더 있어도 통과한다 — 그래서 죽은 값을 남겨 두는
        // 상태가 초록이었다(PR #550 리뷰가 v3 로 올리는 상황을 만들어 확인했다).
        // 세트 교체가 이 검증이 상정한 바로 그 순간이므로, 그때 서버 쪽 한 줄을 지우는
        // 것을 잊으면 아무도 안 물어 준다. 그 자리를 이 단정이 막는다.
        assertThat(SurveySchema.ALLOWED_VERSIONS)
                .as("허용 집합에 살아 있는 세트 말고 다른 값이 있다 — 죽은 값을 남겨 두면 "
                        + "낡은 번들이 그 값으로 불변 기록을 남길 수 있다. 세트를 올렸으면 "
                        + "옛 값을 지운다. 배포 전환 창 때문에 둘을 받아야 한다면 그 이유와 "
                        + "언제 지울지를 SurveySchema 주석에 적고 이 단정을 고친다")
                .containsExactly(webVersion);
    }

    @Test
    @DisplayName("❗죽은 세트는 거부하고, 없는 값은 통과한다(설문 없는 세션)")
    void deadVersionRejectedButAbsentAllowed() {
        assertThat(SurveySchema.isAcceptable("s02-survey-v1"))
                .as("죽은 세트를 통과시키면 낡은 번들이 틀린 근거를 불변 기록에 남긴다").isFalse();
        assertThat(SurveySchema.isAcceptable(null)).isTrue();   // 설문 없는 세션
        assertThat(SurveySchema.isAcceptable("")).isTrue();     // 검증이 잡는 건 «있는데 틀린» 것
    }
}
