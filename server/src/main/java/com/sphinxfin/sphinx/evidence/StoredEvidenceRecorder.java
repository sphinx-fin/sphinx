package com.sphinxfin.sphinx.evidence;

import com.sphinxfin.sphinx.domain.SuitabilityMismatch;
import com.sphinxfin.sphinx.core.EvidenceRecorder;
import com.sphinxfin.sphinx.domain.GateResult;
import com.sphinxfin.sphinx.domain.InputMeta;
import com.sphinxfin.sphinx.domain.Judgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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
    public void appendJudgment(String sessionId, Judgment judgment, int reverifyCount,
                               String askedQuestion, QuestionSource questionSource,
                               InputMeta inputMeta, Instant at) {
        Map<String, Object> payload = envelope("judgment", sessionId, at);
        payload.put("reverifyCount", reverifyCount);
        // 판정을 만든 질문. null 은 "이 필드가 생기기 전 레코드" 하나만 뜻한다 —
        // 생략하지 않는 이유는 misconceptionType 과 같다(없음과 미기재를 가른다). 이슈 #136.
        payload.put("askedQuestion", askedQuestion);
        // 문면만으로는 폴백을 못 가른다 — 목 문면도 질문처럼 생겼다 (#136 3항).
        payload.put("questionSource", questionSource);
        payload.put("judgment", judgmentPayload(judgment));
        // F-INT-003 입력 메타데이터 (이슈 #325). **무엇을 말했나가 아니라 어떻게 입력했나** 다 —
        // 붙여넣기로 채운 되말하기는 발화 내용만 보면 완벽한 U1 로 채점된다.
        //
        // null 은 "화면이 안 보냈다" 이고 생략하지 않는다 — askedQuestion·misconceptionType 과
        // 같은 규약이다(없음과 미기재를 가른다, 이슈 #136). append-only 라 지금부터 쌓이는
        // 것만 남고, 이 필드가 생기기 전 항목은 영원히 null 이다.
        payload.put("inputMeta", inputMeta);
        store.append(streamOf(sessionId), payload);
    }

    @Override
    @Transactional
    public void appendGate(String sessionId, GateResult result, Instant at) {
        Map<String, Object> payload = envelope("gate", sessionId, at);
        payload.put("signal", result.signal());
        payload.put("ruleTrace", result.ruleTrace());
        // 판정을 만든 입력. 0 이어도 생략하지 않는다 — misconceptionType·promptVersion·escalate
        // 와 같은 규약이다(없음과 미기재를 가른다, 이슈 #136).
        //
        // ❗재계산으로 못 되돌리는 값이라 여기서 담는다. 항목이 마저 채점되면 미측정은 0 이
        // 되고 gate_rules.yaml 은 언제든 바뀐다 — 그러면 "왜 이 신호였나" 를 되짚을 수 없다.
        // 그리고 역산 경로가 없다: 세션은 인메모리이고, askedQuestion 은 appendJudgment 안에서만
        // 실려서(판정이 있는 항목만) **물어봤는데 못 잰 항목은 이 스트림에 아예 안 온다**
        // (이슈 #280 ② · #295).
        //
        // rulesVersion 이 0 이면 "버전 0 인 룰셋" 이 아니라 **파일을 안 지나온 룰**이다
        // (GateEngine.UNVERSIONED). 프로덕션 경로는 GateConfig 가 파일을 읽는 생성자뿐이라
        // 그 값이 기록에 나오지 않는다 — 호출부가 테스트 셋뿐인 것을 확인했다.
        payload.put("unmeasured", result.unmeasured());
        payload.put("rulesVersion", result.rulesVersion());
        store.append(streamOf(sessionId), payload);
    }

    @Override
    @Transactional
    public void appendMismatch(String sessionId, SuitabilityMismatch mismatch,
                               String surveySchemaVersion, Map<String, Object> surveyResult,
                               Instant at) {
        Map<String, Object> payload = envelope("mismatch", sessionId, at);
        payload.put("status", mismatch.status());
        // 근거 셋. null 이어도 생략하지 않는다 — misconceptionType 과 같은 규약이다(#136).
        // 여기서는 특히 중요하다: 호출 실패로 근거가 없는 것과 필드가 생기기 전 레코드가
        // 같아 보이면, 감사 시점에 "판정은 했는데 근거가 없다" 를 못 가른다.
        payload.put("reason", mismatch.reason());
        payload.put("confidence", mismatch.confidence());
        payload.put("contradictions", mismatch.contradictions());
        // 판정을 만든 입력. 세션 테이블에만 있으면 재질문·재판정에 덮인다 (#169).
        payload.put("surveySchemaVersion", surveySchemaVersion);
        payload.put("surveyResult", surveyResult);
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
     * <p>레코드를 통째로 넘기지 않고 여기서 펴는 이유는 <b>담는 것을 골라야 하기</b> 때문이다 —
     * 위의 색 규약이 그것이고, {@code misconceptionType} 을 null 이어도 생략하지 않는 것도 여기서
     * 정한다. {@link CanonicalJson} 은 이제 {@code Judgment} 를 직접 순회할 수도 있다
     * (CanonicalJsonTest 의 {@code judgmentIsSerializableOnceConfidenceIsFixed} 가 확인한다).
     * 그래도 펴는 쪽을 남긴다: <b>무엇을 담는지가 이 파일에 보여야</b> 한다.
     *
     * <h2>❗{@code escalate} 는 반드시 담는다 — 상신의 유일한 근거가 된다</h2>
     *
     * <p>{@code escalate} 가 들어오면 <b>그 값이 컴플라이언스 상신을 결정한다.</b>
     * {@code UnfairSalesTypes} 가 <i>"계약이 열리면 사라진다"</i> 고 예고해 둔 자리를 그 값이
     * 대신한다. 그런데 지금까지 담기던 {@code misconceptionType} 은 <b>바로 그 경우에
     * null</b> 이다 — 루브릭 17종 중 {@code M08-TYING} 을 {@code related_misconceptions} 에
     * 건 것이 하나도 없어서 {@code apply_misconception_floor} 가 유형을 안 싣는다(이슈 #160).
     *
     * <p>그래서 이 필드를 빼면 <b>상신된 세션의 기록에 상신 사유가 아무것도 없다</b> —
     * 담긴 유형은 null 이고 신호는 어디에도 없다. P4 가 막으려는 <i>"근거 없는 판정"</i> 이
     * 감사 기록 쪽에서 생기는 모양이고, 이 결함은 <b>분쟁으로 기록을 열어 보는 날까지
     * 드러나지 않는다.</b>
     *
     * <p>{@code #246} 이 필드를 더할 때 여기가 안 따라온 이유는 이 파일을 건드릴 일이 없었기
     * 때문이다. 위 문단이 <i>"무엇을 담는지가 이 파일에 보여야 한다"</i> 고 적어 두었지만
     * 그건 규약일 뿐 강제가 아니었다 — {@code JudgmentIsFullyRecordedTest} 가 이제 그것을
     * 강제한다. {@code Judgment} 에 필드가 늘면 그 테스트가 실패하고, 담을지 뺄지를
     * <b>여기서 정하게</b> 된다.
     */
    private static Map<String, Object> judgmentPayload(Judgment judgment) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("itemId", judgment.itemId());
        item.put("grade", judgment.grade());
        item.put("confidence", judgment.confidence());
        item.put("evidence", judgment.evidence());
        item.put("reason", judgment.reason());
        item.put("misconceptionType", judgment.misconceptionType());   // nullable — 생략하지 않는다
        item.put("promptVersion", judgment.promptVersion());           // nullable — 위와 같은 이유
        item.put("escalate", judgment.escalate());                     // 상신 판단의 근거 — 아래 참조
        return item;
    }
}
