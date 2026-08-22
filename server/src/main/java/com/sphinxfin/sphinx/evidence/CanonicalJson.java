package com.sphinxfin.sphinx.evidence;

/**
 * 정규화 직렬화. 소유: 정세현
 * ReportService(F-GTE-004)와 AuditLog(F-CMN-002)의 공통 기반 — 두 곳의 해시가 교차 검증
 * 가능해야 하므로 정규화는 이 클래스 하나만 쓴다. 여기서 갈라지면 감사 시점까지 안 보인다.
 *
 * 논리적으로 동일한 내용 → 항상 동일한 바이트. 결정해야 할 것:
 *   - 객체 키 정렬 기준 (UTF-16 코드유닛 vs 코드포인트 — RFC 8785는 코드유닛)
 *   - 숫자 표기 (정수/실수 구분, 지수 표기 금지, -0 처리)
 *   - 문자열 이스케이프 최소화 + 유니코드 정규화(NFC) 적용 여부
 *   - 타임스탬프 정밀도 고정 (밀리초? 마이크로초?) 및 UTC 강제
 *   - null 필드를 생략하는가 유지하는가
 */
public final class CanonicalJson {

    /** 정규화된 JSON 문자열. 같은 입력은 언제나 같은 출력이어야 한다. */
    public static String serialize(Object value) {
        // TODO(정세현): RFC 8785(JCS) 기준으로 구현. 위 결정사항을 docs/에 함께 기록한다.
        throw new UnsupportedOperationException("not implemented");
    }

    /** 해시 입력용 바이트. serialize()의 UTF-8 인코딩 — 인코딩도 고정 지점이다. */
    public static byte[] bytes(Object value) {
        // TODO(정세현)
        throw new UnsupportedOperationException("not implemented");
    }

    private CanonicalJson() {}
}
