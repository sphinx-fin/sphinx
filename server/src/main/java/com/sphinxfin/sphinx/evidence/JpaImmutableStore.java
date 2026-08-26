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
        EvidenceStreamAnchor anchor = anchors.findById(stream)
                .orElseGet(() -> EvidenceStreamAnchor.start(stream));

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
