package com.sphinxfin.sphinx.evidence;

/**
 * 해시 체인. 소유: 정세현
 * 리포트(F-GTE-004)와 감사 로그(F-CMN-002)가 같은 체인 규칙을 쓴다.
 *
 * 경계를 명시해야 하는 것: 무엇이 해시 대상에 들어가고 무엇이 빠지는가.
 * prevHash는 대상에 들어가고(그래서 체인이 된다), 자기 자신의 hash 필드는 빠진다.
 * 저장 시각·시퀀스는 들어가야 재배열을 탐지할 수 있다.
 */
public final class HashChain {

    /** 체인의 첫 항목이 참조하는 값. 제네시스를 상수로 고정해야 검증이 시작점을 안다. */
    public static final String GENESIS = "0".repeat(64);

    /** prevHash + 정규화된 payload → 이 항목의 hash (SHA-256 hex). */
    public static String link(String prevHash, Object payload) {
        // TODO(정세현): CanonicalJson.bytes(payload) 앞에 prevHash를 결합해 SHA-256
        throw new UnsupportedOperationException("not implemented");
    }

    /** 체인 전체 재계산 검증. 끊긴 지점의 인덱스를 돌려주는 편이 감사에 쓸모 있다. */
    public static boolean verify(Iterable<? extends ChainEntry> entries) {
        // TODO(정세현)
        throw new UnsupportedOperationException("not implemented");
    }

    /** 체인에 들어가는 항목이 최소로 만족해야 하는 형태. */
    public interface ChainEntry {
        long seq();
        String prevHash();
        String hash();
        Object payload();
    }

    private HashChain() {}
}
