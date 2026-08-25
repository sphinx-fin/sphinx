package com.sphinxfin.sphinx.evidence;

/**
 * 정규화 직렬화. 소유: 정세현
 * ReportService(F-GTE-004)와 AuditLog(F-CMN-002)의 공통 기반 — 두 곳의 해시가 교차 검증
 * 가능해야 하므로 정규화는 이 클래스 하나만 쓴다. 여기서 갈라지면 감사 시점까지 안 보인다.
 *
 * 논리적으로 동일한 내용 → 항상 동일한 바이트. 규약은 <b>ADR-008</b>에 있다
 * (docs/adr/008-canonical-json.md). 요지만 옮기면:
 *
 * <ul>
 *   <li><b>RFC 8785(JCS) 그대로</b>가 기본이다 — 키 정렬은 UTF-16 코드유닛 순서
 *       ({@code String.compareTo()}가 그 순서다), 최소 이스케이프, 숫자는 최단 왕복 표기.
 *       외부 감사자가 우리 코드 없이 재계산할 수 있어야 contentHash가 증거가 된다.</li>
 *   <li><b>해시 대상에 double/float를 담지 않는다.</b> 금액은 원 단위 long, 비율은 BigDecimal.
 *       부동소수는 계산 경로가 조금 달라지면 마지막 자리가 흔들리고, 그 순간 같은 판정이 다른
 *       해시를 낸다 — 체인이면 그 뒤 전체가 검증 실패다. NaN/Infinity는 직렬화 거부.</li>
 *   <li><b>NFC 정규화를 여기서 하지 않는다.</b> 직렬화가 내용을 바꾸면 저장된 utteranceQuote와
 *       해시 대상이 갈리고, verify_quote_is_verbatim이 대조하는 문자열이 어느 쪽인지 모호해진다
 *       (화면이 PII를 미리 마스킹하면 안 되는 것과 같은 구조). 정규화는 입력 경계에서 한다.</li>
 *   <li><b>타임스탬프는 UTC + 밀리초 3자리 고정</b>({@code 2026-08-25T09:34:16.000Z}).
 *       Instant.now()의 정밀도가 플랫폼마다 달라서, 자릿수만 다른 같은 시각이 다른 해시를
 *       내는 것을 막는다. 적재 순서는 타임스탬프가 아니라 prev_hash가 정하므로 밀리초로 충분하다.</li>
 *   <li><b>null 필드를 생략하지 않는다.</b> 생략하면 "값이 null"과 "필드가 없음"이 같은 바이트가
 *       된다. 히트맵의 {@code misrate: null}은 소표본 마스킹이 동작했다는 증거인데, 생략하면
 *       그 증거가 해시에서 사라진다.</li>
 *   <li><b>컬렉션 순서는 itemId 정렬을 강제한다</b>(ADR-004). Session.judgmentsByItem이 HashMap이라
 *       Session.judgments()의 순서는 명세되지 않는다 — 게이트 판정에는 영향이 없지만
 *       (GateEngine은 grade 집합 멤버십만 본다) 리포트·감사 로그 해시는 호출측 순서에 기대면
 *       안 된다. 안 그러면 같은 세션이 다른 해시를 낼 수 있고, 위 첫 문단이 경고하는
 *       "감사 시점까지 안 보이는" 결함이 된다.</li>
 * </ul>
 *
 * 규약을 여기와 ADR 두 곳에 적으면 갈린다. 바뀌면 새 ADR을 추가하고 이 주석은 참조만 고친다.
 */
public final class CanonicalJson {

    /** 정규화된 JSON 문자열. 같은 입력은 언제나 같은 출력이어야 한다. */
    public static String serialize(Object value) {
        // TODO(정세현): ADR-008 대로 구현 (RFC 8785). 8/27~8/29 evidence 공통 기반, 이슈 #54.
        throw new UnsupportedOperationException("not implemented");
    }

    /** 해시 입력용 바이트. serialize()의 UTF-8 인코딩 — 인코딩도 고정 지점이다. */
    public static byte[] bytes(Object value) {
        // TODO(정세현)
        throw new UnsupportedOperationException("not implemented");
    }

    private CanonicalJson() {}
}
