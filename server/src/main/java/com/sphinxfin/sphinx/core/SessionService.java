package com.sphinxfin.sphinx.core;

import com.sphinxfin.sphinx.domain.GateResult;
import com.sphinxfin.sphinx.domain.Grade;
import com.sphinxfin.sphinx.domain.Judgment;
import com.sphinxfin.sphinx.domain.SessionState;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Optional;

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
    private final int maxReverify;   // 항목당 재검증 상한(application.yml)

    /**
     * evidenceRecorder는 Optional 주입 — evidence/ 구현(F-GTE-004)이 등록되기 전에도
     * 세션 루프가 돌아야 하므로, 없으면 NO_OP으로 대체한다.
     */
    public SessionService(SessionRepository repository,
                          GateEngine gateEngine,
                          CoachingScoreService coachingScoreService,
                          Optional<EvidenceRecorder> evidenceRecorder,
                          @Value("${sphinx.scoring.max-reverify:2}") int maxReverify) {
        this.repository = repository;
        this.gateEngine = gateEngine;
        this.coachingScoreService = coachingScoreService;
        this.evidenceRecorder = evidenceRecorder.orElse(EvidenceRecorder.NO_OP);
        this.maxReverify = maxReverify;
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
    public Judgment recordJudgment(String sessionId, Judgment judgment) {
        Session session = get(sessionId);
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
        evidenceRecorder.appendJudgment(
                sessionId, judgment, session.reverifyCount(judgment.itemId()), Instant.now());
        return judgment;
    }

    /**
     * F-INT-004 재설명 — 이해 부족 항목을 다시 설명한다. 상태를 RE_EXPLAIN으로 두고,
     * 이후 같은 항목 재답변(recordJudgment)이 재검증이 된다. 재검증 상한에 도달한 항목은
     * 재설명하지 않고 판정으로 보낸다(게이트 R-03이 RED). 문면은 취약 여부로 맞춘다.
     */
    public ReExplanation reExplain(String sessionId, String itemId) {
        Session session = get(sessionId);
        Judgment judgment = session.judgmentFor(itemId);
        if (judgment == null || judgment.grade() == Grade.U1) {
            throw new ReExplainNotEligibleException("재설명 대상이 아니다(판정 없음 또는 이미 이해): " + itemId);
        }
        if (session.reverifyExhausted(itemId, maxReverify)) {
            throw new ReverifyExhaustedException(
                    "재검증 상한(" + maxReverify + "회) 도달 — 재설명 불가, 판정으로 진행: " + itemId);
        }
        session.fire(SessionFsm.Event.REQUEST_REEXPLAIN);
        repository.save(session);
        return new ReExplanation(itemId, reExplainContent(itemId, session.vulnerable()),
                session.vulnerable(), reverifyQuestion(itemId, session.vulnerable()));
    }

    /**
     * 재설명 문면. 기획서 7-2 [기능 1]은 "해당 항목만" 재설명하라고 하므로 itemId로 가른다
     * — 예금자보호 항목을 물었는데 원금손실을 설명하면 재설명이 아니라 딴소리다.
     * 눈높이는 취약 여부로 다시 가른다(기획서 175행: 비유 중심·짧은 문장).
     * TODO: ai-service /internal/reexplain 연결(F-INT-004, 윤지석). 지금은 데모 항목 2종 목.
     */
    private String reExplainContent(String itemId, boolean vulnerable) {
        return switch (itemId) {
            case "ELS-PRINCIPAL-LOSS-WARNING" -> vulnerable
                    ? "쉽게 다시 설명드릴게요. 이 상품은 은행 예금과 달라서 맡긴 돈(원금)이 "
                      + "줄어들 수 있어요. '예금처럼 안전하다'가 아니라는 점만 꼭 기억해 주세요."
                    : "다시 설명드리면, 기초자산이 정해진 수준 아래로 내려가면 원금 손실이 "
                      + "발생합니다. 손실은 하락폭에 비례해 커집니다.";
            case "ELS-NO-DEPOSIT-INSURANCE" -> vulnerable
                    ? "이건 예금이 아니에요. 은행이 잘못돼도 나라가 5천만 원까지 돌려주는 "
                      + "예금자보호가 이 상품에는 적용되지 않습니다."
                    : "다시 설명드리면, 이 상품은 예금자보호법 적용 대상이 아닙니다. "
                      + "예금자보호 한도와 무관하게 원금이 보호되지 않습니다.";
            default -> vulnerable
                    ? "쉽게 다시 설명드릴게요. 이 상품은 은행 예금과 달라서 맡긴 돈이 "
                      + "줄어들 수 있어요."
                    : "다시 설명드리면, 이 상품은 원금이 보장되지 않으며 조건에 따라 "
                      + "손실이 발생할 수 있습니다.";
        };
    }

    /**
     * 재검증용 변형 질문(F-INT-002). 재설명 응답에 실어 프론트가 직전 질문을 그대로 다시
     * 띄우지 않게 한다.
     *
     * 주의 — 이 목은 기획서 7-4 1단계(우회 비용 상향)를 만족하지 않는다. 그 조항이 요구하는
     * 것은 "질문이 상품 문서에서 자동 생성되므로 고정 문항을 사전에 확보하는 것이 불가능"한
     * 상태인데, 아래는 항목별로 갈리기만 할 뿐 고정 문항이다. 사전에 확보하면 그대로 뚫린다.
     * 우회 비용이 실제로 올라가는 것은 F-INT-002가 문서에서 질문을 생성한 뒤부터다.
     * TODO: ai-service /internal/question?variant=reverify 연결(F-INT-002, 윤지석).
     */
    private String reverifyQuestion(String itemId, boolean vulnerable) {
        // 데모 항목 2종(api/MockData.RISK_ITEMS와 같은 ID). ai-service 연결 시 함께 사라진다.
        // 질문도 재설명 문면과 같은 눈높이여야 한다 — 쉬운 말로 설명해 놓고 곧바로 "기초자산"을
        // 되물으면 고령자 모드가 한 응답 안에서 깨진다(기획서 175행: 비유 중심·짧은 문장).
        return switch (itemId) {
            case "ELS-PRINCIPAL-LOSS-WARNING" -> vulnerable
                    ? "그러면 어떤 경우에 맡기신 돈이 줄어드는지, 편하게 말씀해 주시겠어요?"
                    : "그러면 기초자산이 어디까지 떨어졌을 때 손실이 나는지, 방금 설명을"
                      + " 기준으로 본인 말씀으로 한 번만 더 말씀해 주시겠어요?";
            case "ELS-NO-DEPOSIT-INSURANCE" -> vulnerable
                    ? "이 상품에 넣으신 돈이 은행 예금처럼 보호받지 못한다는 게 어떤 뜻일까요?"
                    : "이 상품에 넣은 돈이 예금자보호를 받지 못한다는 게 어떤 뜻인지,"
                      + " 본인 말씀으로 다시 설명해 주시겠어요?";
            default -> vulnerable
                    ? "방금 설명드린 것 중에 가장 중요한 게 뭐라고 이해하셨는지 말씀해 주시겠어요?"
                    : "방금 설명드린 내용 중 가장 중요한 조건이 무엇인지, 그리고 그 조건에"
                      + " 해당하면 어떻게 되는지 본인 말씀으로 다시 설명해 주시겠어요?";
        };
    }

    /**
     * 게이트 판정 — 세션에 쌓인 판정 + 모순 + '재검증 실패' 횟수를 GateEngine에 넘긴다(P1).
     * 판정 가능한 상태면 JUDGED로 전이.
     */
    public GateResult judge(String sessionId) {
        Session session = get(sessionId);
        GateResult result = gateEngine.judge(
                session.judgments(), session.suitabilityMismatch(), session.failedReverifyCount());
        Instant judgedAt = Instant.now();
        session.recordGate(result, judgedAt);   // 감사 기준점 기록(F-GTE-004)
        if (SessionFsm.canFire(session.state(), SessionFsm.Event.JUDGE)) {
            session.fire(SessionFsm.Event.JUDGE);
        }
        repository.save(session);
        // 신호의 변천(황색→녹색)을 남긴다 — 세션에는 최종 신호만 남으므로.
        evidenceRecorder.appendGate(sessionId, result, judgedAt);
        return result;
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
}
