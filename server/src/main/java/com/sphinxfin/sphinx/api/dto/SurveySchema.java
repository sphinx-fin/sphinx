package com.sphinxfin.sphinx.api.dto;

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
 *   ① 낡은 번들이 죽은 버전을 보낸다 (캐시된 옛 dist/)   ← 이 클래스가 막는다
 *   ② 값이 비었는데 세션에는 설문이 있었다               ← ❗이 클래스는 안 막는다
 * </pre>
 *
 * <p>❗<b>②는 여기서 안 막는다.</b> {@link #isAcceptable} 이 빈 값을 통과시키기 때문이다 —
 * 설문 없는 세션이 계약상 허용되므로 «값이 없다» 만으로는 거부할 근거가 없고, «설문은
 * 있는데 버전이 없다» 를 잡으려면 <b>두 필드를 같이 보는 판단</b>이 필요하다. 그 크로스필드
 * 의미론은 별건이다(이슈 #546 ②) — 그 별건이 열려 있는 동안 교부 문서에 빈 칸이 나갈 수 있다.
 *
 * <p>그리고 그 빈 칸을 <b>결정 5.40 의 위반으로 읽지 않는다.</b> 5.40 이 금지하는 것은
 * <i>부재를 0 으로 적는 것</i>이고, ②에서 일어나는 일은 <i>존재하는 값이 부재로 기록되는
 * 것</i>이라 축이 다르다 — 빈 값이 «모른다»로 남는 것 자체는 오히려 5.40 을 따르는 모양이다.
 * 문제는 «모른다»가 아니라 «실제로는 세트가 있었다» 는 쪽이다.
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
}
