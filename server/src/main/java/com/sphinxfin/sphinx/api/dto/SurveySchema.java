package com.sphinxfin.sphinx.api.dto;

import java.util.Map;
import java.util.Set;

/**
 * 적합성 설문 세트의 «살아 있는 버전». 소유: 강희진 (이슈 #546)
 *
 * <p>❗<b>서버가 살아 있는 세트를 알아야 한다.</b> {@code surveySchemaVersion} 은 불변 기록
 * (append-only)과 교부 문서에 실려 «어느 기준으로 물었나»에 답하는 값이다(F-GTE-004). 선택지
 * 문면이 곧 {@code recorded_answer} 라(세트가 다르면 같은 답이 다른 뜻) 이 값이 틀리면
 * 적합성 모순 판정의 근거가 어긋난다.
 *
 * <p>검증이 없으면 두 갈래로 새어 든다.
 *
 * <pre>
 *   ① 낡은 번들이 죽은 버전을 보낸다 (캐시된 옛 dist/)   ← {@link #isAcceptable}
 *   ② 값이 비었는데 세션에는 설문이 있었다               ← {@link #isVersionedWhenAnswered}
 * </pre>
 *
 * <h2>②를 어떻게 정했나 (이슈 #555)</h2>
 *
 * <p>«값이 없다» 만으로는 거부할 근거가 없다 — 설문 없는 세션이 계약상 허용된다. 그래서
 * <b>두 필드를 같이 보는 판단</b>이 필요했고 갈래가 셋이었다.
 *
 * <pre>
 *   ⓐ surveyResult 가 있으면 surveySchemaVersion 도 필수   ← 골랐다
 *   ⓑ 서버가 기본값을 채운다                               결정 5.40 위반 — 모르는 것을 아는 척한다
 *   ⓒ 그대로 두고 교부 문서에 「기준 미기록」으로 적는다      기록은 정직하지만 고객 지면에 빈 칸이 나간다
 * </pre>
 *
 * <p><b>ⓐ 인 이유는 되돌릴 수 없기 때문이다.</b> 이 값은 append-only 기록으로 내려가고 교부
 * 문서가 그 기록에서 조립되므로 <b>나중에 못 고친다</b> — {@code #546} 이 죽은 버전에 대해
 * 든 근거가 빈 버전에도 그대로 걸린다. 경계에서 막으면 400 하나로 끝나고, 통과시키면 그
 * 세션의 적합성 절이 영원히 «어느 기준으로 물었는지 모름» 이다.
 *
 * <p>❗<b>그리고 막는 비용이 0 이다.</b> 실제 클라이언트는 이미 둘을 같이 보낸다
 * ({@code web/src/pages/S02_SessionStart.tsx} 가 {@code surveySchemaVersion} 과
 * {@code surveyResult} 를 같은 본문에 싣는다). ⓐ 가 거부하는 것은 <b>지금 아무도 안 보내는
 * 조합</b>이고, 그게 오면 클라이언트가 깨진 것이다.
 *
 * <p>ⓒ 를 안 고른 이유: 기록은 정직해지지만 <b>고객이 받는 지면에 빈 칸이 나간다.</b> 그리고
 * 그 시점에는 채울 방법이 없다 — 막을 수 있었던 자리가 경계였다.
 *
 * <p>빈 값이 «모른다»로 남는 것 자체는 <b>결정 5.40 의 위반이 아니다</b> — 5.40 이 금지하는
 * 것은 <i>부재를 0 으로 적는 것</i>이라 오히려 그 결을 따른다. 문제는 «모른다»가 아니라
 * <b>«실제로는 세트가 있었다»</b> 는 쪽이고, ⓐ 는 그 상태를 안 만든다.
 *
 * <p><b>정본은 web {@code src/lib/survey.ts} 의 {@code SURVEY_SCHEMA_VERSION} 하나다.</b> 서버가
 * 두 벌을 갖는 셈이라 갈릴 수 있는데, {@code SurveySchemaVersionMirrorsWebTest} 가
 * {@code DemoModeAccountMapTest} 처럼 <b>서버 테스트가 web 파일을 읽어</b> 대조한다 — 세트를
 * 올릴 때 서버도 같이 올리지 않으면 빨개진다.
 */
public final class SurveySchema {

    /**
     * 지금 받는 세트 버전. 세트를 올리면 여기도 올린다.
     *
     * <p>❗<b>죽은 버전을 남겨 두지 않는다 — 그게 이 검증의 요점이다.</b> 그 단정을
     * {@code SurveySchemaVersionMirrorsWebTest} 가 {@code containsExactly} 로 강제한다.
     * 포함만 보면 집합에 값이 더 있어도 통과하고, 그러면 이 문장이 <b>지키지 못하는 것을
     * 약속</b>하게 된다(PR #550 리뷰가 v3 로 올리는 상황을 만들어 그 상태를 확인했다).
     */
    public static final Set<String> ALLOWED_VERSIONS = Set.of("s02-survey-v2");

    private SurveySchema() {}

    /**
     * 세트 버전이 받아들여지는가. <b>값이 없으면(null·공백) true</b> — 설문 없는 세션은
     * 계약상 허용된다(openapi 에서 {@code surveySchemaVersion} 은 required 가 아니다). 검증이
     * 잡는 것은 «있는데 죽은/모르는 버전»이다, «없는 것»이 아니다.
     */
    public static boolean isAcceptable(String version) {
        return version == null || version.isBlank() || ALLOWED_VERSIONS.contains(version);
    }

    /**
     * 설문 답이 있으면 세트 버전도 있는가 (이슈 #555 · 위 ⓐ).
     *
     * <p><b>답이 없으면 true</b> — 설문 없는 세션에는 버전이 없어도 된다. <b>빈 맵도 «없음»
     * 이다</b>: 답이 하나도 없는 세션에 버전을 요구하면 <i>없는 설문의 세트</i>를 적게 하는
     * 것이라 결정 5.40 과 부딪친다.
     *
     * <p>❗<b>{@link #isAcceptable} 과 겹치지 않는다.</b> 그쪽은 <i>있는 값이 살아 있는가</i>,
     * 이쪽은 <i>있어야 할 값이 있는가</i> 다. 한 메서드로 접으면 실패 문면이 «죽은 버전»과
     * «빠진 버전»을 같이 말하게 되는데 <b>고칠 자리가 다르다</b> — 앞은 번들을 새로 받는
     * 것이고 뒤는 클라이언트가 필드를 안 보낸 것이다.
     */
    public static boolean isVersionedWhenAnswered(String version, Map<String, Object> surveyResult) {
        if (surveyResult == null || surveyResult.isEmpty()) {
            return true;
        }
        return version != null && !version.isBlank();
    }
}
