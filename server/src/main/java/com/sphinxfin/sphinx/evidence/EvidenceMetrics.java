package com.sphinxfin.sphinx.evidence;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 불변 기록에 <b>이미 들어 있는데 아무도 안 세는 값</b>들을 센다 (이슈 #327). 소유: 정세현
 *
 * <p>새 필드도 새 계약도 필요 없다 — {@link StoredEvidenceRecorder} 가 판정마다 적재하는
 * {@code questionSource}·{@code confidence}·{@code at}·{@code reverifyCount} 를 훑기만 한다.
 *
 * <h2>왜 여기인가</h2>
 *
 * <p>{@link AuditLog#summary} 와 같은 모양이다 — 스트림을 재생해서 센다. 다른 것은 <b>무엇을
 * 세는가</b> 뿐이라 두 집계가 같은 규약을 쓰는 편이 낫다(못 읽은 것을 따로 세는 것 포함).
 *
 * <h2>❗실세션만 센다 — 합성 세션은 여기 없다</h2>
 *
 * <p>합성 세션은 집계용이라 불변 기록을 안 쌓는다(CLAUDE.md). 그래서 이 집계는 <b>자동으로</b>
 * 실세션 전용이고, 화면이 합성/실세션을 갈라 말해야 하는 문제(결정 5.16)가 여기서는 안 생긴다 —
 * <b>섞일 수가 없다.</b>
 *
 * <h2>비용</h2>
 *
 * <p>{@code report:} 스트림을 전부 재생한다. {@link AuditLog#summary} 와 같은 한계이고 같은
 * 이유로 지금은 그대로 둔다 — 좁힌 조회를 저장소에 내리면 append-only 계약이 넓어진다.
 */
@Service
public class EvidenceMetrics {

    /** 세션 스트림 접두어. {@link StoredEvidenceRecorder#streamOf} 와 같은 규칙이다. */
    private static final String SESSION_STREAM_PREFIX = "report:";

    /** 값이 안 남은 자리. {@link AuditLog#UNKNOWN} 과 같은 이유로 빈 문자열을 안 쓴다. */
    static final String UNKNOWN = "(미기록)";

    private final ImmutableStore store;
    private final EvidenceStreamAnchorRepository anchors;

    public EvidenceMetrics(ImmutableStore store, EvidenceStreamAnchorRepository anchors) {
        this.store = store;
        this.anchors = anchors;
    }

    /**
     * 네 값을 한 번에 낸다 (이슈 #327 의 1~4).
     *
     * <p>넷을 따로 내지 않는 이유는 스트림을 네 번 재생하지 않으려는 것이다. 그리고 이 값들은
     * <b>같이 읽어야 뜻이 있다</b> — 폴백률이 높은 회차의 등급 전환율은 다른 것을 재고 있다.
     */
    @Transactional(readOnly = true)
    public Summary summary() {
        Map<String, Long> questionSource = new TreeMap<>();
        Map<String, Long> confidence = new TreeMap<>();
        Map<String, Long> transitions = new TreeMap<>();
        List<Long> sessionSeconds = new ArrayList<>();
        long sessions = 0;
        long judgments = 0;
        long unreadable = 0;

        for (String stream : sessionStreams()) {
            // 스트림을 **한 번만** 재생한다. 두 번 부르면 그 사이에 들어온 기록 때문에
            // total 과 readable 이 다른 모집단을 세게 된다(append-only 라 늘기만 한다).
            List<Map<String, Object>> records = new ArrayList<>();
            long replayed = 0;
            for (HashChain.ChainEntry entry : store.replay(stream)) {
                replayed++;
                if (entry.payload() instanceof Map<?, ?> map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> typed = (Map<String, Object>) map;
                    records.add(typed);
                }
            }
            unreadable += replayed - records.size();
            if (records.isEmpty()) {
                continue;
            }
            sessions++;

            // ── ①② 질문 폴백률·확신도 분포 ──────────────────────────────────────
            List<Map<String, Object>> judged = records.stream()
                    .filter(r -> "judgment".equals(text(r.get("type"))))
                    .toList();
            judgments += judged.size();
            for (Map<String, Object> record : judged) {
                bump(questionSource, text(record.get("questionSource")));
                bump(confidence, decimalText(nested(record, "judgment", "confidence")));
            }

            // ── ③ 세션 소요시간 ───────────────────────────────────────────────
            Long seconds = elapsedSeconds(records);
            if (seconds != null) {
                sessionSeconds.add(seconds);
            }

            // ── ④ 재설명 전후 등급 전환 ────────────────────────────────────────
            countTransitions(judged, transitions);
        }

        sessionSeconds.sort(Comparator.naturalOrder());
        return new Summary(sessions, judgments, unreadable,
                questionSource, confidence, durations(sessionSeconds), transitions);
    }

    /**
     * 세션 스트림 목록. 닻(anchor)에서 읽는다 — {@link EvidenceEntryRepository} 에 조회를
     * 늘리지 않는 규약이 있어서다(그 인터페이스 주석). 닻은 스트림당 한 행이라 이쪽이 싸기도 하다.
     */
    private List<String> sessionStreams() {
        return anchors.findAll().stream()
                .map(EvidenceStreamAnchor::stream)
                .filter(s -> s != null && s.startsWith(SESSION_STREAM_PREFIX))
                .sorted()
                .toList();
    }

    /**
     * ③ 세션의 첫 기록부터 <b>게이트 판정</b>까지. 판정이 없으면 {@code null} — 끝나지 않은
     * 세션을 0 초로 세면 평균이 아래로 끌린다.
     *
     * <p>❗<b>「창구가 느려지나」의 답이라 게이트까지다.</b> 리포트 발행·오버라이드는 판정
     * 뒤의 일이고 사람이 자리를 뜬 뒤에도 일어난다 — 그걸 넣으면 면담 시간이 아니라 사무 시간이 된다.
     */
    private static Long elapsedSeconds(List<Map<String, Object>> records) {
        Instant first = null;
        Instant gate = null;
        for (Map<String, Object> record : records) {
            Instant at = instant(record.get("at"));
            if (at == null) {
                continue;
            }
            if (first == null) {
                first = at;
            }
            if ("gate".equals(text(record.get("type")))) {
                gate = at;      // 재판정이 있으면 마지막 게이트가 끝이다
            }
        }
        return first == null || gate == null ? null : Duration.between(first, gate).toSeconds();
    }

    /**
     * ④ 같은 항목의 판정이 <b>재검증 회차를 넘기며</b> 어떻게 바뀌었나. {@code "U3→U1"} 형태.
     *
     * <p>재설명 효과의 실물이다 — 대시보드가 지금 말할 수 있는 것은 *"오해가 29% 였다"* 뿐인데
     * 그건 문제 제기이지 성과가 아니다(이슈 #327). {@code reverifyCount} 가 늘어난 쌍만 센다:
     * 같은 회차에 판정이 두 번 적재되는 것은 재설명이 아니라 재판정이라 여기서 세면 안 된다.
     */
    private static void countTransitions(List<Map<String, Object>> judged,
                                         Map<String, Long> transitions) {
        Map<String, List<Map<String, Object>>> byItem = new LinkedHashMap<>();
        for (Map<String, Object> record : judged) {
            String itemId = text(nested(record, "judgment", "itemId"));
            if (itemId != null) {
                byItem.computeIfAbsent(itemId, k -> new ArrayList<>()).add(record);
            }
        }
        for (List<Map<String, Object>> history : byItem.values()) {
            for (int i = 1; i < history.size(); i++) {
                Long before = number(history.get(i - 1).get("reverifyCount"));
                Long after = number(history.get(i).get("reverifyCount"));
                if (before == null || after == null || after <= before) {
                    continue;
                }
                String from = text(nested(history.get(i - 1), "judgment", "grade"));
                String to = text(nested(history.get(i), "judgment", "grade"));
                bump(transitions, from == null || to == null ? null : from + "→" + to);
            }
        }
    }

    private static Durations durations(List<Long> sorted) {
        if (sorted.isEmpty()) {
            return new Durations(0, null, null, null);
        }
        return new Durations(sorted.size(), sorted.get(0),
                sorted.get(sorted.size() / 2), sorted.get(sorted.size() - 1));
    }

    /**
     * 판정까지 걸린 시간. <b>평균을 안 낸다</b> — 한 세션이 길어지면 평균이 통째로 끌려가고,
     * 「창구가 느려지나」에 답하는 것은 <b>가운데와 최악</b>이다.
     *
     * @param medianSeconds 가운데 값. 표본이 짝수면 위쪽을 쓴다(정렬 후 size/2)
     */
    public record Durations(int sessions, Long minSeconds, Long medianSeconds, Long maxSeconds) {}

    /**
     * 이슈 #327 의 넷.
     *
     * @param unreadable      payload 를 못 읽어 어느 집계에도 못 넣은 기록 수 —
     *                        0 으로 뭉개면 {@code judgments} 가 "읽을 수 있었던 것" 이 된다
     * @param questionSource  ① LLM 인가 템플릿 폴백인가. *"데모가 될 상태인가"* 를 이 하나가 답한다
     * @param confidence      ② 확신도 분포. ADR-005 가 dev set 24건에서 `[0.7, 0.9, 1.0]` 뿐이고
     *                        {@code <0.7} 이 0건임을 실측해 뒀다 — 운영에서 같으면 R-05 가
     *                        사실상 후처리 전용으로 돈다는 뜻이다
     * @param sessionDuration ③ 세션 첫 기록 → 게이트 판정
     * @param reexplainTransitions ④ 재설명 전후 등급 전환 (`"U3→U1"` → 건수)
     */
    public record Summary(long sessions, long judgments, long unreadable,
                          Map<String, Long> questionSource,
                          Map<String, Long> confidence,
                          Durations sessionDuration,
                          Map<String, Long> reexplainTransitions) {}

    // ── 재생된 payload 읽기 — 한 건이 집계를 못 죽인다 ──────────────────────────

    private static Object nested(Map<String, Object> record, String outer, String key) {
        return record.get(outer) instanceof Map<?, ?> inner ? inner.get(key) : null;
    }

    private static void bump(Map<String, Long> counts, String key) {
        counts.merge(key == null ? UNKNOWN : key, 1L, Long::sum);
    }

    private static String text(Object value) {
        return value instanceof CharSequence s ? s.toString() : null;
    }

    private static Long number(Object value) {
        return value instanceof Number n ? n.longValue() : null;
    }

    /**
     * 분포의 키가 될 수의 문면.
     *
     * <p>❗<b>여기서 다시 정규화하지 않는다.</b> {@link CanonicalJson#write} 가 적재 시점에
     * 이미 {@code stripTrailingZeros().toPlainString()} 으로 고정했으므로 저장된 JSON 에는
     * {@code 1.0} 이 아니라 {@code 1} 이 들어 있다. 여기서 한 번 더 접으면 <b>정규화가 두 곳에
     * 생기고</b>, 그 둘이 미묘하게 갈리는 날 같은 값이 두 칸으로 세인다 — 이 레포가
     * {@code CanonicalJson} 을 유일 지점으로 둔 이유가 그것이다.
     *
     * <p>재생 매퍼가 소수를 {@link BigDecimal} 로 주므로({@code JpaImmutableStore}) 문면이
     * 그대로 돌아온다. 그 성질이 깨지면 {@code EvidenceMetricsTest} 의 확신도 분포가 빨개진다.
     */
    private static String decimalText(Object value) {
        if (!(value instanceof Number n)) {
            return null;
        }
        return n instanceof BigDecimal d ? d.toPlainString() : n.toString();
    }

    private static Instant instant(Object value) {
        if (value instanceof CharSequence text) {
            try {
                return Instant.parse(text.toString());
            } catch (java.time.format.DateTimeParseException e) {
                return null;
            }
        }
        return null;
    }
}
