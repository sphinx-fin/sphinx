package com.sphinxfin.sphinx.api.dto;

import java.util.Set;

/**
 * 적합성 설문 세트의 «살아 있는 버전». 소유: 강희진 (이슈 #546)
 *
 * <p>❗<b>서버가 살아 있는 세트를 알아야 한다.</b> {@code surveySchemaVersion} 은 불변 기록
 * (append-only)과 교부 문서에 실려 «어느 기준으로 물었나»에 답하는 값이다(F-GTE-004). 검증이
 * 없으면 두 갈래로 새어 든다 — <b>낡은 번들</b>이 죽은 버전(캐시된 옛 {@code dist/})을 보내고
 * 서버가 그대로 기록하거나, 빈 값이 «모른다»로 기록된다(결정 5.40 의 정반대). 선택지 문면이
 * 곧 {@code recorded_answer} 라(세트가 다르면 같은 답이 다른 뜻) 이 값이 틀리면 적합성 모순
 * 판정의 근거가 어긋난다.
 *
 * <p><b>정본은 web {@code src/lib/survey.ts} 의 {@code SURVEY_SCHEMA_VERSION} 하나다.</b> 서버가
 * 두 벌을 갖는 셈이라 갈릴 수 있는데, {@code SurveySchemaVersionMirrorsWebTest} 가
 * {@code DemoModeAccountMapTest} 처럼 <b>서버 테스트가 web 파일을 읽어</b> 대조한다 — 세트를
 * 올릴 때 서버도 같이 올리지 않으면 빨개진다.
 */
public final class SurveySchema {

    /**
     * 지금 받는 세트 버전. 세트를 올리면 여기도 올린다(그리고 미러 테스트가 그걸 강제한다).
     * 죽은 버전을 남겨 두지 않는다 — 그게 이 검증의 요점이다.
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
