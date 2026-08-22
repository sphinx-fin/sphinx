package com.sphinxfin.sphinx.evidence;

/**
 * F-CMN-002 접근 감사 로그. 소유: 정세현
 *
 * F-CMN-002는 두 덩어리다 — 접근 통제(RBAC)와 감사 로그. 감사 로그가 이쪽,
 * RBAC는 {@code security/}. 경계는 security/AccessPolicy 주석 참고.
 *
 * 기록 주체는 컨트롤러가 아니라 security/AuditInterceptor다. 컨트롤러마다 record()를
 * 부르면 감사 관심사가 api/(강희진)에 흩어져 소유권이 다시 겹친다.
 *
 * ReportService와 동일한 해시 체인·정규화를 쓴다. 스트림 이름: "audit"
 */
public class AuditLog {

    /** 누가·언제·무엇에 접근했는가. 개인 식별자는 담지 않는다 (P3). */
    public record Entry(String actorId, String role, String action,
                        String resource, String resultCode, String occurredAt) {}

    // TODO(정세현): ImmutableStore.append("audit", entry)
}
