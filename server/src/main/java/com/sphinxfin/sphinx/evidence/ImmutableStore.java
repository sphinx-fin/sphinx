package com.sphinxfin.sphinx.evidence;

/**
 * append-only 저장. 소유: 정세현
 * 리포트와 감사 로그가 공유하는 저장 계층 — UPDATE·DELETE 경로를 아예 만들지 않는다.
 *
 * MVP는 H2. 교체 시에도 이 인터페이스 밖으로 append 외의 연산이 새지 않게 유지한다.
 * 스트림 이름으로 체인을 분리한다 (예: "report:{sessionId}", "audit").
 */
public interface ImmutableStore {

    /** 직전 항목의 hash를 읽어 이어 붙인다. 반환값은 새로 기록된 항목. */
    HashChain.ChainEntry append(String stream, Object payload);

    /** 검증·교부용 재생. 순서는 seq 오름차순. */
    Iterable<? extends HashChain.ChainEntry> replay(String stream);

    /** 체인의 현재 머리. 비어 있으면 {@link HashChain#GENESIS}. */
    String head(String stream);
}
