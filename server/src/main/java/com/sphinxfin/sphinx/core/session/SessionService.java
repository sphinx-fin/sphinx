package com.sphinxfin.sphinx.core.session;

import com.sphinxfin.sphinx.domain.SuitabilityMismatch;
import com.sphinxfin.sphinx.domain.GateResult;
import com.sphinxfin.sphinx.domain.Grade;
import com.sphinxfin.sphinx.domain.InputMeta;
import com.sphinxfin.sphinx.domain.Judgment;
import com.sphinxfin.sphinx.domain.RiskItem;
import com.sphinxfin.sphinx.domain.SessionState;
import com.sphinxfin.sphinx.domain.SuitabilityStatus;
import com.sphinxfin.sphinx.domain.RuleRef;
import com.sphinxfin.sphinx.domain.Signal;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import com.sphinxfin.sphinx.core.EvidenceRecorder;
import com.sphinxfin.sphinx.core.aiservice.AiServiceClient;
import com.sphinxfin.sphinx.core.gate.GateEngine;

/**
 * F-INT-001/004 세션 서비스. 소유: 강희진
 *
 * 세션 생명주기 오케스트레이션: 생성·조회, 답변 기록(+상태전이), 재설명·재검증 루프,
 * 게이트 판정, 중단. 생성 불변식은 Session.create, 판정은 GateEngine, 취약 가중은
 * CoachingScoreService가 소유하고 서비스는 조율만 한다.
 */
@Service
public class SessionService {

    private final SessionRepository repository;
    private final GateEngine gateEngine;
    private final CoachingScoreService coachingScoreService;
    private final EvidenceRecorder evidenceRecorder;
    private final AiServiceClient aiServiceClient;
    private final ApplicationEventPublisher events;
    /**
     * 항목당 재검증 상한. <b>게이트 룰에서 읽는다</b>(이슈 #66).
     *
     * <p>전에는 {@code application.yml sphinx.scoring.max-reverify} 였고, 같은 숫자가
     * {@code gate_rules.yaml R-03}({@code reverifyFailed >= N})에도 있었다. 둘이 어긋나면
     * 상한과 게이트가 따로 논다 — 대조 테스트로 막고 있었지만 <b>그건 어긋난 뒤에 잡는
     * 것</b>이고, 고칠 자리가 둘이면 언젠가 한 곳만 바뀐다.
     *
     * <p>ADR-005 가 임계값의 단일 출처를 {@code gate_rules.yaml} 로 정해 뒀으니 그쪽으로
     * 접었다. 룰이 숫자를 소유하고 여기서 읽는다.
     */
    private final int maxReverify;

    /**
     * evidenceRecorder는 Optional 주입 — evidence/ 구현(F-GTE-004)이 등록되기 전에도
     * 세션 루프가 돌아야 하므로, 없으면 NO_OP으로 대체한다.
     *
     * aiServiceClient 는 재설명 콘텐츠 생성(F-INT-004)에 쓴다 — 눈높이 재설명은 룰이 아니라
     * 측정 기반 생성이므로 서비스가 조율만 하고 문면은 ai-service 가 만든다(P1: 판정 아님).
     */
    public SessionService(SessionRepository repository,
                          GateEngine gateEngine,
                          CoachingScoreService coachingScoreService,
                          Optional<EvidenceRecorder> evidenceRecorder,
                          AiServiceClient aiServiceClient,
                          ApplicationEventPublisher events) {
        this.repository = repository;
        this.gateEngine = gateEngine;
        this.coachingScoreService = coachingScoreService;
        this.evidenceRecorder = evidenceRecorder.orElse(EvidenceRecorder.NO_OP);
        this.aiServiceClient = aiServiceClient;
        this.events = events;
        this.maxReverify = gateEngine.reverifyThreshold();
    }

    public Session create(CreateSessionCommand cmd) {
        Session session = Session.create(cmd);
        // 생성 시점 취약 가중 산출(모순은 아직 없음).
        var coaching = coachingScoreService.score(session, false);
        session.applyCoaching(coaching.score(), coaching.vulnerable());
        return repository.save(session);
    }

    /** 세션 조회. 없으면 예외(→ 404). */
    public Session get(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("세션을 찾을 수 없다: " + id));
    }

    /**
     * 항목별 판정(AI 측정값)을 세션에 기록한다. 상태에 따라:
     * - CREATED: 첫 답변이므로 인터뷰 시작(→IN_PROGRESS)
     * - RE_EXPLAIN: 재설명 후 재답변이므로 재검증(재검증 횟수 +1, →RE_VERIFY).
     *   이번에 이해(U1)했으면 정상 흐름으로 복귀(→IN_PROGRESS).
     */
    @Transactional
    public Judgment recordJudgment(String sessionId, Judgment judgment) {
        return recordJudgment(sessionId, judgment, null);
    }

    /**
     * 마스킹된 발화까지 함께 기록한다. 발화는 F-DET-002(세션 전체 발화 입력)에서 다시 쓴다.
     * maskedAnswer 가 null 이면 발화를 남기지 않는다 — 판정만 넣는 기존 경로용이다.
     */
    @Transactional
    public Judgment recordJudgment(String sessionId, Judgment judgment, String maskedAnswer) {
        return recordJudgment(sessionId, judgment, maskedAnswer, null,
                EvidenceRecorder.QuestionSource.DISPLAYED);
    }

    /**
     * 채점에 쓴 질문까지 함께 기록한다.
     *
     * <p>❗{@code askedQuestion} 은 <b>호출자가 넘긴다</b> — 여기서 세션 맵을 다시 읽지 않는다.
     * 읽으면 값을 두 곳에서 따로 구하게 되고, 폴백처럼 한쪽에만 있는 분기가 생기는 순간
     * <b>채점한 질문과 기록한 질문이 갈린다</b>. 실제로 그랬다 — 세션 맵이 비면 채점은 목
     * 문면으로 떨어지는데 기록은 null 이었고, 그러면 null 이 "필드 이전 레코드" 와
     * "폴백이었다" 두 뜻을 갖는다(#137 리뷰). append-only 라 섞인 뒤에는 못 가른다.
     */
    @Transactional
    public Judgment recordJudgment(String sessionId, Judgment judgment, String maskedAnswer,
                                   String askedQuestion,
                                   EvidenceRecorder.QuestionSource questionSource) {
        return recordJudgment(sessionId, judgment, maskedAnswer, askedQuestion, questionSource, null);
    }

    /**
     * F-INT-003 입력 메타데이터를 같이 기록한다 (이슈 #325).
     *
     * <p>지금까지 {@code AnswerRequest} 가 받아서 <b>버렸다</b> — 화면은 매 답변마다 실어
     * 보내는데 서버 쪽 참조가 DTO 선언 두 줄뿐이었다. {@code evidence/} 는 append-only 라
     * 늦을수록 복구가 안 된다.
     *
     * <p>❗<b>게이트에 안 물린다.</b> 측정이지 판정이 아니다(P1) — 기록에 남기고 집계에서
     * 본다. 그리고 판매자 화면에 안 나간다({@link com.sphinxfin.sphinx.domain.InputMeta}).
     */
    @Transactional
    public Judgment recordJudgment(String sessionId, Judgment judgment, String maskedAnswer,
                                   String askedQuestion,
                                   EvidenceRecorder.QuestionSource questionSource,
                                   InputMeta inputMeta) {
        Session session = get(sessionId);
        if (maskedAnswer != null) {
            session.recordUtterance(judgment.itemId(), maskedAnswer);
        }
        if (SessionFsm.canFire(session.state(), SessionFsm.Event.START)) {
            session.fire(SessionFsm.Event.START);
        }
        boolean reverifying = session.state() == SessionState.RE_EXPLAIN;
        if (reverifying) {
            session.recordReverify(judgment.itemId());
            session.fire(SessionFsm.Event.REVERIFY);
        }
        session.recordJudgment(judgment);
        if (reverifying && judgment.grade() == Grade.U1) {
            session.fire(SessionFsm.Event.RESUME);
        }
        repository.save(session);
        // 세션은 덮어쓰지만 이력은 남긴다 — 재설명 이력의 유일한 보존 경로(기획서 174행).
        // 같은 트랜잭션 안이다(2026-08-25 결정): append-only 해시 체인은 순서가 해시에
        // 들어가므로 구멍을 나중에 메울 수 없다. append 가 실패하면 세션 저장도 함께 롤백되고
        // 요청 전체가 실패한다 — 근거 없는 판정이 무효라면 기록 없는 판정도 무효다(P4와 같은 논리).
        // 그 판정을 만든 질문을 함께 남긴다 — 채점에 넘긴 값 그대로다(호출자가 준다).
        // 질문은 재질문 시 덮어쓰는 가변 테이블에만 있어서, 여기서 안 실으면 "어느 질문에
        // 대한 답을 잰 것인가" 에 답할 수 없다 (#136).
        evidenceRecorder.appendJudgment(
                sessionId, judgment, session.reverifyCount(judgment.itemId()),
                askedQuestion, questionSource, inputMeta, Instant.now());
        publishIfUnfairSales(sessionId, judgment);
        return judgment;
    }

    /**
     * F-GTE-003 — 판정에 불공정영업 신호가 있으면 COMPL 로 사건을 낸다 (이슈 #63).
     *
     * <p>불변 기록 append 뒤에 낸다. 통보가 먼저 나가고 기록이 롤백되면 컴플라이언스가
     * 근거 없는 알림을 받는다 — 같은 트랜잭션이라 append 가 실패하면 여기까지 안 온다.
     *
     * <p>❗<b>발행 여부를 판정 응답에 싣지 않는다.</b> 판매자가 그 필드를 보면 무엇이
     * 탐지되는지 알게 되고, 그러면 같은 영업을 문면만 바꿔서 한다(기획 7-4 역이용 방지).
     * 그래서 이 메서드는 값을 돌려주지 않는다 — 호출자가 실수로 응답에 실을 수 없다.
     *
     * <h2>❗{@code misconceptionType} 이 아니라 {@code escalate} 로 가른다 (이슈 #160)</h2>
     *
     * <p>예전에는 {@code UnfairSalesTypes.isSignal(judgment.misconceptionType())} 이었다.
     * 그 클래스가 자기 javadoc 에 <i>"이 클래스는 계약이 열리면 사라진다"</i> 고 적어 둔
     * 그 계약이 열렸다({@code judgment.schema.json} 의 {@code escalate} · PR #242).
     *
     * <p>유형ID 로 가르는 동안 <b>발행이 한 번도 일어나지 않았다.</b> 등급 상향은 루브릭의
     * {@code related_misconceptions} 로 거르는 것이 맞는데(다른 항목의 오해가 이 항목 등급을
     * 끌어내리면 안 된다) 그 필터가 <b>신호까지 같이 삼켰다</b> — 17개 루브릭 어디에도
     * {@code M08-TYING} 이 안 걸려 있어서 유형이 실릴 경로가 없었다. 탐지는 pattern 1.0
     * 만점인데 COMPL 발행은 0회였고, <b>아무도 안 걸리는 것이 탐지가 도는 것처럼 보였다.</b>
     *
     * <p>{@code escalate} 는 ai-service 가 <b>루브릭 필터 밖에서</b> 계산한다(PR #205 ·
     * {@code escalation_signal}). 등급과 신호는 묻는 것이 다르기 때문이다 — 등급은
     * <i>"이 고객이 이 항목을 이해했는가"</i>, 꺾기는 <i>"판매자가 무엇을 했는가"</i> 다.
     * 후자는 어느 위험항목을 채점 중이었는지와 무관하게 성립한다.
     *
     * <p>❗<b>서버에 유형 목록을 두지 않는다.</b> 근거는 오해 라이브러리의
     * {@code escalate: compliance} 하나이고, ai-service 가 그것을 읽어 이미 판단해 준다.
     * 서버가 목록을 다시 갖는 순간 유형이 승급될 때 두 곳을 고쳐야 하고, 한쪽만 고치면
     * <b>탐지는 되는데 발행이 안 되는</b> 지금 이 상태로 돌아온다.
     */
    private void publishIfUnfairSales(String sessionId, Judgment judgment) {
        if (!judgment.escalate()) {
            return;
        }
        events.publishEvent(new UnfairSalesSignalEvent(
                sessionId, judgment.itemId(), judgment.misconceptionType(),
                judgment.evidence().utteranceQuote(), Instant.now()));
    }

    /**
     * F-DET-002 모순 판정 반영. ai-service 의 status·mismatch 를 세션 상태로 옮기고
     * **코칭 스코어를 다시 계산한다.**
     *
     * 재계산이 필요한 이유: 생성 시점에는 모순을 모르므로 mismatch=false 로 점수를 냈다
     * (PR #24 의 한계). 모순이 확인되면 vulnerability_weights.yaml 의 mismatch-bonus 가
     * 붙어야 하는데, 안 붙이면 취약 임계값을 넘겨야 할 고객이 안 넘고 고령자 모드 재설명이
     * 안 걸린다 — 에러도 로그도 없이 시연만 밋밋해진다(결정 10.12 와 같은 실패 양식).
     *
     * UNKNOWN 일 때는 가산하지 않는다. 모순이 확인된 게 아니므로 코칭 가중의 근거가 없다 —
     * 게이트가 R-02b 로 황색을 내서 재확인을 요구하는 것이 그 상태에 맞는 처리다.
     */
    @Transactional
    public Session recordSuitability(String sessionId, SuitabilityMismatch mismatch) {
        Session session = get(sessionId);
        SuitabilityStatus status = mismatch.status();
        session.recordSuitability(status);
        var coaching = coachingScoreService.score(session, status.isMismatch());
        session.applyCoaching(coaching.score(), coaching.vulnerable());
        repository.save(session);
        // 세션 저장과 같은 트랜잭션이다(recordJudgment 와 같은 논리) — 근거 없는 판정이
        // 무효라면 기록 없는 판정도 무효다(P4). 판정을 만든 근거와 입력을 함께 남긴다:
        // 이 판정이 R-02·R-02b 로 게이트를 움직이는데 게이트 기록에는 ruleTrace 밖에 없어서
        // "왜 모순인가" 에 답할 것이 없었다 (#169).
        evidenceRecorder.appendMismatch(
                sessionId, mismatch, session.surveySchemaVersion(), session.surveyResult(),
                Instant.now());
        return session;
    }

    /**
     * 고객에게 보여준 질문을 기록한다 (F-INT-002).
     *
     * <p>채점이 같은 문면을 쓰게 하려는 것이다. ai-service 가 질문을 매번 생성하므로 저장하지
     * 않으면 채점 시점에 재현할 수 없다.
     */
    @Transactional
    public Session recordAskedQuestion(String sessionId, String itemId, String question,
                                       String questionType,
                                       EvidenceRecorder.QuestionSource source) {
        Session session = get(sessionId);
        session.recordAskedQuestion(itemId, question, questionType, source);
        return repository.save(session);
    }

    /**
     * F-INT-004 재설명 — 이해 부족 항목을 다시 설명한다. 상태를 RE_EXPLAIN으로 두고,
     * 이후 같은 항목 재답변(recordJudgment)이 재검증이 된다. 재검증 상한에 도달한 항목은
     * 재설명하지 않고 판정으로 보낸다(게이트 R-03이 RED).
     *
     * 재설명 콘텐츠는 ai-service /internal/reexplain 가 만든다 — 판정(측정값)과 risk_item,
     * 세션의 연령대·경험수준으로 눈높이 재설명을 생성한다(#60). LLM 문면은 판정이 아니라
     * 설명 초안이므로 P1 을 어기지 않는다. risk_item 은 호출부(SessionController)가 넘긴다 —
     * 지금은 목(MockData) 항목이고, 추출(F-EXT-002)이 붙으면 세션에 쌓인 항목으로 바뀐다.
     *
     * ai-service 호출은 상태 전이 전에 한다 — 실패(502)하면 세션을 RE_EXPLAIN 으로 옮기지
     * 않아 재시도가 깔끔하다(부수효과 없이 실패).
     */
    public ReExplanation reExplain(String sessionId, String itemId, RiskItem riskItem) {
        return reExplain(sessionId, itemId, riskItem, "ELS");
    }

    /**
     * 재설명 + 재검증 질문 (F-INT-004 · F-INT-002).
     *
     * <p>{@code productType} 을 호출자가 준다 — 상품 목록이 {@code api/MockData} 에 있어
     * 여기서 찾으면 {@code core} 가 {@code api} 에 의존하게 된다.
     */
    @Transactional
    public ReExplanation reExplain(String sessionId, String itemId, RiskItem riskItem,
                                   String productType) {
        Session session = get(sessionId);
        Judgment judgment = session.judgmentFor(itemId);
        if (judgment == null || judgment.grade() == Grade.U1) {
            throw new ReExplainNotEligibleException("재설명 대상이 아니다(판정 없음 또는 이미 이해): " + itemId);
        }
        if (session.reverifyExhausted(itemId, maxReverify)) {
            throw new ReverifyExhaustedException(
                    "재검증 상한(" + maxReverify + "회) 도달 — 재설명 불가, 판정으로 진행: " + itemId);
        }
        String content = aiServiceClient
                .reExplain(riskItem, judgment, session.ageBand(), session.experienceLevel())
                .content();
        session.fire(SessionFsm.Event.REQUEST_REEXPLAIN);
        repository.save(session);
        var reverify = reverifyQuestion(session, itemId, riskItem, productType);
        // ❗**보여줄 문면을 여기서 기록한다** (이슈 #274). 화면은 계약대로 이 문장을 띄우고
        // 고객은 그것에 답하는데, 안 남기면 /answers 가 **원 질문 문면**으로 채점하고
        // 기록한다 — 필드가 비는 것보다 나쁘다. 기록이 다른 질문을 가리키니 감사 시점에
        // 아무도 의심하지 않는다.
        session.recordAskedQuestion(itemId, reverify.question(), reverify.questionType(),
                EvidenceRecorder.QuestionSource.REVERIFY);
        repository.save(session);
        return new ReExplanation(itemId, content, session.vulnerable(), reverify.question());
    }

    /**
     * 재검증용 질문 — <b>ai-service 가 만든다</b> (F-INT-002).
     *
     * <h2>왜 고정 문항을 걷어냈나 — 기획서 7-4 1단계</h2>
     *
     * <p>여기 있던 목이 항목별 고정 문장이었고, 그 자리 주석이 스스로 적어 뒀다.
     *
     * <blockquote>이 목은 기획서 7-4 1단계(우회 비용 상향)를 만족하지 않는다. … 항목별로
     * 갈리기만 할 뿐 고정 문항이다. <b>사전에 확보하면 그대로 뚫린다.</b></blockquote>
     *
     * <p>7-4 가 요구하는 것은 <i>"질문이 상품 문서에서 자동 생성되므로 고정 문항을 사전에
     * 확보하는 것이 불가능"</i> 한 상태다. <b>재검증이야말로 판매자가 미리 답을 준비시킬
     * 동기가 가장 큰 자리</b>다 — 첫 질문에서 이미 한 번 막혔으므로. 거기가 고정이면
     * 게이트의 두 번째 관문이 열린 채로 있다.
     *
     * <h2>❗실패해도 인터뷰를 멈추지 않는다</h2>
     *
     * <p>생성이 죽으면 ai-service 의 재검증 폴백이 온다 — 그것도 <b>항목을 안 담는</b>
     * 한 문장이라 미리 확보해도 답이 되지 않는다. 여기서 502 를 올리면 재설명을 눌렀는데
     * 아무 일도 안 일어나고, 그 손해가 실패에 비례하지 않는다.
     */
    /**
     * 질문 생성에 넘길 면담 맥락 (F-INT-002).
     *
     * <p>❗<b>만드는 자리가 하나여야 한다.</b> 처음에 컨트롤러와 여기 두 곳에서 만들었더니
     * <b>한쪽만 고치는 변이가 안 잡혔다</b> — 두 경로가 서로 다른 맥락을 넘기게 되는데
     * 어느 쪽도 그것을 모른다.
     *
     * <p>{@code exceptItem} 은 <b>지금 물으려는 항목</b>이다. 그 항목의 앞선 판정은 뺀다 —
     * 재검증에서 자기 직전 등급이 맥락으로 가면 <i>"방금 U4 였다"</i> 가 질문에 실려
     * <b>고객이 자기 점수를 알게 된다.</b>
     */
    public AiServiceClient.InterviewContext interviewContext(Session session, String exceptItem) {
        return new AiServiceClient.InterviewContext(
                session.vulnerable(),
                session.priorGrades(exceptItem).stream().map(Enum::name).toList(),
                session.matchedMisconceptions(exceptItem));
    }

    private AiServiceClient.Question reverifyQuestion(Session session, String itemId,
                                                     RiskItem riskItem, String productType) {
        var context = interviewContext(session, itemId);
        // ❗**이 항목에 이미 쓴 유형을 넘긴다** — 재검증이 첫 질문과 같은 모양이면 같은
        // 각도로 두 번 재는 것이고, 유형이 고정이면 문면이 매번 생성돼도 대비할 수 있다
        // (기획서 7-4 1단계). 여기서 읽고 아래에서 기록하는 순서라야 이번 유형이 자기
        // 자신을 배제하지 않는다.
        return aiServiceClient.question(
                riskItem, session.askedTypes(itemId), productType, "reverify", context);
    }

    /**
     * 게이트 판정 — 세션에 쌓인 판정 + 모순 + '재검증 실패' 횟수를 GateEngine에 넘긴다(P1).
     * 판정 가능한 상태면 JUDGED로 전이.
     */
    @Transactional
    public GateResult judge(String sessionId) {
        Session session = get(sessionId);

        // 멱등(2026-08-25 결정) — 이미 판정된 세션은 재계산하지 않고 기록값을 돌려준다.
        // 재계산하면 그 사이 gate_rules.yaml 이 바뀐 경우 다른 답이 나오고 감사 기준점이
        // 둘이 된다. #16 합의: 기준점은 재계산값이 아니라 기록값이다.
        if (session.judgedAt() != null) {
            return recordedGate(session);
        }

        // 판정 0건 가드 — fail-closed RED 자체는 P5에 맞지만, JUDGED 는 CLOSE 외에 나가는
        // 전이가 없어서 되돌릴 수 없다. 답변 하나 없는 세션이 버튼 오작동 한 번으로 끝나면
        // 안 된다. 신호만 보고 싶으면 previewGate 를 쓴다(부수효과 없음).
        if (session.judgments().isEmpty()) {
            throw new SessionFsm.IllegalStateTransitionException(
                    session.state(), SessionFsm.Event.JUDGE);
        }

        GateResult result = gateEngine.judge(
                session.judgments(), session.suitabilityMismatch(), session.suitabilityUnknown(),
                session.failedReverifyCount(), session.unmeasuredItemCount());
        Instant judgedAt = Instant.now();
        session.recordGate(result, judgedAt);   // 감사 기준점 기록(F-GTE-004)
        if (SessionFsm.canFire(session.state(), SessionFsm.Event.JUDGE)) {
            session.fire(SessionFsm.Event.JUDGE);
        }
        repository.save(session);
        // append 는 같은 트랜잭션 안이다(recordJudgment 주석 참고).
        evidenceRecorder.appendGate(sessionId, result, judgedAt);
        return result;
    }

    /**
     * F-GTE-001 신호등 미리보기 — 계산만 하고 **아무것도 기록하지 않는다.**
     *
     * 기획서 7-2 [기능 1]이 "황색 판정 → 재설명 → 재검증 → 녹색 통과"인데, 판매자가 황색을
     * 보려면 신호등을 조회해야 한다. 그걸 judge() 로 하면 JUDGED 로 전이되고 거기서
     * RE_EXPLAIN 으로 갈 수 없어 그 흐름 자체가 성립하지 않는다.
     *
     * JUDGED → REQUEST_REEXPLAIN 전이를 여는 대신 조회 경로를 나눈다. 전이를 열면 판정
     * 시점이 여러 개가 되고 어느 것이 감사 기준점인지 모호해진다.
     * GateEngine 이 순수 함수라(P2) 미리보기와 확정이 같은 입력에 같은 답을 낸다.
     *
     * 이미 판정된 세션은 재계산하지 않고 기록값을 돌려준다 — judge() 멱등과 같은 이유다.
     */
    @Transactional(readOnly = true)
    public GatePreview previewGate(String sessionId) {
        Session session = get(sessionId);
        if (session.judgedAt() != null) {
            GateResult recorded = recordedGate(session);
            return new GatePreview(recorded.signal(), recorded.ruleTrace(), true,
                    session.judgedAt(), session.suitabilityStatus());
        }
        // ❗미측정 수도 같이 넘긴다. 안 넘기면 미리보기가 **판정보다 낙관적**이 되고,
        // 그건 아래 주석이 이미 경고한 모양이다 — 판매자가 GREEN 미리보기를 보고 재설명
        // 루프를 건너뛰는데 /judge 는 R-00 으로 RED 를 낸다.
        GateResult result = gateEngine.judge(
                session.judgments(), session.suitabilityMismatch(), session.suitabilityUnknown(),
                session.failedReverifyCount(), session.unmeasuredItemCount());
        // 미리보기는 모순 판정을 부르지 않는다 — GET 이 상태를 바꾸면 안 되고 LLM 호출 비용도
        // 든다. 대신 아직 평가 전이라는 사실을 실어 보낸다. 안 실으면 signal=GREEN 만 오는데,
        // /judge 는 모순을 평가하므로 같은 세션이 YELLOW·RED 로 갈릴 수 있다 — 미리보기가
        // 판정보다 낙관적인 쪽이라 판매자가 재설명 루프를 건너뛰게 된다.
        return new GatePreview(result.signal(), result.ruleTrace(), false, null,
                session.suitabilityStatus());
    }

    /** 세션에 기록된 게이트 결과(감사 기준점). 재계산하지 않는다. */
    private GateResult recordedGate(Session session) {
        return new GateResult(session.gateSignal(), session.gateRuleTrace(),
                session.gateUnmeasured(), session.gateRulesVersion());
    }

    /** 세션 중단(고객 이탈 등) → ABORTED. */
    public Session abort(String sessionId) {
        Session session = get(sessionId);
        if (SessionFsm.canFire(session.state(), SessionFsm.Event.ABORT)) {
            session.fire(SessionFsm.Event.ABORT);
            repository.save(session);
        }
        return session;
    }

    /**
     * 재설명 결과. vulnerable은 렌더링 힌트(큰 글씨·비유)이지 화면에 표시할 라벨이 아니다
     * — "취약 고객으로 분류됨"을 본인에게 보이면 기획서 7-4의 취급 원칙(보호 목적으로만)에
     * 어긋난다. reverifyQuestion은 재설명 직후 같은 항목을 다시 물을 때 쓸 변형 질문이다.
     */
    public record ReExplanation(String itemId, String content, boolean vulnerable,
                                String reverifyQuestion) {}

    /**
     * 신호등 미리보기. recorded=false 면 아직 감사 기준점이 아니다 — 화면이 이걸 확정으로
     * 보관하면 안 된다. judgedAt 이 null 이면 /judge 가 아직 호출되지 않은 세션이다.
     */
    /**
     * 게이트 미리보기.
     *
     * <p>{@code suitabilityStatus} 를 함께 싣는 이유: 미리보기는 적합성 모순을 평가하지
     * <b>않는다.</b> {@code NOT_EVALUATED} 인 채로 계산되므로 R-02·R-02b 가 둘 다 안 걸리고,
     * 전부 U1 이면 GREEN 이 나온다. 같은 세션에서 {@code /judge} 는 모순을 평가하므로
     * YELLOW·RED 가 될 수 있다 — <b>미리보기가 판정보다 낙관적</b>이라 나쁜 방향이다.
     *
     * <p>신호를 바꾸지 않고 그 사실을 드러낸다. {@code NOT_EVALUATED} 면 화면은
     * "적합성 미확인" 을 함께 보여야 하고, 이 GREEN 을 최종 통과로 그리면 안 된다.
     */
    public record GatePreview(Signal signal, List<RuleRef> ruleTrace,
                              boolean recorded, Instant judgedAt,
                              SuitabilityStatus suitabilityStatus) {}
}
