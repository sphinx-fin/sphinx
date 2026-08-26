package com.sphinxfin.sphinx.domain;

/**
 * F-DET-002 적합성 모순 판정 상태. 소유: 강희진
 *
 * 불리언 하나로는 부족하다. {@code contracts/suitability_mismatch.schema.json} 이 스스로
 * 경고한 지점이다 —
 *
 * <blockquote>
 * status=insufficient_input 이면 mismatch 는 항상 false 이며, <b>그 false 를 적합으로 읽으면
 * 안 된다</b>
 * </blockquote>
 *
 * 세션이 {@code boolean suitabilityMismatch} 하나만 들면 <b>"모순 없음" 과 "판정하지 못함" 이
 * 같은 값</b>이 되고, 게이트가 그걸 GREEN 으로 흘린다. 판정하지 못한 것을 통과로 읽는 것은
 * 실패를 은폐하는 것이다(E-EXT-03 과 같은 원칙).
 *
 * 그래서 세 상태로 나눈다. 아직 물어보지 않은 것(NOT_EVALUATED)도 판정 못 한 것과 구별한다 —
 * 전자는 흐름이 거기까지 안 간 것이고 후자는 시도했는데 답이 안 나온 것이다.
 */
public enum SuitabilityStatus {

    /** 아직 모순 판정을 돌리지 않았다. 세션 생성 직후의 상태. */
    NOT_EVALUATED,

    /** 판정했고 모순이 없다. 이것만이 "적합" 이다. */
    NO_MISMATCH,

    /** 판정했고 모순이 있다 → 게이트 R-02 로 RED. */
    MISMATCH,

    /**
     * 입력이 부족해 판정하지 못했다 → 게이트 R-02b 로 YELLOW (결정 10.9).
     *
     * RED 가 아닌 이유: 모순이 확인된 게 아니라 확인하지 못한 것이다. 판매 보류가 아니라
     * 재확인이 비례하고, 황색이면 F-INT-004 루프가 설문을 다시 받는다.
     * GREEN 이 아닌 이유: 확인하지 못한 것을 통과로 읽으면 안 된다.
     */
    UNKNOWN;

    /** 게이트 입력 — 모순이 확인됐는가(R-02). */
    public boolean isMismatch() {
        return this == MISMATCH;
    }

    /** 게이트 입력 — 판정을 시도했으나 확인하지 못했는가(R-02b). */
    public boolean isUnknown() {
        return this == UNKNOWN;
    }
}
