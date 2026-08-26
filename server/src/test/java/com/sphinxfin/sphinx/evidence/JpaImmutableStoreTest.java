package com.sphinxfin.sphinx.evidence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * append-only 저장 계층. 소유: 정세현
 *
 * <p>가장 중요한 케이스는 <b>왕복</b>이다 — 저장한 payload 를 다시 읽어 해시를 재계산했을 때
 * 저장 시점의 값과 같아야 한다. 이게 깨지면 적재는 되는데 <b>기록 전체가 검증 불가능</b>해지고,
 * 그 사실은 감사 시점에야 드러난다. 그래서 정상 경로보다 왕복과 꼬리 절단에 케이스를 더 뒀다.
 */
@DataJpaTest
@Import(JpaImmutableStore.class)
@DisplayName("JpaImmutableStore — append-only 저장")
class JpaImmutableStoreTest {

    private static final String STREAM = "report:S-1";

    @Autowired
    private JpaImmutableStore store;
    @Autowired
    private EvidenceEntryRepository entries;
    @Autowired
    private TestEntityManager em;

    private static Map<String, Object> judgment(String itemId, String grade, String confidence) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("itemId", itemId);
        m.put("grade", grade);
        m.put("confidence", new BigDecimal(confidence));
        m.put("at", Instant.parse("2026-08-27T01:00:00.000Z"));
        m.put("misconceptionType", null);
        return m;
    }

    @Nested
    @DisplayName("왕복 — 정규화 JSON 이 고정점이라 검증이 성립한다")
    class RoundTrip {

        @Test
        @DisplayName("저장한 payload 를 재생해 해시를 재계산하면 저장 시점 값과 같다")
        void replayedPayloadRehashesIdentically() {
            HashChain.ChainEntry appended = store.append(STREAM, judgment("A", "U4", "0.91"));
            em.flush();
            em.clear();

            HashChain.ChainEntry replayed = store.replay(STREAM).get(0);

            assertThat(HashChain.link(replayed.prevHash(), replayed.seq(), replayed.payload()))
                    .as("이게 깨지면 적재는 되는데 기록 전체가 검증 불가능해진다")
                    .isEqualTo(appended.hash());
        }

        @Test
        @DisplayName("소수는 BigDecimal 로 읽는다 — double 로 읽으면 CanonicalJson 이 거부한다")
        void parsesDecimalsAsBigDecimal() {
            store.append(STREAM, judgment("A", "U4", "0.91"));
            em.flush();
            em.clear();

            Object payload = store.replay(STREAM).get(0).payload();
            Object confidence = ((Map<?, ?>) payload).get("confidence");

            assertThat(confidence).isInstanceOf(BigDecimal.class);
            assertThat(CanonicalJson.serialize(payload)).contains("\"confidence\":0.91");
        }

        @Test
        @DisplayName("재생한 payload 를 다시 정규화해도 같은 문자열 — 고정점")
        void canonicalJsonIsAFixedPoint() {
            Object original = judgment("A", "U4", "0.91");
            String once = CanonicalJson.serialize(original);

            store.append(STREAM, original);
            em.flush();
            em.clear();

            assertThat(CanonicalJson.serialize(store.replay(STREAM).get(0).payload()))
                    .isEqualTo(once);
        }
    }

    @Nested
    @DisplayName("append — 이어 붙이고 흡수하지 않는다")
    class Append {

        @Test
        @DisplayName("첫 항목은 GENESIS 를 참조하고 seq 는 FIRST_SEQ")
        void firstEntryStartsFromGenesis() {
            HashChain.ChainEntry first = store.append(STREAM, judgment("A", "U1", "0.9"));
            assertThat(first.prevHash()).isEqualTo(HashChain.GENESIS);
            assertThat(first.seq()).isEqualTo(HashChain.FIRST_SEQ);
        }

        @Test
        @DisplayName("같은 payload 를 두 번 적재해도 두 건이다 — 중복을 흡수하지 않는다 (ADR-004)")
        void doesNotDeduplicate() {
            HashChain.ChainEntry first = store.append(STREAM, judgment("A", "U1", "0.9"));
            HashChain.ChainEntry second = store.append(STREAM, judgment("A", "U1", "0.9"));
            em.flush();

            assertThat(store.replay(STREAM)).hasSize(2);
            assertThat(second.hash())
                    .as("seq 가 다르므로 해시도 다르다 — 두 건이 구별된다")
                    .isNotEqualTo(first.hash());
            assertThat(store.verify(STREAM).ok()).isTrue();
        }

        @Test
        @DisplayName("스트림이 다르면 체인도 다르다 — 각자 GENESIS 에서 시작한다")
        void streamsAreIndependent() {
            store.append("report:S-1", judgment("A", "U1", "0.9"));
            HashChain.ChainEntry audit = store.append("audit", judgment("A", "U1", "0.9"));
            em.flush();

            assertThat(audit.prevHash()).isEqualTo(HashChain.GENESIS);
            assertThat(store.replay("report:S-1")).hasSize(1);
            assertThat(store.replay("audit")).hasSize(1);
            assertThat(store.verify("report:S-1").ok()).isTrue();
            assertThat(store.verify("audit").ok()).isTrue();
        }

        @Test
        @DisplayName("head 는 마지막 hash — 비어 있으면 GENESIS")
        void headTracksLastHash() {
            assertThat(store.head(STREAM)).isEqualTo(HashChain.GENESIS);
            store.append(STREAM, judgment("A", "U3", "0.8"));
            HashChain.ChainEntry last = store.append(STREAM, judgment("A", "U1", "0.9"));
            em.flush();
            assertThat(store.head(STREAM)).isEqualTo(last.hash());
        }
    }

    @Nested
    @DisplayName("verify — 닻이 꼬리 절단을 잡는다")
    class Verify {

        @Test
        @DisplayName("정상 스트림은 통과한다")
        void acceptsIntactStream() {
            store.append(STREAM, judgment("A", "U3", "0.8"));
            store.append(STREAM, judgment("A", "U1", "0.9"));
            store.append(STREAM, judgment("B", "U1", "0.95"));
            em.flush();

            HashChain.Verification result = store.verify(STREAM);
            assertThat(result.ok()).isTrue();
            assertThat(result.checked()).isEqualTo(3);
        }

        @Test
        @DisplayName("적재된 적 없는 스트림은 통과한다 — 빈 것과 지워진 것은 닻으로 갈린다")
        void acceptsUntouchedStream() {
            assertThat(store.verify("report:없는세션").ok()).isTrue();
        }

        @Test
        @DisplayName("꼬리를 지우면 잡힌다 — 체인만으로는 통과하는 경우다")
        void detectsTailTruncation() {
            store.append(STREAM, judgment("A", "U3", "0.8"));
            store.append(STREAM, judgment("A", "U1", "0.9"));
            store.append(STREAM, judgment("B", "U1", "0.95"));
            em.flush();

            List<EvidenceEntry> all = entries.findByStreamOrderBySeqAsc(STREAM);
            entries.delete(all.get(2));                 // 마지막 한 건만 삭제
            em.flush();
            em.clear();

            assertThat(HashChain.verify(store.replay(STREAM)).ok())
                    .as("체인만 보면 남은 둘은 완전한 체인이라 통과한다")
                    .isTrue();

            HashChain.Verification result = store.verify(STREAM);
            assertThat(result.ok()).isFalse();
            assertThat(result.reason()).contains("꼬리가 잘렸다");
        }

        @Test
        @DisplayName("전부 지워도 잡힌다 — 빈 체인과 구별된다")
        void detectsFullDeletion() {
            store.append(STREAM, judgment("A", "U3", "0.8"));
            store.append(STREAM, judgment("A", "U1", "0.9"));
            em.flush();

            entries.deleteAll(entries.findByStreamOrderBySeqAsc(STREAM));
            em.flush();
            em.clear();

            HashChain.Verification result = store.verify(STREAM);
            assertThat(result.ok())
                    .as("닻이 2건을 기억하므로 '적재된 적 없는 스트림' 과 갈린다")
                    .isFalse();
            assertThat(result.checked()).isZero();
        }

        @Test
        @DisplayName("중간을 지우면 체인 쪽에서 먼저 잡힌다")
        void detectsMiddleDeletion() {
            store.append(STREAM, judgment("A", "U3", "0.8"));
            store.append(STREAM, judgment("A", "U1", "0.9"));
            store.append(STREAM, judgment("B", "U1", "0.95"));
            em.flush();

            entries.delete(entries.findByStreamOrderBySeqAsc(STREAM).get(1));
            em.flush();
            em.clear();

            HashChain.Verification result = store.verify(STREAM);
            assertThat(result.ok()).isFalse();
            assertThat(result.brokenAt()).isEqualTo(1);
        }
    }
}
