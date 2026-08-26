package com.sphinxfin.sphinx.evidence;

import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * F-GTE-004 원문 응답 보존기간 정책. 소유: 정세현
 *
 * <p>기획서 437행이 근거다.
 *
 * <blockquote><b>최소 보존</b> : 원문 발화는 보관기간을 한정하고, <b>이후에는 판정 결과만
 * 남긴다.</b></blockquote>
 *
 * <h2>무엇을 지우고 무엇을 남기나 — 이 구분이 정책의 전부다</h2>
 *
 * <p><b>지우는 것</b>: {@code session_utterance} 의 발화 전문(마스킹본). 고객이 실제로 한 말
 * 전체이고, 보관기간이 지나면 보관할 근거가 없다.
 *
 * <p><b>남기는 것</b>: 판정과 그 <b>근거로 인용된 조각</b>({@code Judgment.Evidence.utteranceQuote}).
 * 인용도 고객의 말이지만 <b>판정의 일부</b>다 — P4 가 *"근거 없는 판정은 무효"* 이므로 인용을
 * 지우면 남는 판정이 증거로서 무의미해진다. 그리고 그건 append-only 해시 체인 안이라
 * <b>지우면 사슬이 끊긴다</b>(ADR-004).
 *
 * <p>두 정책이 충돌하는 것처럼 보이지만 대상이 다르다 — <b>발화 전문</b>과 <b>근거 인용</b>은
 * 같은 것이 아니다. 인용은 루브릭 조항에 걸린 한 조각이라 범위가 한정돼 있고, 그게 기획서가
 * *"판정 결과만 남긴다"* 로 허용한 범위라고 읽었다.
 *
 * <h2>감사 로그의 두 번째 호출 지점 — 명시적 예외다</h2>
 *
 * <p>CLAUDE.md 는 <i>"감사 로그는 {@code AuditInterceptor} 단일 통로로 기록한다"</i> 고 정했고
 * 그 근거는 <b>감사 관심사가 {@code api/} 에 흩어지는 것</b>을 막는 것이다. 여기는 <b>HTTP 요청이
 * 아니다</b> — 인터셉터가 덮을 수 없는 자리이므로 규약이 막으려던 상황이 아니다.
 *
 * <p><b>예외를 적어두는 이유</b>: 규약이 조용히 닳지 않게 하려는 것이다. 같은 규약 때문에
 * 필터 단계 감사(#105)는 별건으로 빠졌다 — 두 결정이 같은 근거에서 갈리므로, 문면이 없으면
 * 다음 사람이 어느 쪽을 따라야 할지 모른다. 기준은 <b>"HTTP 요청 경로인가"</b> 다.
 *
 * <h2>삭제도 기록한다</h2>
 *
 * <p>지운 사실을 감사 스트림에 남긴다. 안 남기면 <b>"발화가 없다"와 "발화를 지웠다"가 구별되지
 * 않는다</b> — 분쟁 시점에 *"기록을 안 했다"* 와 *"보존기간이 지나 지웠다"* 는 전혀 다른 답이다.
 * 이 프로젝트가 반복해서 경계한 실패 양식이 그거다.
 *
 * <h2>기본은 꺼져 있다</h2>
 *
 * <p>{@code sphinx.retention.enforce}(기본 {@code false}). 켜지 않으면 스케줄이 돌지 않는다.
 * 되돌릴 수 없는 삭제를 하는 코드가 <b>기본으로 켜진 채</b> 배포되면 안 되고,
 * {@code sphinx.security.enforce} 와 같은 이유로 <b>꺼져 있음을 기동 로그에 남긴다</b> —
 * 조용히 안 돌면 "정책이 있다"고 착각하게 된다.
 */
@Slf4j
@Service
public class RetentionService {

    /** 시스템 행위다 — HTTP action 이 아니라 rbac_policy.yaml 에 없다. */
    static final String PURGE_ACTION = "retention:purge";

    /**
     * {@code AuditLog.Entry.resultCode} 에 넣는 값.
     *
     * <p><b>그 필드는 HTTP 상태 문자열이 아니다</b> — {@code AuditInterceptor} 가 HTTP 경로에서
     * {@code String.valueOf(status)} 로 채우지만, 이 스트림에는 HTTP 가 아닌 행위도 들어온다.
     * 그래서 필드의 뜻을 <b>"HTTP 상태 또는 시스템 행위 결과"</b> 로 넓혀 쓰고, 둘을 가르는
     * 것은 {@code action} 이다({@code retention:*} 은 시스템, 그 외는 HTTP action 이름).
     *
     * <p>숫자로 통일하지 않은 이유: 삭제에 맞는 HTTP 상태가 없다. 200 을 쓰면 <b>요청이 있었던
     * 것처럼 보이고</b>, 그게 감사에서 더 나쁜 오독이다. {@code audit:read} 가 붙을 때
     * *"4xx 만 골라 차단 시도를 본다"* 같은 질의는 <b>{@code action} 으로 먼저 좁히면 된다.</b>
     */
    static final String PURGE_RESULT = "PURGED";

    private final EntityManager em;
    private final AuditLog auditLog;
    private final int months;
    private final boolean enforce;

    public RetentionService(EntityManager em, AuditLog auditLog,
                            @Value("${sphinx.retention.raw-answer-months}") int months,
                            @Value("${sphinx.retention.enforce:false}") boolean enforce) {
        this.em = em;
        this.auditLog = auditLog;
        this.months = months;
        this.enforce = enforce;
        if (!enforce) {
            log.warn("보존기간 정책 비활성(sphinx.retention.enforce=false) — 발화 전문이 "
                    + "{}개월이 지나도 지워지지 않는다. 켜기 전까지 정책은 선언만 된 상태다.", months);
        }
    }

    /**
     * 실행 결과. <b>{@code ran=false}(정책이 꺼져 있었다)와 {@code purged=0}(돌았고 대상이
     * 없었다)을 가른다.</b> 하나로 두면 호출자가 "0건 삭제" 를 찍고, 그건
     * <b>"정책이 돌았고 대상이 없었다"로 읽힌다</b> — 이 클래스가 경계한 바로 그 양식이다.
     */
    public record PurgeResult(boolean ran, int purged) {}

    /**
     * 보존기간이 지난 세션의 발화 전문을 지운다.
     *
     * <p>기준 시각은 {@code createdAt} 이다 — 발화가 그 세션에서 일어났으므로. <b>한계가 있다</b>:
     * 계약이 실제로 끝난 시점이 아니라 세션이 시작된 시점이라, 오래 열려 있던 세션은 마지막
     * 발화가 보존기간보다 짧게 남는다. 세션이 하루 안에 끝나는 지금 구조에서는 차이가 없고,
     * 그렇지 않게 되면 종료 시각을 기준으로 바꿔야 한다.
     */
    @Transactional
    public PurgeResult purgeExpiredUtterances(Instant now) {
        if (!enforce) {
            return new PurgeResult(false, 0);
        }
        // 달력 개월로 뺀다. months * 30일 로 근사하면 커트오프가 더 나중이 되어
        // **아직 보존기간이 안 된 발화가 지워진다**(6개월 설정에서 최대 4일 — PR #107 리뷰).
        // "6개월 보존" 이라고 적고 5개월 26일에 지우는 것도 지키지 않는 정책이다.
        Instant cutoff = now.atZone(ZoneOffset.UTC).minusMonths(months).toInstant();

        // 발화가 남아 있는 만료 세션만 고른다. 이미 지운 세션을 다시 세면 감사 기록이 부풀고,
        // "지웠다"가 여러 번 남아 언제 지웠는지가 흐려진다.
        List<String> expired = em.createQuery("""
                        select distinct s.id from Session s
                        where s.createdAt < :cutoff and size(s.maskedUtterancesByItem) > 0
                        """, String.class)
                .setParameter("cutoff", cutoff)
                .getResultList();
        if (expired.isEmpty()) {
            return new PurgeResult(true, 0);
        }

        // 벌크 삭제다 — 세션을 로드해 맵을 비우는 것보다 싸고, 이건 도메인 행위가 아니라
        // 데이터 수명 관리다. 대가: 1차 캐시에 이미 올라온 Session 은 낡은 맵을 들고 있다.
        // 배치 경로라 같은 트랜잭션에서 세션을 다시 쓰지 않으므로 문제가 되지 않는다.
        int rows = em.createNativeQuery(
                        "delete from session_utterance where session_id in (:ids)")
                .setParameter("ids", expired)
                .executeUpdate();

        // 세션 단위로 남긴다. 어느 세션의 발화가 지워졌는지가 감사에서 필요한 단위다.
        // 건수가 커지면 한 건으로 묶는 편이 낫지만 그러면 payload 모양을 바꿔야 한다.
        recordAfterCommit(expired, now.truncatedTo(ChronoUnit.MILLIS));
        log.info("보존기간 경과 발화 삭제 — 세션 {}건 · 행 {}개 · 기준 {}", expired.size(), rows, cutoff);
        return new PurgeResult(true, expired.size());
    }

    /**
     * 감사 기록을 <b>바깥 트랜잭션이 커밋된 뒤에</b> 남긴다.
     *
     * <p>{@link AuditLog#record} 는 {@code REQUIRES_NEW} 라 즉시 커밋된다. 그런데 삭제는 이
     * 트랜잭션에 달려 있으므로, 바깥이 롤백되면 <b>발화는 살아 있는데 감사는 "지웠다"고 말한다.</b>
     * 이 클래스의 논거가 *"'없다'와 '지웠다'는 다른 답이다"* 인데 그 경우는 <b>'안 지웠다'가
     * '지웠다'로 기록되는 것</b>이라 방향이 더 나쁘다 — 없는 기록은 조사하면 드러나지만
     * <b>있는 거짓 기록은 조사를 끝내버린다.</b> (PR #107 리뷰에서 실측으로 재현했다.)
     *
     * <p>대가는 반대쪽이다: 커밋과 기록 사이에 죽으면 <b>지웠는데 기록이 없다.</b> 감사가
     * 거짓을 주장하는 것보다 침묵하는 편이 낫다 — 침묵은 다른 흔적(발화 부재)과 대조하면
     * 드러나고, 거짓 주장은 대조해도 맞아 보인다.
     */
    private void recordAfterCommit(List<String> sessionIds, Instant at) {
        List<AuditLog.Entry> entries = new ArrayList<>();
        for (String sessionId : sessionIds) {
            entries.add(new AuditLog.Entry(
                    null, null, PURGE_ACTION, "session:" + sessionId, PURGE_RESULT, at));
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // 트랜잭션 밖에서 불릴 일은 없다(@Transactional). 그래도 조용히 버리지 않는다.
            entries.forEach(auditLog::record);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                entries.forEach(auditLog::record);
            }
        });
    }
}
