package com.sphinxfin.sphinx.evidence;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 해시 체인. 소유: 정세현
 * 리포트(F-GTE-004)와 감사 로그(F-CMN-002)가 같은 체인 규칙을 쓴다.
 *
 * 경계를 명시해야 하는 것: 무엇이 해시 대상에 들어가고 무엇이 빠지는가.
 * prevHash는 대상에 들어가고(그래서 체인이 된다), 자기 자신의 hash 필드는 빠진다.
 * 저장 시각·시퀀스는 들어가야 재배열을 탐지할 수 있다.
 *
 * <h2>무엇을 해시하는가</h2>
 *
 * <p>{@code prevHash} 바이트 뒤에 payload를 이어 붙이는 대신 <b>봉투를 만들어 통째로
 * 정규화</b>한다.
 *
 * <pre>{@code
 * sha256( CanonicalJson.bytes({ "payload": <payload>, "prevHash": "...", "seq": N }) )
 * }</pre>
 *
 * <p>이유는 <b>외부 재현 가능성</b>이다. contentHash의 용도가 "이 기록은 위조되지 않았다"인데
 * (decision-log 5.11), 감사자가 우리 코드 없이 같은 값을 재계산할 수 있어야 그 주장이 성립한다
 * (ADR-008이 RFC 8785를 그대로 쓰기로 한 것과 같은 이유). 봉투는 그 자체가 JSON이라
 * "이 세 필드를 이 이름으로 담아 JCS로 직렬화하고 sha256" 한 줄로 규약이 끝난다. 바이트 연결은
 * 구분자·인코딩·순서를 따로 설명해야 한다.
 *
 * <p><b>seq가 해시 대상에 들어간다.</b> 안 들어가면 payload와 prevHash가 같은 두 항목을 맞바꿔도
 * 해시가 그대로여서 재배열이 안 잡힌다. 그래서 {@code link}가 seq를 받는다 — 스텁의
 * {@code link(prevHash, payload)}로는 이 성질을 만들 수 없다.
 *
 * <p><b>저장 시각은 payload 안에 있다.</b> {@code EvidenceRecorder}의 append 계열이 전부
 * {@code Instant at}을 받아 payload로 넘기므로 봉투에 따로 두지 않는다. 두 곳에 두면 어느 쪽이
 * 진짜 시각인지 모호해진다.
 *
 * <p><b>자기 자신의 hash는 대상에서 빠진다</b> — 자기를 포함하면 계산이 성립하지 않는다.
 * 그래서 {@link ChainEntry#hash()}는 검증할 때 <i>재계산 결과와 대조하는 값</i>이지 입력이 아니다.
 */
public final class HashChain {

    /** 체인의 첫 항목이 참조하는 값. 제네시스를 상수로 고정해야 검증이 시작점을 안다. */
    public static final String GENESIS = "0".repeat(64);

    /** 각 스트림의 첫 항목 시퀀스. 머리를 잘라내는 것을 탐지하려면 시작값도 고정돼야 한다. */
    public static final long FIRST_SEQ = 0L;

    /** prevHash + seq + 정규화된 payload → 이 항목의 hash (SHA-256 소문자 hex 64자). */
    public static String link(String prevHash, long seq, Object payload) {
        if (prevHash == null || prevHash.length() != 64) {
            throw new IllegalArgumentException(
                    "prevHash 는 64자 hex 여야 한다(첫 항목은 GENESIS): " + prevHash);
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("prevHash", prevHash);
        envelope.put("seq", seq);
        envelope.put("payload", payload);
        return sha256Hex(CanonicalJson.bytes(envelope));
    }

    /**
     * 체인 전체 재계산 검증. 끊긴 지점을 돌려준다 — 감사에서 "어디부터 못 믿는가"가 답이라
     * 참/거짓만으로는 쓸모가 적다.
     *
     * <p>보는 것은 셋이다. (1) 연결 — 각 항목의 prevHash가 직전 항목의 hash인가, 첫 항목은
     * GENESIS인가. (2) 시퀀스 — {@link #FIRST_SEQ}에서 시작해 1씩 증가하는가(구멍·중복 탐지).
     * (3) 내용 — 저장된 hash가 payload를 다시 해시한 값과 같은가(변조 탐지).
     */
    public static Verification verify(Iterable<? extends ChainEntry> entries) {
        String previousHash = GENESIS;
        long expectedSeq = FIRST_SEQ;
        int index = 0;

        for (ChainEntry entry : entries) {
            if (!previousHash.equals(entry.prevHash())) {
                return Verification.broken(index, entry.seq(), index == 0
                        ? "첫 항목의 prevHash 가 GENESIS 가 아니다 — 체인 머리가 잘렸을 수 있다"
                        : "prevHash 가 직전 항목의 hash 와 다르다 — 항목이 빠졌거나 바뀌었다");
            }
            if (entry.seq() != expectedSeq) {
                return Verification.broken(index, entry.seq(),
                        "seq 가 " + expectedSeq + " 이어야 하는데 " + entry.seq() + " 다 — 구멍이거나 재배열이다");
            }
            String recomputed = link(entry.prevHash(), entry.seq(), entry.payload());
            if (!recomputed.equals(entry.hash())) {
                return Verification.broken(index, entry.seq(),
                        "저장된 hash 가 payload 재계산과 다르다 — 내용이 바뀌었다");
            }
            previousHash = entry.hash();
            expectedSeq = entry.seq() + 1;
            index++;
        }
        return Verification.ok(index);
    }

    /**
     * 검증 결과. 끊긴 지점을 인덱스와 seq 둘 다로 돌려준다 — 인덱스는 재생 목록에서의 위치,
     * seq는 저장된 값이고, <b>둘이 어긋나는 것 자체가 정보</b>다(중간이 통째로 빠지면 갈린다).
     *
     * @param ok        전부 통과했는가
     * @param checked   검사한 항목 수
     * @param brokenAt  끊긴 항목의 인덱스(0-based). 통과했으면 -1
     * @param brokenSeq 끊긴 항목에 저장돼 있던 seq. 통과했으면 -1
     * @param reason    끊긴 이유. 통과했으면 빈 문자열
     */
    public record Verification(boolean ok, int checked, int brokenAt, long brokenSeq, String reason) {

        static Verification ok(int checked) {
            return new Verification(true, checked, -1, -1L, "");
        }

        static Verification broken(int index, long seq, String reason) {
            return new Verification(false, index, index, seq, reason);
        }
    }

    /** 체인에 들어가는 항목이 최소로 만족해야 하는 형태. */
    public interface ChainEntry {
        long seq();
        String prevHash();
        String hash();
        Object payload();
    }

    private static String sha256Hex(byte[] input) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 은 모든 JVM 이 제공해야 한다", e);
        }
        byte[] hashed = digest.digest(input);
        StringBuilder hex = new StringBuilder(hashed.length * 2);
        for (byte b : hashed) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    private HashChain() {}
}
