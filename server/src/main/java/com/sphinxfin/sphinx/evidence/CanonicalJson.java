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
 *   - <b>컬렉션 순서를 누가 정하는가.</b> {@code Session.judgmentsByItem}(강희진, core/)은
 *     HashMap이므로 {@code Session.judgments()}가 내주는 순서는 삽입순도 itemId 정렬순도
 *     아니다(실측 확인: 같은 키 집합이면 실행 간 재현되지만 순서 자체는 명세되지 않는다).
 *     게이트 판정에는 영향이 없다 — GateEngine은 grade 집합 멤버십만 보고 ruleTrace도 룰
 *     ID뿐이다. 그러나 <b>리포트·감사 로그 해시는 호출측 순서에 기대면 안 된다.</b>
 *     여기서 itemId 정렬을 강제해야 한다. 안 그러면 같은 세션이 다른 해시를 낼 수 있고,
 *     이 클래스 주석 첫 문단이 경고하는 "감사 시점까지 안 보이는" 결함이 된다.
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
