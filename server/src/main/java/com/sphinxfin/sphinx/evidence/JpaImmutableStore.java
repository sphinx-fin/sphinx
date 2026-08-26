package com.sphinxfin.sphinx.evidence;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * H2/JPA 기반 append-only 저장. 소유: 정세현
 *
 * <h2>payload를 문자열로 저장하고 재생 때 파싱한다</h2>
 *
 * <p>이 왕복이 성립하는 이유는 <b>정규화 JSON이 고정점</b>이라는 데 있다. 정규화된 문자열을
 * 파싱해서 다시 정규화하면 같은 바이트가 나온다 — 키는 이미 정렬돼 있고, 숫자는 이미 정규
 * 표기이며, 타임스탬프는 이미 문자열이다. 그래서 저장 시점에 계산한 hash와 재생 후 재계산한
 * hash가 같고, {@link #verify}가 성립한다. <b>이 성질이 깨지면 저장된 기록 전체가 검증
 * 불가능해지므로</b> 테스트로 직접 고정한다.
 *
 * <p>파싱 매퍼에 {@code USE_BIG_DECIMAL_FOR_FLOATS}를 켠다. 안 켜면 Jackson이 소수를
 * {@code double}로 읽고, 그 순간 {@link CanonicalJson}이 <b>거부</b>한다(ADR-008) — 적재는
 * 됐는데 재생이 안 되는 상태가 된다.
 *
 * <h2>트랜잭션</h2>
 *
 * <p>{@code @Transactional}이되 새 트랜잭션을 열지 않는다. 호출자(세션 저장)의 트랜잭션에
 * 참여해야 <b>append가 실패하면 세션 저장도 함께 롤백</b>된다(ADR-004: 기록 없는 판정도 무효).
 */
@Repository
public class JpaImmutableStore implements ImmutableStore {

    private final EvidenceEntryRepository entries;
    private final EvidenceStreamAnchorRepository anchors;

    /** 재생 전용 매퍼. 소수를 double로 읽으면 CanonicalJson이 거부하므로 BigDecimal로 고정한다. */
    private final ObjectMapper replayMapper = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    public JpaImmutableStore(EvidenceEntryRepository entries, EvidenceStreamAnchorRepository anchors) {
        this.entries = entries;
        this.anchors = anchors;
    }

    @Override
    @Transactional
    public HashChain.ChainEntry append(String stream, Object payload) {
        // 닻을 잡고 읽는다. 잠그지 않으면 두 트랜잭션이 같은 count 를 읽어 같은 seq 를 만들고,
        // 유니크 제약에서 진 쪽 기록이 사라진다 — 사슬은 온전하고 검증은 통과하는데 기록만 없다.
        // 20건 동시 적재에서 8건만 남는 것을 실측했다(PR #96 리뷰).
        EvidenceStreamAnchor anchor = lockAnchor(stream);

        long seq = HashChain.FIRST_SEQ + anchor.count();
        String prevHash = anchor.headHash();
        String payloadJson = CanonicalJson.serialize(payload);
        String hash = HashChain.link(prevHash, seq, payload);

        entries.save(new EvidenceEntry(null, stream, seq, prevHash, hash, payloadJson));
        anchor.advance(hash);
        anchors.save(anchor);

        return new Replayed(seq, prevHash, hash, payload);
    }

    @Override
    @Transactional(readOnly = true)
    public List<HashChain.ChainEntry> replay(String stream) {
        List<HashChain.ChainEntry> replayed = new ArrayList<>();
        for (EvidenceEntry entry : entries.findByStreamOrderBySeqAsc(stream)) {
            replayed.add(new Replayed(entry.seq(), entry.prevHash(), entry.hash(),
                    parse(entry.payloadJson())));
        }
        return replayed;
    }

    @Override
    @Transactional(readOnly = true)
    public String head(String stream) {
        return anchors.findById(stream)
                .map(EvidenceStreamAnchor::headHash)
                .orElse(HashChain.GENESIS);
    }

    @Override
    @Transactional(readOnly = true)
    public HashChain.Verification verify(String stream) {
        List<HashChain.ChainEntry> chain = replay(stream);
        HashChain.Verification chained = HashChain.verify(chain);
        if (!chained.ok()) {
            return chained;
        }

        EvidenceStreamAnchor anchor = anchors.findById(stream).orElse(null);
        long expected = anchor == null ? 0L : anchor.count();
        if (chained.checked() != expected) {
            return new HashChain.Verification(false, chained.checked(), chained.checked(), -1L,
                    "적재된 항목이 " + expected + "건이어야 하는데 " + chained.checked()
                            + "건만 남아 있다 — 꼬리가 잘렸다");
        }

        String head = anchor == null ? HashChain.GENESIS : anchor.headHash();
        String actualHead = chain.isEmpty()
                ? HashChain.GENESIS
                : chain.get(chain.size() - 1).hash();
        if (!head.equals(actualHead)) {
            return new HashChain.Verification(false, chained.checked(), chained.checked(), -1L,
                    "머리 hash 가 닻과 다르다 — 마지막 항목이 바뀌었다");
        }
        return chained;
    }

    /**
     * 닻을 잠그고 읽는다. 첫 append 에는 잡을 행이 없으므로 <b>이 트랜잭션 안에서</b> 만든다.
     *
     * <p><b>별도 트랜잭션으로 만들지 않는 이유가 있다.</b> {@code REQUIRES_NEW} 로 격리하면
     * 이 트랜잭션이 커넥션을 쥔 채 두 번째 커넥션을 요구하게 되고, 동시 요청이 풀 크기를
     * 넘으면 <b>전원이 서로의 커넥션을 기다리며 멈춘다</b>(20 스레드 · 풀 10 에서 실측 —
     * 30초 안에 끝나지 않았다). 중첩 트랜잭션은 이 경로에 둘 수 없다.
     *
     * <p>대신 <b>같은 스트림의 첫 append 가 동시에 오면 하나가 PK 충돌로 진다.</b> 스트림당
     * 한 번뿐인 창이고, 상시 경합이 있는 {@code audit} 스트림은 {@link AuditLog} 가 미리 열어
     * 그 창을 없앤다. 세션 스트림은 그 세션의 요청 하나만 쓰므로 경합 자체가 없다.
     */
    private EvidenceStreamAnchor lockAnchor(String stream) {
        return anchors.findForUpdate(stream)
                .orElseGet(() -> anchors.saveAndFlush(EvidenceStreamAnchor.start(stream)));
    }

    /**
     * 스트림을 미리 열어 둔다(멱등). 첫 append 경합 창을 없애려는 것이고, 이미 있으면 아무
     * 일도 하지 않는다. 항목이 0건인 닻은 빈 스트림과 같은 상태라 무해하다.
     */
    @Transactional
    public void openStream(String stream) {
        if (!anchors.existsById(stream)) {
            anchors.save(EvidenceStreamAnchor.start(stream));
        }
    }

    /** 저장된 정규화 JSON을 CanonicalJson이 다시 담을 수 있는 타입으로 되돌린다. */
    private Object parse(String payloadJson) {
        try {
            return replayMapper.readValue(payloadJson, Object.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(
                    "저장된 payload 를 재생할 수 없다 — 정규화 JSON 이 아니다: " + payloadJson, e);
        }
    }

    /** 재생된 항목. 엔티티는 payload를 문자열로 들고 있으므로 파싱 결과를 여기 담는다. */
    record Replayed(long seq, String prevHash, String hash, Object payload)
            implements HashChain.ChainEntry {}
}
