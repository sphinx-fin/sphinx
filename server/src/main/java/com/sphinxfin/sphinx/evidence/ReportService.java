package com.sphinxfin.sphinx.evidence;

/**
 * F-GTE-004 이해 기록 리포트. 소유: 정세현
 * ① 불변 JSON(해시 체인) ② PDF ③ 고객 교부용 요약. 원문 응답 보존 기간 정책 포함.
 *
 * 저장·해시는 {@link ImmutableStore}·{@link HashChain}·{@link CanonicalJson}에 위임한다.
 * 이 클래스가 직접 직렬화하거나 해시를 만들면 AuditLog와 갈라진다.
 * 스트림 이름: "report:{sessionId}"
 */
public class ReportService {
    // TODO(정세현)
}
