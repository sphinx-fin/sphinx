package com.sphinxfin.sphinx.evidence;

import com.sphinxfin.sphinx.core.EvidenceRecorder;
import com.sphinxfin.sphinx.domain.GateResult;
import com.sphinxfin.sphinx.domain.Judgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link EvidenceRecorder} 구현. 소유: 정세현
 *
 * <p>인터페이스는 {@code core}, 구현은 여기다 — {@code core}는 {@code evidence}를 모른다.
 * 등록되면 {@code SessionService}·{@code OverrideService}의 {@code NO_OP}을 대체한다.
 *
 * <h2>세 종류를 한 스트림에 쌓는다</h2>
 *
 * <p>스트림은 {@code report:{sessionId}} 하나이고 판정·게이트·오버라이드가 모두 여기 들어간다.
 * <b>세션에서 일어난 일의 순서가 하나의 사슬로 남아야</b> "황색이었다가 재설명 후 통과했고 그
 * 뒤에 오버라이드가 승인됐다"를 기록만으로 재구성할 수 있다. 종류별로 스트림을 나누면 각 사슬은
 * 온전한데 <b>사이의 순서가 사라진다</b> — 그게 이해 기록에서 제일 중요한 정보다.
 *
 * <p>그래서 payload에 {@code type} 판별자를 둔다. 하나의 스트림을 재생할 때 무엇이 무엇인지
 * 구별되어야 하고, 그 구별이 저장 시점에 박혀 있어야 나중에 필드 모양으로 추측하지 않는다.
 *
 * <p>{@code sessionId}는 스트림 이름에도 있지만 payload에도 담는다. <b>기록 한 건이 스스로
 * 어느 세션인지 말할 수 없으면 증거로 약하다</b> — 재생 맥락 밖으로 나가는 순간 미아가 된다.
 *
 * <h2>중복을 흡수하지 않는다 (ADR-004)</h2>
 *
 * <p>같은 항목을 재검증하면 판정이 두 건 쌓인다. 세션은 최신만 들고 있으므로
 * (<code>judgmentsByItem</code>이 Map이다) <b>덮어쓰기 전 값 — "처음에 황색이었다" — 는 여기에만
 * 남는다.</b> 기획서 174행이 이해 기록의 구성요소로 못박은 "재설명 이력"이 그것이다.
 */
@Component
public class StoredEvidenceRecorder implements EvidenceRecorder {

    /** 세션 이해 기록 스트림. 이슈 #54의 2번이 정한 이름이다. */
    static String streamOf(String sessionId) {
        return "report:" + sessionId;
    }

    private final ImmutableStore store;

    public StoredEvidenceRecorder(ImmutableStore store) {
        this.store = store;
    }

    @Override
    @Transactional
    public void appendJudgment(String sessionId, Judgment judgment, int reverifyCount, Instant at) {
        Map<String, Object> payload = envelope("judgment", sessionId, at);
        payload.put("reverifyCount", reverifyCount);
        payload.put("judgment", judgmentPayload(judgment));
        store.append(streamOf(sessionId), payload);
    }

    @Override
    @Transactional
    public void appendGate(String sessionId, GateResult result, Instant at) {
        Map<String, Object> payload = envelope("gate", sessionId, at);
        payload.put("signal", result.signal());
        payload.put("ruleTrace", result.ruleTrace());
        store.append(streamOf(sessionId), payload);
    }

    @Override
    @Transactional
    public void appendOverride(String sessionId, String reason, String approver, Instant at) {
        Map<String, Object> payload = envelope("override", sessionId, at);
        payload.put("reason", reason);
        payload.put("approver", approver);
        store.append(streamOf(sessionId), payload);
    }

    private static Map<String, Object> envelope(String type, String sessionId, Instant at) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", type);
        payload.put("sessionId", sessionId);
        payload.put("at", at);          // CanonicalJson이 ADR-008 형식(UTC·밀리초 3자리)으로 적는다
        return payload;
    }

    /**
     * 판정을 담을 수 있는 형태로 편다.
     *
     * <p><b>색은 담지 않는다</b>(ADR-004 §5) — {@code grade} 원값과 근거만 담는다. 표시 관례가
     * 바뀌면 같은 판정의 해시가 달라지고 교차 검증이 무너진다.
     *
     * <p>{@code Judgment} 레코드를 통째로 넘기지 않고 여기서 펴는 이유는 {@link #confidenceOf}
     * 하나 때문이다. 그것만 아니면 {@link CanonicalJson}이 레코드를 직접 순회할 수 있다
     * (CanonicalJsonTest의 {@code judgmentIsSerializableOnceConfidenceIsFixed}가 확인한다).
     */
    private static Map<String, Object> judgmentPayload(Judgment judgment) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("itemId", judgment.itemId());
        item.put("grade", judgment.grade());
        item.put("confidence", confidenceOf(judgment));
        item.put("evidence", judgment.evidence());
        item.put("reason", judgment.reason());
        item.put("misconceptionType", judgment.misconceptionType());   // nullable — 생략하지 않는다
        return item;
    }

    /**
     * ⚠️ <b>10.32가 닫힐 때까지의 임시 다리다.</b>
     *
     * <p>ADR-008은 해시 대상에 {@code double}을 담지 않기로 했는데 {@code Judgment.confidence}가
     * 아직 {@code double}이라, 그대로 넘기면 {@link CanonicalJson}이 거부한다. 그래서 여기서
     * {@code BigDecimal}로 바꾼다.
     *
     * <p><b>이것은 내가 10.32에서 반대한 (2)번 안이다</b> — *"담지 않는다"를 담는 쪽에서 우회하는
     * 모양*이고, 한 번 허용하면 다음 {@code double} 필드에서 또 한다. 영구 해법은 여전히 (1)번
     * (도메인 타입을 {@code BigDecimal}로)이고 그 PR은 강희진이 올린다.
     *
     * <p>그때까지 이 다리가 없으면 <b>모든 답변 제출이 500으로 실패한다</b> —
     * {@code SessionService.recordJudgment}가 매 건 append하기 때문이다. 그래서 두되,
     * <b>잊히지 않게 만든다</b>: {@code StoredEvidenceRecorderTest}가 {@code confidence}의 타입이
     * {@code double}인 동안만 이 메서드가 필요하다는 것을 단정하므로, 타입이 바뀌는 순간 그
     * 테스트가 깨져서 여기를 지우라고 알려준다.
     *
     * <p>{@code BigDecimal.valueOf}를 쓴다. {@code new BigDecimal(double)}은 이진 근사를 그대로
     * 펼쳐 {@code 0.9100000000000000355...}가 되고, 그러면 같은 판정이 다른 해시를 낸다.
     */
    private static BigDecimal confidenceOf(Judgment judgment) {
        return BigDecimal.valueOf(judgment.confidence());
    }
}
