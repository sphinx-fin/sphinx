package com.sphinxfin.sphinx.domain;

/**
 * 측정값 자체가 계약을 벗어난 판정. 소유: 강희진
 *
 * <p>{@link EvidenceRequiredException}(P4, 근거 없음)과 <b>구별해서 던진다.</b> 둘 다 상류
 * 계약 위반이고 둘 다 502 지만, "왜 못 쓰는 판정인가"가 다르다 — 근거가 없는 것과 신뢰도가
 * 없는 것은 상류에서 고쳐야 할 자리가 다르다. 하나로 합치면 로그와 화면에서 그 구별이
 * 사라지고, 상류에 무엇을 고치라고 말할 수 없게 된다.
 *
 * <p>{@code confidence} 가 {@code double} 이던 시절에는 이 예외가 필요 없었다 — 필드가 빠지면
 * 0.0 이 되어 R-05 로 <b>보수적 황색</b>에 떨어졌다. 그것도 상류 위반을 감춘 것이라 좋지
 * 않았지만, {@code BigDecimal} 로 바꾼 뒤에는 같은 입력이 게이트 안쪽에서 NPE 로 터져
 * <b>500(서버 오류)</b> 이 된다 — 상류가 계약을 어긴 것이 우리가 깨진 것으로 보인다.
 * 그래서 타입 변경과 같은 PR 에서 막는다(결정 10.32 리뷰).
 */
public class MeasurementInvalidException extends RuntimeException {
    public MeasurementInvalidException(String message) {
        super(message);
    }
}
