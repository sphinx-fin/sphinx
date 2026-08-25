package com.sphinxfin.sphinx.core;

import com.sphinxfin.sphinx.domain.Channel;
import com.sphinxfin.sphinx.domain.GateResult;
import com.sphinxfin.sphinx.domain.Grade;
import com.sphinxfin.sphinx.domain.Judgment;
import com.sphinxfin.sphinx.domain.SessionState;
import com.sphinxfin.sphinx.domain.Signal;
import jakarta.persistence.CollectionTable;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    @Convert(converter = JsonMapConverter.class)
    @Column(columnDefinition = "TEXT")
    @Builder.Default
    private Map<String, Object> surveyResult = Map.of();   // 적합성 설문 결과, JSON 저장

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

    // 항목별 최신 판정(AI 측정값). 게이트 판정 입력으로 쓰인다. JSON 저장.
    @Convert(converter = JudgmentMapConverter.class)
    @Column(columnDefinition = "TEXT")
    @Builder.Default
    private Map<String, Judgment> judgmentsByItem = new HashMap<>();

    // 적합성 설문 vs 발화 모순 여부(F-DET-002). 감지되면 게이트 R-02로 RED.
    @Column(nullable = false)
    @Builder.Default
    private boolean suitabilityMismatch = false;

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

    // 발화 룰 ID 목록(예: ["R-01"]). 콤마 결합하지 않는다 — StringListConverter 주석 참고.
    @Convert(converter = StringListConverter.class)
    @Column(columnDefinition = "TEXT")
    private List<String> gateRuleTrace;
    private Instant judgedAt;              // 판정 시각

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
                .surveyResult(cmd.surveyResult() == null ? Map.of() : Map.copyOf(cmd.surveyResult()))
                .build();
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

    /** 항목별 최신 판정을 기록(재검증 시 덮어씀). */
    public void recordJudgment(Judgment judgment) {
        judgmentsByItem.put(judgment.itemId(), judgment);
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

    /** F-DET-002 모순 감지 결과 반영. */
    public void flagSuitabilityMismatch(boolean mismatch) {
        this.suitabilityMismatch = mismatch;
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
        this.judgedAt = judgedAt;
    }
}
