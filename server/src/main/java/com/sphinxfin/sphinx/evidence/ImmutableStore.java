package com.sphinxfin.sphinx.evidence;

/**
 * append-only 저장. 소유: 정세현
 * 리포트와 감사 로그가 공유하는 저장 계층 — UPDATE·DELETE 경로를 아예 만들지 않는다.
 *
 * MVP는 H2. 교체 시에도 이 인터페이스 밖으로 append 외의 연산이 새지 않게 유지한다.
 * 스트림 이름으로 체인을 분리한다 (예: "report:{sessionId}", "audit").
 *
 * <p><b>중복을 흡수하지 않는다</b>(ADR-004). 같은 내용이 두 번 들어오면 두 건으로 적재한다 —
 * 저장소가 "같은 판정"을 판단하면 감사 기록을 저장소가 편집하는 셈이고, {@code /judge}가 세 번
 * 불린 사실 자체가 감사 정보다.
 *
 * <p><b>적재 경로에 외부 I/O를 넣지 않는다</b>(ADR-004). append는 세션 저장과 같은 트랜잭션에
 * 묶이므로, 여기서 네트워크를 타면 그 트랜잭션이 그만큼 붙잡힌다.
 */
public interface ImmutableStore {

    /** 직전 항목의 hash를 읽어 이어 붙인다. 반환값은 새로 기록된 항목. */
    HashChain.ChainEntry append(String stream, Object payload);

    /** 검증·교부용 재생. 순서는 seq 오름차순. */
    Iterable<? extends HashChain.ChainEntry> replay(String stream);

    /** 체인의 현재 머리. 비어 있으면 {@link HashChain#GENESIS}. */
    String head(String stream);

    /**
     * 스트림 전체 검증. {@link HashChain#verify}에 <b>꼬리 절단 탐지</b>를 더한다.
     *
     * <p>체인만으로는 끝에서 잘라낸 것을 못 잡는다 — 남은 부분이 그 자체로 완전한 체인이기
     * 때문이다. 저장소는 스트림별 머리 hash와 항목 수를 따로 들고 있으므로 그 대조를 여기서 한다.
     * 기대값을 아는 쪽이 저장소라는 것이 {@link HashChain.Verification#checked()}를 돌려주는 이유다.
     */
    HashChain.Verification verify(String stream);
}
