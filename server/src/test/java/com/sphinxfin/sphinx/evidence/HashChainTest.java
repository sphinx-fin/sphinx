package com.sphinxfin.sphinx.evidence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 해시 체인 규약. 소유: 정세현
 *
 * <p>이 테스트가 지키는 것은 "기록을 손대면 반드시 드러난다"다. 체인의 값은 변조를 *막는* 데
 * 있지 않고 — append-only 저장소는 물리적으로 막지 못한다 — <b>손댄 사실이 검증에서 드러나는</b>
 * 데 있다. 그래서 정상 경로보다 <b>변조·삭제·재배열이 실제로 잡히는지</b>에 케이스를 더 뒀다.
 */
@DisplayName("HashChain — append-only 체인")
class HashChainTest {

    /** 테스트용 최소 구현. 저장 계층(ImmutableStore)이 아직 없어서 여기서 만든다. */
    record Entry(long seq, String prevHash, String hash, Object payload) implements HashChain.ChainEntry {}

    /** payload 목록으로 정상 체인을 만든다. */
    private static List<Entry> chainOf(Object... payloads) {
        List<Entry> entries = new ArrayList<>();
        String prev = HashChain.GENESIS;
        long seq = HashChain.FIRST_SEQ;
        for (Object payload : payloads) {
            String hash = HashChain.link(prev, seq, payload);
            entries.add(new Entry(seq, prev, hash, payload));
            prev = hash;
            seq++;
        }
        return entries;
    }

    private static Map<String, Object> judgment(String itemId, String grade) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("itemId", itemId);
        m.put("grade", grade);
        m.put("at", Instant.parse("2026-08-27T01:00:00.000Z"));
        return m;
    }

    @Nested
    @DisplayName("link — 무엇이 해시 대상에 들어가는가")
    class Link {

        @Test
        @DisplayName("소문자 hex 64자")
        void producesLowercaseHex() {
            assertThat(HashChain.link(HashChain.GENESIS, 0, judgment("A", "U1")))
                    .hasSize(64)
                    .matches("[0-9a-f]{64}");
        }

        @Test
        @DisplayName("같은 입력이면 같은 해시 — 100회")
        void isDeterministic() {
            String first = HashChain.link(HashChain.GENESIS, 0, judgment("A", "U1"));
            for (int i = 0; i < 100; i++) {
                assertThat(HashChain.link(HashChain.GENESIS, 0, judgment("A", "U1"))).isEqualTo(first);
            }
        }

        @Test
        @DisplayName("prevHash 가 대상에 들어간다 — 그래서 체인이 된다")
        void prevHashIsPartOfTheHash() {
            String other = "1".repeat(64);
            assertThat(HashChain.link(HashChain.GENESIS, 0, judgment("A", "U1")))
                    .isNotEqualTo(HashChain.link(other, 0, judgment("A", "U1")));
        }

        @Test
        @DisplayName("seq 가 대상에 들어간다 — 안 들어가면 같은 내용의 두 항목을 맞바꿔도 안 잡힌다")
        void seqIsPartOfTheHash() {
            assertThat(HashChain.link(HashChain.GENESIS, 0, judgment("A", "U1")))
                    .isNotEqualTo(HashChain.link(HashChain.GENESIS, 1, judgment("A", "U1")));
        }

        @Test
        @DisplayName("payload 가 한 글자만 달라도 해시가 달라진다")
        void payloadChangesHash() {
            assertThat(HashChain.link(HashChain.GENESIS, 0, judgment("A", "U1")))
                    .isNotEqualTo(HashChain.link(HashChain.GENESIS, 0, judgment("A", "U2")));
        }

        @Test
        @DisplayName("prevHash 형식이 아니면 던진다 — 빈 문자열로 체인을 시작할 수 없다")
        void rejectsMalformedPrevHash() {
            assertThatThrownBy(() -> HashChain.link("", 0, judgment("A", "U1")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("GENESIS");
            assertThatThrownBy(() -> HashChain.link(null, 0, judgment("A", "U1")))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("외부 감사자가 우리 코드 없이 재계산할 수 있다 — 봉투 JSON 을 sha256 한 값이다")
        void isReproducibleWithoutOurCode() throws Exception {
            Object payload = judgment("A", "U1");
            String prev = HashChain.GENESIS;

            // 감사자가 하는 일: 봉투 세 필드를 JCS 로 직렬화하고 sha256.
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("payload", payload);
            envelope.put("prevHash", prev);
            envelope.put("seq", 7L);
            byte[] canonical = CanonicalJson.bytes(envelope);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical);
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }

            assertThat(HashChain.link(prev, 7L, payload))
                    .as("규약이 '이 세 필드를 이 이름으로 담아 JCS 직렬화 후 sha256' 한 줄로 끝나야 한다")
                    .isEqualTo(hex.toString());
        }
    }

    @Nested
    @DisplayName("verify — 손댄 사실이 드러나는가")
    class Verify {

        @Test
        @DisplayName("정상 체인은 통과하고 검사한 개수를 돌려준다")
        void acceptsIntactChain() {
            HashChain.Verification result =
                    HashChain.verify(chainOf(judgment("A", "U3"), judgment("A", "U1"), judgment("B", "U1")));
            assertThat(result.ok()).isTrue();
            assertThat(result.checked()).isEqualTo(3);
            assertThat(result.brokenAt()).isEqualTo(-1);
        }

        @Test
        @DisplayName("빈 체인은 유효하다 — 아직 아무것도 적재되지 않은 세션이다")
        void acceptsEmptyChain() {
            assertThat(HashChain.verify(List.of()).ok()).isTrue();
        }

        @Test
        @DisplayName("payload 를 고치면 잡힌다 — hash 는 그대로 두고 내용만 바꾼 경우")
        void detectsTamperedPayload() {
            List<Entry> chain = new ArrayList<>(chainOf(
                    judgment("A", "U3"), judgment("A", "U1"), judgment("B", "U1")));
            Entry original = chain.get(1);
            chain.set(1, new Entry(original.seq(), original.prevHash(), original.hash(),
                    judgment("A", "U4")));          // U1 → U4 로 바꿔치기

            HashChain.Verification result = HashChain.verify(chain);
            assertThat(result.ok()).isFalse();
            assertThat(result.brokenAt()).isEqualTo(1);
            assertThat(result.reason()).contains("내용이 바뀌었다");
        }

        @Test
        @DisplayName("항목을 지우면 잡힌다 — 뒤 항목의 prevHash 가 안 맞는다")
        void detectsDeletedEntry() {
            List<Entry> chain = new ArrayList<>(chainOf(
                    judgment("A", "U3"), judgment("A", "U1"), judgment("B", "U1")));
            chain.remove(1);

            HashChain.Verification result = HashChain.verify(chain);
            assertThat(result.ok()).isFalse();
            assertThat(result.brokenAt()).isEqualTo(1);
            assertThat(result.brokenSeq())
                    .as("인덱스는 1 인데 저장된 seq 는 2 다 — 둘이 어긋나는 것 자체가 삭제의 흔적이다")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("머리를 잘라내면 잡힌다 — 첫 항목의 prevHash 가 GENESIS 가 아니다")
        void detectsTruncatedHead() {
            List<Entry> chain = new ArrayList<>(chainOf(
                    judgment("A", "U3"), judgment("A", "U1"), judgment("B", "U1")));
            chain.remove(0);

            HashChain.Verification result = HashChain.verify(chain);
            assertThat(result.ok()).isFalse();
            assertThat(result.brokenAt()).isEqualTo(0);
            assertThat(result.reason()).contains("GENESIS");
        }

        @Test
        @DisplayName("순서를 뒤바꾸면 잡힌다")
        void detectsReordering() {
            List<Entry> chain = new ArrayList<>(chainOf(
                    judgment("A", "U3"), judgment("A", "U1"), judgment("B", "U1")));
            Entry second = chain.get(1);
            chain.set(1, chain.get(2));
            chain.set(2, second);

            assertThat(HashChain.verify(chain).ok()).isFalse();
        }

        @Test
        @DisplayName("같은 판정을 두 번 적재해도 체인은 유효하다 — 중복을 흡수하지 않는다 (ADR-004)")
        void allowsDuplicatePayloads() {
            HashChain.Verification result =
                    HashChain.verify(chainOf(judgment("A", "U1"), judgment("A", "U1")));
            assertThat(result.ok())
                    .as("/judge 가 세 번 불린 사실 자체가 감사 정보다 — 저장소가 판단하지 않는다")
                    .isTrue();
            assertThat(result.checked()).isEqualTo(2);
        }

        @Test
        @DisplayName("같은 내용이어도 seq 가 달라 해시가 다르다 — 중복 적재가 구별된다")
        void duplicatePayloadsGetDistinctHashes() {
            List<Entry> chain = chainOf(judgment("A", "U1"), judgment("A", "U1"));
            assertThat(chain.get(0).hash()).isNotEqualTo(chain.get(1).hash());
        }
    }
}
