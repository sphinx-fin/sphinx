package com.sphinxfin.sphinx.core.session;

import com.sphinxfin.sphinx.domain.Channel;
import com.sphinxfin.sphinx.domain.GateResult;
import com.sphinxfin.sphinx.domain.Grade;
import com.sphinxfin.sphinx.domain.InputMeta;
import com.sphinxfin.sphinx.domain.Judgment;
import com.sphinxfin.sphinx.domain.OverrideStatus;
import com.sphinxfin.sphinx.domain.SessionState;
import com.sphinxfin.sphinx.domain.RuleRef;
import com.sphinxfin.sphinx.domain.Signal;
import com.sphinxfin.sphinx.domain.SuitabilityStatus;
import jakarta.persistence.CollectionTable;
import com.sphinxfin.sphinx.core.EvidenceRecorder;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.sphinxfin.sphinx.core.persistence.BaseEntity;
import com.sphinxfin.sphinx.core.persistence.JsonMapConverter;
import com.sphinxfin.sphinx.core.persistence.LongMapConverter;
import com.sphinxfin.sphinx.core.persistence.JudgmentMapConverter;
import com.sphinxfin.sphinx.core.persistence.RuleRefListConverter;
import com.sphinxfin.sphinx.core.persistence.StringListConverter;
import com.sphinxfin.sphinx.core.persistence.StringListMapConverter;

/**
 * F-INT-001 세션 집합체(JPA 엔티티). 소유: 강희진
 *
 * 서버 내부 세션 상태를 H2에 영속한다. 담는 속성은 전부 비식별이다 — 성명·주민번호 같은
 * PII 필드는 애초에 존재하지 않는다(P3). 상태 전이는 SessionFsm에 위임하고, 항목당 재검증
 * 횟수는 여기서 센다(상태가 아니라 항목 단위 카운트이므로).
 *
 * 생성은 @Builder로 한다(SessionService.create 참고). 접근자는 @Getter(fluent) — 이
 * 코드베이스의 record 스타일(x())과 맞춘다. createdAt/updatedAt은 BaseEntity가 자동 관리.
 */
@Entity
@Table(name = "sessions")
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)   // JPA 전용
@AllArgsConstructor(access = AccessLevel.PRIVATE)    // @Builder 전용
@Builder
public class Session extends BaseEntity {

    @Id
    private String id;

    @Column(nullable = false)
    private String productId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Channel channel;          // 대면 | 모바일 | TM

    @Column(nullable = false)
    private String ageBand;           // 연령대(비식별)

    private String experienceLevel;   // 투자 경험 수준 — nullable(@Column 기본값)
    private String amountBand;         // 가입금액대 — nullable(@Column 기본값)
    private String contractRef;        // 계약건 참조번호(비식별) — nullable(@Column 기본값)

    /**
     * 이 세션을 진행한 창구 직원과 그 지점. rbac_policy.yaml 의 scope 를 평가할 근거다 —
     * own_session 은 sellerId 를, branch 는 branchId 를 행위자와 비교한다.
     *
     * <p><b>인증 주체에서만 채워진다</b>(CurrentActor). 요청 본문에 이 필드가 없는 것이
     * 요지다 — 본문으로 받으면 자기가 아닌 사람을 소유자로 적을 수 있고 own_session 이
     * 견제가 아니라 자기 신고가 된다.
     *
     * <p>계정 분리(결정 10.5) 전에는 null 이고, 그러면 정책이 "판단할 수 없다" 로 <b>거부</b>
     * 한다 — 통과가 아니라 거부라 안전한 방향이다. 지금 필드를 두는 이유는, 계정이 생긴 뒤에
     * 붙이면 <b>그 사이 세션이 영원히 주인 없는 상태</b>로 남아 아무도 못 읽게 되기 때문이다.
     */
    private String sellerId;           // nullable — 10.5 전까지
    private String branchId;           // nullable — 10.5 전까지
    private String surveySchemaVersion; // 적합성 설문 문항 세트 버전 — 리포트가 어느 세트로 받았는지 안다(F-GTE-004)

    @Convert(converter = JsonMapConverter.class)
    @Column(columnDefinition = "TEXT")
    @Builder.Default
    private Map<String, Object> surveyResult = Map.of();   // 적합성 설문 결과, JSON 저장

    /**
     * 지금 진행 중인 재설명(F-INT-004 · 이슈 #415). {@code {itemId, content, reverifyQuestion}}.
     *
     * <p>화면이 <b>저장소 인계에 기대지 않고</b> 서버에서 다시 읽게 하려면 여기 있어야 한다.
     * S-02 가 고객 화면을 새 창(window.open)으로 열면서 sessionStorage 인계가 창을 못 건넌다 —
     * 새 창은 재설명을 못 받아 정상 흐름으로 떨어지고, <b>엉뚱한 항목의 판정이 에러 없이
     * 재검증으로 기록</b>된다. GET 이 이 값을 돌려주면 새 창·새로고침·다른 기기 모두 서버가 출처다.
     *
     * <p>❗<b>GET 이 ai-service 로 재생성하지 않고 이 저장값을 돌려주는 이유</b>: 재설명 문면은
     * LLM 산출물이라 비결정이다(P1). 재생성하면 판매자가 실제로 띄운 문장과 달라진다 — 그래서
     * POST /re-explain 이 <b>보여준 그 문면</b>을 여기 남기고 GET 은 그것만 읽는다.
     * 사이클(RE_EXPLAIN·RE_VERIFY)을 벗어나면 무효다({@link #inReExplainCycle()}).
     */
    @Convert(converter = JsonMapConverter.class)
    @Column(columnDefinition = "TEXT")
    @Builder.Default
    private Map<String, Object> currentReExplanation = Map.of();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private SessionState state = SessionState.CREATED;      // 생성 시 기본값

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "session_reverify", joinColumns = @JoinColumn(name = "session_id"))
    @MapKeyColumn(name = "item_id")
    @Column(name = "reverify_count")
    @Builder.Default
    private Map<String, Integer> reverifyCounts = new HashMap<>();

    /**
     * 항목별 **마스킹된** 발화(F-DET-002 입력). 원문은 저장하지 않는다.
     *
     * 모순 판정은 항목 하나가 아니라 세션 전체 발화가 입력이라(suitability_mismatch.schema.json)
     * 판정 시점에 지난 답변이 남아 있어야 한다. 여기 없으면 판정기에 넣을 게 근거 스팬뿐인데,
     * 스팬은 루브릭에 걸린 조각이라 "사실 원금 잃으면 안 돼요" 같은 모순 문장이 통째로 빠진다 —
     * 그러면 모순이 없어서가 아니라 안 보여서 insufficient_input 이 나온다.
     *
     * 마스킹된 값만 들어온다. 마스킹은 AiServiceClient 경계 안에서만 일어나고(P3),
     * 이 필드는 그 결과를 받아 적을 뿐 mask() 를 새로 부르지 않는다.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "session_utterance", joinColumns = @JoinColumn(name = "session_id"))
    @MapKeyColumn(name = "item_id")
    @Column(name = "masked_text", columnDefinition = "TEXT")
    @Builder.Default
    private Map<String, String> maskedUtterancesByItem = new HashMap<>();

    /**
     * 항목별로 <b>고객에게 실제로 보여준 질문</b>(F-INT-002). 채점이 같은 문면을 쓰게 하려고 남긴다.
     *
     * <p>질문은 ai-service 가 매번 생성하므로 <b>같은 항목이라도 호출마다 다르다.</b> 저장하지
     * 않으면 채점 시점에 재현할 방법이 없어서, 고객이 답한 질문과 채점에 넘어간 질문이 갈린다 —
     * 루브릭 기반이라 명백한 오해는 그대로 잡히지만 경계 사례에서 맥락이 어긋난다. 그리고
     * 그 어긋남은 <b>근거(evidence)가 "묻지 않은 질문에 대한 답"을 인용하게</b> 만드는데,
     * 인용 대조는 답변만 보므로 못 잡고 리포트까지 그대로 간다.
     *
     * <p>{@code EAGER} 인 이유: 채점 경로가 {@code SessionService.get()} 이 돌려준
     * <b>분리된(detached) 엔티티</b>에서 이 맵을 읽는다. LAZY 로 바꾸면
     * {@code LazyInitializationException} 이 난다 — {@code AskedQuestionTest} 가 잡긴 하지만
     * 이유를 적어두면 거기까지 안 간다.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "session_asked_question", joinColumns = @JoinColumn(name = "session_id"))
    @MapKeyColumn(name = "item_id")
    @Column(name = "question", columnDefinition = "TEXT")
    @Builder.Default
    private Map<String, String> askedQuestionsByItem = new HashMap<>();

    /**
     * 그 항목에 마지막으로 보여준 질문의 <b>출처</b> (이슈 #234 · #274).
     *
     * <p>❗<b>불리언이 아니라 출처를 그대로 담는다.</b> 처음엔 "템플릿 폴백인가" 한 비트였는데
     * (#265) 재검증 질문이 들어오면서 세 번째 상태가 생겼다 — 불리언으로는 못 담는다.
     * 값을 그대로 두면 상태가 늘어도 이 자료구조가 안 바뀐다.
     *
     * <p>출처를 <b>질문을 만든 자리에서</b> 정해 넘긴다. 예전에는 컨트롤러가 저장된 값을 보고
     * 되유도했는데, 그러면 유도 규칙과 실제 경로가 갈릴 수 있다 — 실제로 재검증 경로가
     * {@code DISPLAYED} 로 유도되고 있었다.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "session_asked_source", joinColumns = @JoinColumn(name = "session_id"))
    @MapKeyColumn(name = "item_id")
    @Column(name = "source")
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private Map<String, EvidenceRecorder.QuestionSource> askedQuestionSourceByItem = new HashMap<>();

    /**
     * 항목별로 <b>이미 쓴 질문 유형</b>(situation·amount·condition). 물어본 순서를 지킨다.
     *
     * <p>❗<b>같은 항목을 두 번 물어보는 자리가 있다</b> — 재검증이다(F-INT-004). 그때
     * 유형까지 같으면 <b>같은 모양의 질문을 한 번 더 하는 것</b>이 되고, 그건 두 가지를
     * 동시에 깬다. 측정으로는 <i>같은 각도로 두 번 재는 것</i>이라 재검증이 새로 아는 것이
     * 없고, 기획서 7-4 로는 <b>판매자가 준비시킬 문항이 사실상 하나로 줄어든다</b> —
     * 문면이 매번 생성돼도 유형이 고정이면 대비할 수 있다.
     *
     * <p>ai-service 는 이 목록을 받아 <b>남은 유형에서 고른다</b>. 셋을 다 썼으면 전체로
     * 되돌린다(굶기지 않는다) — 인터뷰가 멈추는 것이 반복보다 나쁘다.
     *
     * <p><b>세션 단위가 아니라 항목 단위</b>다. 유형은 항목의 성격을 따라간다 —
     * {@code amount} 는 금액 조건이 있는 항목에서만 성립한다. 세션 전체로 배제하면
     * 뒤쪽 항목이 자기에게 맞는 유형을 못 쓴다.
     */
    @Convert(converter = StringListMapConverter.class)
    @Column(name = "asked_types", columnDefinition = "TEXT")
    @Builder.Default
    private Map<String, List<String>> askedTypesByItem = new LinkedHashMap<>();

    // 항목별 최신 판정(AI 측정값). 게이트 판정 입력으로 쓰인다. JSON 저장.
    @Convert(converter = JudgmentMapConverter.class)
    @Column(columnDefinition = "TEXT")
    @Builder.Default
    private Map<String, Judgment> judgmentsByItem = new HashMap<>();

    /**
     * 재검증에서 <b>직전과 사실상 같은 답을 냈는데 등급이 올라간</b> 항목 (이슈 #268 (d)).
     *
     * <p>❗<b>이 상태가 없으면 게이트가 GREEN 을 낸다.</b> 최종 등급만 남으므로 전 항목이
     * U1 이 되고 {@code R-06} 이 문다 — 재설명이 이해를 올린 것이 아니라 <b>채점이 흔들린
     * 것</b>인데 통과한다. 그리고 판정이 서면 {@code JUDGED} 에서 나가는 전이가
     * {@code CLOSE} 뿐이라 되돌릴 수 없다.
     *
     * <p><b>등급을 고치지 않는다</b>(P1). 측정은 그대로 두고 <b>게이트에 입력을 하나 더
     * 준다</b> — 판정은 룰이 한다. 항목 ID 를 담는 이유는 건수만으로는 <i>"어느 항목이
     * 그랬나"</i> 에 답할 수 없어서다.
     */
    @Convert(converter = StringListConverter.class)
    @Column(name = "repeated_answer_items", columnDefinition = "TEXT")
    @Builder.Default
    private List<String> repeatedAnswerUpgradedItems = new ArrayList<>();

    /**
     * 항목별 <b>답변 입력에 걸린 시간(ms)</b> — 기획 7-4 2단계 ③ (응답 지연 분포).
     *
     * <p>❗<b>불변 기록에만 있고 세션에는 없었다.</b> {@code #340} 이 {@code inputMeta} 를
     * evidence 로 보내는데 집계는 {@code evidence/} 를 안 연다({@code #327} — 인메모리라
     * 재기동마다 사라진다). 그래서 기획서가 이름으로 든 세 신호 중 <b>이것만 셀 수 없었다.</b>
     *
     * <p>❗<b>총 입력 시간 하나만 남긴다.</b> {@code inputMeta} 전체(첫 타건 지연·백스페이스
     * 횟수·붙여넣기 여부)를 복사하면 <b>불변 기록과 두 벌</b>이 되고, 갈리면 어느 쪽이 참인지
     * 알 수 없다. 집계가 답해야 하는 질문 하나에 필요한 값만 든다.
     *
     * <p>화면에 안 나간다 — {@code JudgmentView} 에 필드가 없다(기획 7-4 · {@code #144}).
     */
    @Convert(converter = LongMapConverter.class)
    @Column(name = "input_ms", columnDefinition = "TEXT")
    @Builder.Default
    private Map<String, Long> inputMsByItem = new LinkedHashMap<>();

    /**
     * 적합성 설문 vs 발화 모순 판정 상태(F-DET-002).
     *
     * 불리언이 아닌 이유는 SuitabilityStatus 주석에 있다 — "모순 없음" 과 "판정하지 못함" 이
     * 같은 값이 되면 게이트가 후자를 GREEN 으로 흘린다.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private SuitabilityStatus suitabilityStatus = SuitabilityStatus.NOT_EVALUATED;

    // F-DET-002 코칭 메타 — 취약 가중치 합산 점수·취약 여부. 게이트 신호 아님(코칭·리포트용).
    @Column(nullable = false)
    @Builder.Default
    private int coachingScore = 0;

    @Column(nullable = false)
    @Builder.Default
    private boolean vulnerable = false;

    // 판정 시점의 게이트 결과 기록(F-GTE-004 감사 기준점 — 재계산값이 아니라 기록값).
    @Enumerated(EnumType.STRING)
    private Signal gateSignal;              // 판정 전이면 null

    // 발화 룰(ID + 문면). 문면까지 남기는 이유는 RuleRefListConverter 주석에 있다 — 판정 뒤에
    // gate_rules.yaml 이 바뀌면 재계산으로는 그때의 말이 안 나온다 (이슈 #320).
    @Convert(converter = RuleRefListConverter.class)
    @Column(columnDefinition = "TEXT")
    private List<RuleRef> gateRuleTrace;

    // 판정 시점의 미측정 항목 수와 룰셋 버전. 신호·트레이스만 남기면 "R-00 이 물었다"까지만
    // 알고 몇 개를 못 쟀는지도 어느 룰셋으로 쟀는지도 모르는 기록이 된다 — 판정 뒤에는
    // 재계산해도 그때의 값이 안 나오므로(항목이 더 채점될 수 있다) 여기 같이 남긴다.
    @Builder.Default
    private int gateUnmeasured = 0;
    @Builder.Default
    private int gateRulesVersion = 0;

    private Instant judgedAt;              // 판정 시각

    // F-GTE-002 적색 오버라이드 — 적색 판정 세션을 관리자 승인으로 진행한 사실·사유·승인자.
    // 게이트 신호는 그대로 RED로 남긴다(오버라이드는 신호를 바꾸는 게 아니라 진행을 예외 허가한다).
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private OverrideStatus overrideStatus = OverrideStatus.NONE;
    private String overrideReason;        // 판매자가 적은 진행 사유(30자 이상) — 승인 전에도 기록
    private String overrideApprover;      // 승인한 MGR 식별자(ADR-002) — 승인 전이면 null
    private Instant overrideDecidedAt;    // 승인 시각 — 승인 전이면 null

    /**
     * 세션 생성 팩토리. ID 발급·기본 상태·설문 null 방어 등 생성 불변식은 도메인이 소유한다.
     * (서비스는 이 결과를 저장만 한다.)
     */
    public static Session create(CreateSessionCommand cmd) {
        return Session.builder()
                .id(UUID.randomUUID().toString())
                .productId(cmd.productId())
                .channel(cmd.channel())
                .ageBand(cmd.ageBand())
                .experienceLevel(cmd.experienceLevel())
                .amountBand(cmd.amountBand())
                .contractRef(cmd.contractRef())
                .sellerId(cmd.sellerId())
                .branchId(cmd.branchId())
                .surveySchemaVersion(cmd.surveySchemaVersion())
                .surveyResult(nfcSurvey(cmd.surveyResult()))
                .build();
    }

    /**
     * 설문 결과의 문자열 값을 NFC 로 고정한다 (정본 경계, 결정 10.2.1 · 이슈 #466 NFC 축).
     *
     * <p>❗<b>surveyResult 는 발화와 달리 여기서 정규화한다.</b> 발화(answerText)는 {@code Session}
     * 에 원문을 안 남기고 {@code AiServiceClient} 로 나가는 경계에서 NFC 를 걸지만(#469), surveyResult
     * 는 <b>세션에 저장</b>되고 그대로 불변 기록(appendMismatch)까지 내려간다. 10.2.1 이 정한
     * 대로 <i>"Spring 안에 원문을 저장하면 정규화를 요청 진입점으로 올린다"</i> — 이 도메인 진입
     * choke point 가 그 자리다. 안 하면 조합형(NFD) 설문 답이 그대로 해시되고, 영속 DB(#445)
     * 이후엔 같은 답이 환경마다 다른 contentHash 를 내 교차검증이 깨진다(못 고친다).
     *
     * <p>값의 <b>표현만</b> 바꾸는 #482(Double→BigDecimal, recorder)와 달리 이건 내용(바이트)을
     * 바꾸므로 recorder 가 아니라 <b>입력 경계</b>에서 한다 — CanonicalJson 이 정규화를 일부러
     * 안 하는 이유와 같은 결이다. 문자열 아닌 값(숫자·불리언)은 그대로 둔다.
     */
    private static Map<String, Object> nfcSurvey(Map<String, Object> survey) {
        if (survey == null || survey.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        survey.forEach((k, v) -> out.put(k,
                v instanceof String s ? java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFC) : v));
        return Map.copyOf(out);
    }

    /** 상태 전이. 불법 전이면 SessionFsm이 예외를 던진다. */
    public SessionState fire(SessionFsm.Event event) {
        this.state = SessionFsm.next(this.state, event);
        return this.state;
    }

    /** 항목의 재검증 시도를 1 증가시키고 누적 횟수를 반환한다. */
    public int recordReverify(String itemId) {
        return reverifyCounts.merge(itemId, 1, Integer::sum);
    }

    public int reverifyCount(String itemId) {
        return reverifyCounts.getOrDefault(itemId, 0);
    }

    /** 항목이 재검증 상한에 도달했는지 — 도달 시 재설명 루프 대신 판정으로 가야 한다. */
    public boolean reverifyExhausted(String itemId, int max) {
        return reverifyCount(itemId) >= max;
    }

    /** 항목별 최신 판정을 기록(재검증 시 덮어씀). 발화가 없는 경로용. */
    public void recordJudgment(Judgment judgment) {
        recordAnswer(judgment.itemId(), null, judgment);
    }

    /**
     * 발화와 판정을 <b>한 자리에서</b> 기록한다.
     *
     * <p>❗둘을 따로 부르면 이 메서드가 재려는 것을 잴 수 없다. <b>직전 발화와 직전 등급이
     * 둘 다 아직 남아 있는 순간은 여기 한 번뿐</b>이고, 어느 한쪽을 먼저 덮어쓰면 비교
     * 대상이 사라진다. 실제로 예전에는 {@code recordUtterance} 가 먼저 불려서 직전 발화가
     * 이미 지워진 뒤에 판정이 들어왔다.
     */
    public void recordAnswer(String itemId, String maskedAnswer, Judgment judgment) {
        recordAnswer(itemId, maskedAnswer, judgment, null);
    }

    /**
     * 입력 시간까지 같이 남긴다 (기획 7-4 2단계 ③).
     *
     * <p>{@code inputMeta} 가 없으면 <b>안 적는다</b> — 0 으로 적으면 <i>"즉답"</i> 과
     * <i>"안 보냈다"</i> 가 같아진다. 화면이 옛 버전이거나 스크립트로 들어온 답변이 그 경우다.
     */
    public void recordAnswer(String itemId, String maskedAnswer, Judgment judgment,
                             InputMeta inputMeta) {
        if (inputMeta != null) {
            inputMsByItem.put(itemId, inputMeta.totalInputMs());
        }
        Judgment prior = judgmentsByItem.get(itemId);
        String priorAnswer = maskedUtterancesByItem.get(itemId);
        // 등급이 **올라간** 경우만 본다(U1 이 제일 좋다 = ordinal 이 작다). 같거나 내려간
        // 것은 이미 R-04·R-03 이 받는다 — 여기서 잡으려는 것은 **통과로 새는 방향**이다.
        if (prior != null && maskedAnswer != null
                && judgment.grade().ordinal() < prior.grade().ordinal()
                && AnswerRepetition.essentiallySame(priorAnswer, maskedAnswer)
                && !repeatedItems().contains(itemId)) {
            repeatedItems().add(itemId);
        }
        if (maskedAnswer != null) {
            maskedUtterancesByItem.put(itemId, maskedAnswer);
        }
        judgmentsByItem.put(judgment.itemId(), judgment);
    }

    /**
     * 집계 입력용 — 항목별 입력 시간(ms). 없으면 빈 맵.
     *
     * <p>❗<b>0 이 아니라 없는 것</b>이 정상 상태다. 화면이 안 보낸 답변은 여기 안 들어온다.
     */
    public Map<String, Long> inputMsByItem() {
        return Map.copyOf(inputMsByItem);
    }

    /**
     * 게이트 입력용 — 같은 답을 되풀이했는데 등급이 올라간 항목 수 (이슈 #268 (d)).
     */
    public int repeatedAnswerUpgradedCount() {
        return repeatedItems().size();
    }

    /**
     * ❗{@link StringListConverter} 는 빈 목록을 {@code null} 로 되돌린다 — 판정 전 세션의
     * 룰 트레이스를 빈 목록과 구분하려는 규약이다. 그 규약을 이 필드가 그대로 물려받으므로
     * <b>DB 를 한 번 다녀온 세션에서는 여기가 null</b> 이다. 초기화를 미룬다.
     */
    private List<String> repeatedItems() {
        if (repeatedAnswerUpgradedItems == null) {
            repeatedAnswerUpgradedItems = new ArrayList<>();
        }
        return repeatedAnswerUpgradedItems;
    }

    /** 고객에게 보여준 질문을 항목별로 기록(재질문 시 덮어씀 — 마지막에 보여준 것이 답의 맥락이다). */
    /**
     * 그 항목에 보여준 질문과 <b>그 문면의 출처</b>를 같이 남긴다.
     *
     * <p>둘을 한 메서드로 묶어 두는 이유는 <b>따로 쓰는 경로를 안 만들려는 것</b>이다 —
     * 문면은 새것인데 출처가 옛것으로 남으면 아예 안 남기는 것보다 나쁘다.
     */
    public void recordAskedQuestion(String itemId, String question, String questionType,
                                    EvidenceRecorder.QuestionSource source) {
        askedQuestionsByItem.put(itemId, question);
        askedQuestionSourceByItem.put(itemId, source);
        if (questionType != null && !questionType.isBlank()) {
            // ❗**같은 유형을 두 번 적지 않는다.** 목록은 "무엇을 이미 썼나" 이지 몇 번
            // 썼나가 아니고, 중복이 쌓이면 ai-service 의 배제 계산은 그대로인데 프롬프트에
            // 실리는 문면만 길어진다. 순서는 유지한다.
            List<String> used = askedTypesByItem.computeIfAbsent(itemId, k -> new ArrayList<>());
            if (!used.contains(questionType)) {
                used.add(questionType);
            }
        }
    }

    /** 그 항목에 이미 쓴 질문 유형(물어본 순서). 없으면 빈 목록. */
    public List<String> askedTypes(String itemId) {
        return List.copyOf(askedTypesByItem.getOrDefault(itemId, List.of()));
    }

    /**
     * 지금 보여준 재설명 문면을 세션에 남긴다(#415) — GET /re-explanations/current 가 이 값을
     * 돌려준다. 재생성이 아니라 이 저장값을 읽는 이유는 {@link #currentReExplanation} javadoc 참고.
     */
    public void recordReExplanation(String itemId, String content, String reverifyQuestion) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("itemId", itemId);
        snapshot.put("content", content);
        snapshot.put("reverifyQuestion", reverifyQuestion);
        this.currentReExplanation = snapshot;
    }

    /**
     * 재설명 사이클(RE_EXPLAIN·RE_VERIFY) 안인가 — 이때만 {@link #currentReExplanation} 이
     * 유효하다. 재검증을 통과해 IN_PROGRESS 로 돌아갔거나 판정으로 넘어갔으면, 저장값이 남아
     * 있어도 "지금 진행 중인 재설명"은 없다. GET 이 이 경계로 404 를 가른다.
     */
    public boolean inReExplainCycle() {
        return state == SessionState.RE_EXPLAIN || state == SessionState.RE_VERIFY;
    }

    /**
     * 질문 생성에 넘길 면담 맥락 (F-INT-002).
     *
     * <p>❗<b>정답이 될 값을 안 담는다.</b> 등급과 오해 유형 ID 뿐이고 발화·루브릭·조건
     * 원문은 안 간다 — 그건 질문에서 걸러내는 바로 그 값이라, 맥락으로 넣으면 다음 질문에
     * 옮겨 쓰인다.
     *
     * <p>{@code exceptItem} 은 <b>지금 물으려는 항목</b>이다. 그 항목의 앞선 판정은 빼고
     * 넘긴다 — 재검증에서 자기 직전 등급을 맥락으로 주면 <i>"방금 U3 였다"</i> 가 질문에
     * 실려 <b>고객이 자기 점수를 알게 된다.</b>
     */
    public java.util.List<Grade> priorGrades(String exceptItem) {
        return judgmentsByItem.entrySet().stream()
                .filter(e -> !e.getKey().equals(exceptItem))
                .map(e -> e.getValue().grade())
                .toList();
    }

    /** 이 면담에서 이미 걸린 오해 유형 ID. 중복은 접는다 — 몇 번인지는 질문이 안 쓴다. */
    public java.util.List<String> matchedMisconceptions(String exceptItem) {
        return judgmentsByItem.entrySet().stream()
                .filter(e -> !e.getKey().equals(exceptItem))
                .map(e -> e.getValue().misconceptionType())
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
    }

    /**
     * <b>기대 항목 중 판정이 없는 항목 수</b> (이슈 #280 ② · #405). {@code R-00} 의 분모다.
     *
     * <p>분모({@code expectedItemIds})는 <b>그 상품의 추출 항목 집합</b>이다 —
     * {@code ProductRiskItems.riskItemsOf(productId)} 가 낸다(저장된 추출 우선, 없으면 MockData
     * 폴백). 세션은 그 목록을 모르므로 <b>호출부가 넣어 준다</b>({@code SessionService}). 세션은
     * "무엇을 판정했나" 만 알고, "몇 항목이어야 하나" 는 상품 쪽이 안다 — 둘을 여기서 맞춘다.
     *
     * <p>❗<b>전에는 분모가 "질문을 보낸 항목" 이었다.</b> 추출이 아직 목이던 시절의 우회다
     * (#405). 그러면 <b>아예 안 물어본 항목</b>(질문 생성 실패·항목 누락·순회 중단)이 분모에서
     * 같이 빠져 안 잡혔다 — 13항목 중 3개에 질문을 못 보냈으면 게이트는 10개만 보고 그 10개가
     * U1 이면 R-06 이 GREEN 을 냈다. 이제 기대 집합으로 세므로 <b>안 물어본 항목도 미측정으로
     * 잡힌다.</b> 물어봤는데 못 잰 것(채점 502·무응답)은 이 집합의 부분집합이라 그대로 걸린다.
     *
     * <p>가려야 하는 것은 <i>"쟀는데 통과"</i> 와 <i>"못 쟀는데 통과"</i> 이고 그건 {@code R-00}
     * 이 한다 — 게이트는 왜 못 쟀는지(안 물음·채점 실패·무응답)를 가릴 필요가 없다.
     */
    public int unmeasuredItemCount(java.util.Collection<String> expectedItemIds) {
        java.util.Set<String> judged = judgments().stream()
                .map(Judgment::itemId).collect(java.util.stream.Collectors.toSet());
        return (int) expectedItemIds.stream().distinct()
                .filter(id -> !judged.contains(id)).count();
    }

    /** 그 항목에 마지막으로 보여준 질문의 출처. 보여준 적이 없으면 {@code null}. */
    public EvidenceRecorder.QuestionSource askedQuestionSource(String itemId) {
        return askedQuestionSourceByItem.get(itemId);
    }

    /** 그 항목에 실제로 보여준 질문. 없으면 null — 화면을 거치지 않고 답변이 들어온 경우다. */
    public String askedQuestion(String itemId) {
        return askedQuestionsByItem.get(itemId);
    }

    /** 마스킹된 발화를 항목별로 기록(재검증 시 덮어씀). */
    public void recordUtterance(String itemId, String maskedText) {
        maskedUtterancesByItem.put(itemId, maskedText);
    }

    /** F-DET-002 입력용 — 항목별 마스킹 발화(항목 순서 무관, 없으면 빈 맵). */
    public Map<String, String> maskedUtterances() {
        return Map.copyOf(maskedUtterancesByItem);
    }

    /** 게이트 입력용 — 항목별 최신 판정 목록. */
    public List<Judgment> judgments() {
        return new ArrayList<>(judgmentsByItem.values());
    }

    /** 항목의 최신 판정(없으면 null). */
    public Judgment judgmentFor(String itemId) {
        return judgmentsByItem.get(itemId);
    }

    /**
     * 게이트 입력용 — '재검증 실패' 최대 횟수(R-03 판단).
     * 재검증했지만 여전히 이해(U1)에 도달하지 못한 항목의 재검증 횟수만 센다.
     * (2회 재검증 후 U1이 됐다면 실패가 아니므로 0으로 친다.)
     */
    public int failedReverifyCount() {
        int max = 0;
        for (var entry : reverifyCounts.entrySet()) {
            Judgment j = judgmentsByItem.get(entry.getKey());
            boolean understood = j != null && j.grade() == Grade.U1;
            if (!understood) {
                max = Math.max(max, entry.getValue());
            }
        }
        return max;
    }

    /** F-DET-002 모순 판정 결과 반영. ai-service 의 status·mismatch 를 그대로 옮긴다. */
    public void recordSuitability(SuitabilityStatus status) {
        // null 을 넣으면 이후 suitabilityMismatch()/suitabilityUnknown() 이 NPE 로 터지는데,
        // 그 자리는 판정 한참 뒤라 원인이 안 보인다. 넣는 자리에서 막는다.
        java.util.Objects.requireNonNull(status, "적합성 판정 상태는 null 일 수 없다");
        this.suitabilityStatus = status;
    }

    /**
     * 게이트 입력 — 모순이 **확인됐는가**(R-02).
     *
     * 판정하지 못한 경우(UNKNOWN)는 false 다. 그 false 를 "적합" 으로 읽으면 안 되며,
     * 그건 suitabilityUnknown() 이 별도로 답한다.
     */
    /** 적합성 모순을 아직 판정하지 않았는지 — 판정 직전에 한 번만 부르기 위한 조건. */
    public boolean suitabilityNotEvaluated() {
        return suitabilityStatus == SuitabilityStatus.NOT_EVALUATED;
    }

    public boolean suitabilityMismatch() {
        return suitabilityStatus.isMismatch();
    }

    /** 게이트 입력 — 판정을 시도했으나 확인하지 못했는가(R-02b, 결정 10.9). */
    public boolean suitabilityUnknown() {
        return suitabilityStatus.isUnknown();
    }

    /** F-DET-002 코칭 스코어·취약 여부 반영(세션 메타). */
    public void applyCoaching(int score, boolean vulnerable) {
        this.coachingScore = score;
        this.vulnerable = vulnerable;
    }

    /** 판정 시점의 게이트 결과를 기록한다(감사 기준점, F-GTE-004). */
    public void recordGate(GateResult result, Instant judgedAt) {
        this.gateSignal = result.signal();
        this.gateRuleTrace = List.copyOf(result.ruleTrace());
        this.gateUnmeasured = result.unmeasured();
        this.gateRulesVersion = result.rulesVersion();
        this.judgedAt = judgedAt;
    }

    /** 게이트 판정이 적색인지 — 오버라이드는 적색 세션에만 허용된다(F-GTE-002). */
    public boolean isRedGate() {
        return gateSignal == Signal.RED;
    }

    /** F-GTE-002 오버라이드 요청 — 사유를 기록하고 승인 대기로 둔다. 적색 가드는 서비스가 건다. */
    public void requestOverride(String reason) {
        this.overrideStatus = OverrideStatus.PENDING_APPROVAL;
        this.overrideReason = reason;
    }

    /** F-GTE-002 오버라이드 승인 — 승인자·시각을 기록한다(불변 기록은 evidence로 별도 append). */
    public void approveOverride(String approver, Instant at) {
        this.overrideStatus = OverrideStatus.APPROVED;
        this.overrideApprover = approver;
        this.overrideDecidedAt = at;
    }
}
