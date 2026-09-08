package com.sphinxfin.sphinx.core.pii;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * P3 경계가 <b>몇 번 작동했는가</b>. 소유: 강희진 (이슈 #326 1번)
 *
 * <h2>왜 계량기인가</h2>
 *
 * <p>보안·개인정보 주장이 전부 코드 주석과 정책 파일로만 있었다. 심사에서
 * <i>"개인정보는 어떻게 처리하나요"</i> 가 나오면 답이 <b>주석</b>이었다. 이 계량기가
 * 있으면 <b>숫자</b>가 된다 — 경계를 지나간 호출 수와 종류별 삭제 건수.
 *
 * <h2>❗원문은 어디에도 안 남는다</h2>
 *
 * <p>무엇이 걸렸는지를 남기면 그게 곧 PII 저장이고, <b>지우려고 만든 경로가 새는 자리</b>가
 * 된다. 여기 쌓이는 것은 <b>종류 이름과 개수뿐</b>이다 — 문자열 조각도, 세션 ID 도,
 * 행위자도 없다. 그래서 이 값은 개인을 식별하지 않고, 집계로만 존재한다.
 *
 * <p>같은 이유로 <b>세션별로 안 쌓는다.</b> 세션 축이 붙는 순간 <i>"이 고객이 주민번호를
 * 적었다"</i> 가 되고, 그건 우리가 지운 사실 자체를 다시 만드는 것이다.
 *
 * <h2>수명</h2>
 *
 * <p>프로세스와 함께 사라진다({@code evidence/} 와 달리 영속하지 않는다). 감사 기록이
 * 아니라 <b>운영 관측값</b>이기 때문이다 — 영속시키려면 그 자체가 보관 정책 대상이 되고,
 * 그 판단은 {@code #326} 2번(감사 조회 경로)과 같이 해야 한다.
 */
@Component
public class PiiMeter {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(PiiMeter.class);

    /**
     * 이 계량기가 세기 시작한 시각 = 프로세스 기동 시각(싱글턴이라 컨텍스트 생성 시점이다).
     *
     * <p>❗<b>이 값이 없으면 «호출 12건» 이 뜻을 잃는다.</b> 계량기가 프로세스와 함께
     * 사라지므로 그 숫자는 언제나 <i>"이 프로세스가 뜬 뒤로"</i> 이고, 그 시점을 같이 내지
     * 않으면 읽는 사람이 <b>전체 기간의 값으로 읽는다</b> — 재기동 직후라면 0 에 가까운 값을
     * 보고 <i>"마스킹이 안 돈다"</i> 로 오해한다({@code EvidenceMetrics} 가 같은 이유로
     * 「이 프로세스가 뜬 뒤로」를 문면에 적어 둔 자리다).
     */
    private final java.time.Instant since = java.time.Instant.now();

    private final AtomicLong calls = new AtomicLong();
    /** 무언가 하나라도 지워진 호출 수. {@link #calls} 의 부분집합이다. */
    private final AtomicLong callsWithRemovals = new AtomicLong();
    private final Map<String, AtomicLong> removed = new ConcurrentHashMap<>();

    /** 경계를 한 번 지났다. {@code hits} 가 비어도 부른다 — 분모가 있어야 비율이 선다. */
    public void record(PiiGateway.Masked masked) {
        calls.incrementAndGet();
        if (!masked.hits().isEmpty()) {
            // ❗**호출 수와 삭제 건수는 다른 질문이다.** 한 호출에서 셋이 지워질 수 있으므로
            // 삭제 건수만으로는 «몇 건에서 마스킹이 작동했나» 를 못 답한다 — 감사가 묻는
            // 것은 그쪽이다("마스킹이 실제로 도는가"). 두 분모를 다 든다.
            callsWithRemovals.incrementAndGet();
        }
        masked.hits().forEach((kind, count) ->
                removed.computeIfAbsent(kind, k -> new AtomicLong()).addAndGet(count));
    }

    /** 경계를 지나간 호출 수 — 마스킹이 한 건도 안 걸린 호출도 센다. */
    public long calls() {
        return calls.get();
    }

    /** 종류 → 누적 삭제 건수. 안 걸린 종류는 키가 없다. */
    public Map<String, Long> removed() {
        Map<String, Long> out = new TreeMap<>();
        removed.forEach((k, v) -> out.put(k, v.get()));
        return Map.copyOf(out);
    }

    /**
     * 종료 시점에 <b>한 번</b> 낸다 — 종류별 누적이 로그에 남는 유일한 자리다.
     *
     * <p>❗<b>매 호출 찍으면 안 된다.</b> 연속한 두 줄의 <b>차분</b>이 곧 그 호출의 종류별
     * 건수이고, 로그 줄의 시각이 세션 축 노릇을 하므로 <i>"이 고객이 주민번호를 적었다"</i>
     * 가 복원된다. 여기서는 프로세스가 끝날 때 한 줄이라 <b>어느 호출의 것인지 알 수 없다</b> —
     * 집계는 남고 개별 사건은 안 남는다.
     */
    @jakarta.annotation.PreDestroy
    void logSummaryOnce() {
        if (calls.get() > 0) {
            LOG.info("P3 경계 누적 — {}", summary());
        }
    }

    /**
     * 조회 경로({@code GET /dashboard/pii-summary})가 내는 값 (이슈 #326 파트1).
     *
     * <p>❗<b>{@code removedByKind} 는 안 걸린 종류도 0 으로 담는다</b>
     * ({@link PiiGateway#kinds()}) — 키를 아예 빼면 <i>"0 건이다"</i> 와 <i>"그런 패턴이
     * 없다"</i> 가 화면에서 같아진다. 반면 {@link #removed()} 는 <b>날값</b>이라 안 걸린
     * 종류의 키가 없다: 두 접근자의 규약이 다른 것이 여기서는 옳다(하나는 운영 관측,
     * 하나는 사람이 읽는 요약).
     *
     * <p>이 값에 개인이 식별될 조각은 하나도 없다 — 종류 이름·개수·시각뿐이다. 그래서
     * {@code audit:read}(COMPL org)로 낼 수 있고, 그 판단의 근거가 이 문단이다.
     *
     * @param since             세기 시작한 시각 = 프로세스 기동. <b>기간 질의를 받지 않는다</b>
     * @param calls             경계를 지나간 호출 수 (아무것도 안 지워진 호출 포함)
     * @param callsWithRemovals 그중 무언가 지워진 호출 수 — <i>"마스킹이 실제로 도는가"</i>
     * @param removedByKind     종류 → 누적 삭제 건수. <b>안 걸린 종류도 0 으로 있다</b>
     * @param removedTotal      종류 합계. 한 호출에서 여럿 지워질 수 있어 {@code calls} 와 무관하다
     */
    public record Summary(java.time.Instant since, long calls, long callsWithRemovals,
                          Map<String, Long> removedByKind, long removedTotal) {}

    /**
     * 지금 값을 {@link Summary} 로. 안 걸린 종류를 0 으로 채우는 유일한 자리다.
     *
     * <p>❗<b>{@code Map.copyOf} 를 쓰지 않는다.</b> 그것이 내는 불변 맵은 <b>삽입 순서를
     * 보장하지 않아</b>(JDK {@code ImmutableCollections.MapN}) 직렬화 순서가 흔들린다 —
     * 처음에 그렇게 썼고, 바로 위에 <i>"선언 순서를 유지한다"</i> 라고 적어 둔 주석과
     * 어긋났다. {@code LinkedHashMap} 을 감싸서 순서를 살린다.
     *
     * <p>다만 그 순서는 <b>읽는 사람을 위한 것이고 계약이 아니다</b> — 소비자가 순서에
     * 의존하면 안 된다(JSON 객체 키 순서는 규격상 무의미하다). 순서가 곧 뜻인 값은 배열로
     * 내야 한다는 지적이 있었고(#522 리뷰), 여기는 <b>키로 찾는 값</b>이라 맵이 맞다 —
     * {@code AccessSummary.byAction} 과 같은 모양이다.
     */
    public Summary snapshot() {
        Map<String, Long> raw = removed();
        Map<String, Long> byKind = new java.util.LinkedHashMap<>();
        // 마스킹이 도는 순서로 담는다 — ACCOUNT 가 마지막인 것에 뜻이 있어(카드·전화가 먼저
        // 지워지고 남은 것만 계좌로 센다) 그 순서로 읽히면 숫자가 설명된다.
        PiiGateway.kinds().forEach(kind -> byKind.put(kind, raw.getOrDefault(kind, 0L)));
        long total = raw.values().stream().mapToLong(Long::longValue).sum();
        return new Summary(since, calls.get(), callsWithRemovals.get(),
                java.util.Collections.unmodifiableMap(byKind), total);
    }

    /** {@code 호출 12건 · EMAIL=1 PHONE=2} — 종료 요약·조회 경로가 쓴다. */
    public String summary() {
        StringBuilder sb = new StringBuilder("호출 ").append(calls.get()).append("건");
        removed().forEach((k, v) -> sb.append(" · ").append(k).append('=').append(v));
        return sb.toString();
    }
}
