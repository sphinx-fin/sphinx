package com.sphinxfin.sphinx.evidence;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.TreeMap;

/**
 * F-GTE-004 이해 기록 리포트. 소유: 정세현
 * ① 불변 JSON(해시 체인) ② PDF ③ 고객 교부용 요약. 원문 응답 보존 기간 정책 포함.
 *
 * 저장·해시는 {@link ImmutableStore}·{@link HashChain}·{@link CanonicalJson}에 위임한다.
 * 이 클래스가 직접 직렬화하거나 해시를 만들면 AuditLog와 갈라진다.
 * 스트림 이름: "report:{sessionId}"
 *
 * <h2>리포트는 세션이 아니라 기록에서 만든다</h2>
 *
 * <p>내용을 전부 {@link ImmutableStore} 재생에서 조립한다. {@code Session}을 읽지 않는다 —
 * 세션은 가변이고 항목별 <b>최신</b> 판정만 들고 있어서, 세션에서 만들면 리포트가
 * <b>"최신"만 낼 수 있고 "왜 황색이었다가 통과했는가"에 답할 수 없다</b>(5.12).
 * 그리고 세션과 기록이 어긋났을 때 리포트가 어느 쪽을 말하는지 모호해진다.
 *
 * <h2>contentHash는 내용의 해시다 — 체인 항목 해시가 아니다</h2>
 *
 * <p>{@code sha256(CanonicalJson.bytes(content))}이고 {@code prevHash}·{@code seq}는 안 들어간다.
 * 이유는 <b>대조하는 사람이 누구인가</b>에 있다. 계약이 요약본에도 *"전문과 같은 해시"*를 싣게
 * 한 것은 <b>고객이 받은 문서를 나중에 대조할 수 있어야</b> 하기 때문인데, 체인 항목 해시는
 * 위치(앞 항목·순번)에 의존하므로 <b>문서만 가진 사람은 재계산할 수 없다.</b> ADR-008이 우리
 * 고유 규칙 대신 RFC 8785를 그대로 쓴 것과 같은 이유다.
 *
 * <p>발행 사실은 여전히 체인에 남는다 — 리포트 항목이 스트림에 append되고 그 payload가
 * contentHash를 담는다. 그래서 검증은 두 층이다: 체인이 온전한가({@code HashChain.verify}),
 * 그리고 기록에서 다시 조립한 내용이 그 해시를 내는가.
 *
 * <p><b>{@code generatedAt}은 내용에 안 들어간다.</b> 들어가면 같은 내용을 두 번 발행할 때마다
 * 해시가 달라져서 "이 문서가 그 문서인가"를 대조할 수 없다. 발행 시각은 체인 항목의
 * {@code at}이 갖는다.
 */
@Service
public class ReportService {

    /** 리포트 발행 기록의 payload 판별자. 조립 대상에서 스스로를 빼는 데도 쓴다. */
    static final String REPORT_TYPE = "report";

    private final ImmutableStore store;

    private final ReportPdf pdf;

    public ReportService(ImmutableStore store, ReportPdf pdf) {
        this.pdf = pdf;
        this.store = store;
    }

    /** 리포트 메타. 계약 {@code ReportResponse}·{@code ReportSummaryResponse}가 공유하는 부분이다. */
    public record Report(String reportId, String sessionId, Instant generatedAt, String contentHash) {}

    /**
     * 기록에서 리포트 내용을 조립한다. <b>부작용이 없다</b> — 미리보기·PDF 렌더·검증이 이걸 쓴다.
     *
     * <p>항목별로 판정 <b>이력</b>을 모은다. 재검증이 있었으면 {@code A:U3 → A:U1}이 순서대로
     * 남는다(5.12). 게이트 신호의 변천과 오버라이드 승인도 각각 시간순으로 담는다.
     */
    public Map<String, Object> render(String sessionId) {
        return render(sessionId, Long.MAX_VALUE);
    }

    /**
     * <b>발행 시점까지만</b> 재생해서 조립한다.
     *
     * <p>❗발행 뒤에도 판정은 더 쌓인다(재검증·오버라이드). 그래서 지금 전체를 재생하면
     * <b>교부된 문서와 다른 지면</b>이 나온다 — 해시도 안 맞는다. 스트림이 append-only 라
     * 발행 시점 내용은 그 seq 까지의 앞부분으로 <b>정확히 재현된다</b>(ADR-003).
     *
     * <p>ADR-004 가 발행 기록을 안 지우는 이유가 이것과 한 쌍이다 — 기록이 남아 있고 재현이
     * 되어야 <i>"교부 시점에 무엇이 적혀 있었는가"</i>에 답할 수 있다.
     */
    Map<String, Object> render(String sessionId, long uptoSeq) {
        Map<String, List<Map<String, Object>>> byItem = new TreeMap<>();
        List<Map<String, Object>> gateHistory = new ArrayList<>();
        List<Map<String, Object>> overrides = new ArrayList<>();

        for (HashChain.ChainEntry entry : store.replay(StoredEvidenceRecorder.streamOf(sessionId))) {
            if (entry.seq() > uptoSeq) {
                break;   // 발행 뒤에 쌓인 것은 그 문서에 없었다
            }
            Map<String, Object> payload = asMap(entry.payload());
            switch (String.valueOf(payload.get("type"))) {
                case "judgment" -> {
                    Map<String, Object> judgment = asMap(payload.get("judgment"));
                    byItem.computeIfAbsent(String.valueOf(judgment.get("itemId")), k -> new ArrayList<>())
                            .add(judgmentHistoryEntry(judgment, payload));
                }
                case "gate" -> gateHistory.add(ordered(
                        "at", payload.get("at"),
                        "signal", payload.get("signal"),
                        "ruleTrace", payload.get("ruleTrace")));
                case "override" -> overrides.add(ordered(
                        "at", payload.get("at"),
                        "approver", payload.get("approver"),
                        "reason", payload.get("reason")));
                default -> { /* 리포트 발행 기록은 조립 대상이 아니다 — 자기를 포함하면 순환이다 */ }
            }
        }

        List<Map<String, Object>> items = new ArrayList<>();
        byItem.forEach((itemId, history) -> items.add(ordered("itemId", itemId, "history", history)));

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("sessionId", sessionId);
        content.put("items", items);
        content.put("gateHistory", gateHistory);
        content.put("overrides", overrides);
        return content;
    }

    /** 내용의 해시. 문서만 가진 사람도 재계산할 수 있어야 하므로 위치 정보를 안 섞는다. */
    public String contentHash(Map<String, Object> content) {
        return sha256Hex(CanonicalJson.bytes(content));
    }

    /**
     * 리포트를 발행한다. <b>내용이 직전 발행과 같으면 다시 발행하지 않고 그것을 돌려준다.</b>
     *
     * <p>조회할 때마다 새 항목을 쌓으면 체인이 리포트로 채워지고, 무엇보다 <b>같은 내용의 문서가
     * 매번 다른 발행 기록을 갖는다.</b> 반대로 내용이 달라졌으면(판정이 더 쌓였으면) 새로
     * 발행해야 한다 — 그때 이전 리포트를 지우지는 않는다. 두 발행 기록이 나란히 남는 것이
     * <b>"교부 시점에 무엇이 적혀 있었는가"</b>에 답하는 방법이다.
     */
    @Transactional
    public Report issue(String sessionId, Instant at) {
        Map<String, Object> content = render(sessionId);
        String hash = contentHash(content);

        Optional<Report> unchanged = latest(sessionId).filter(r -> r.contentHash().equals(hash));
        if (unchanged.isPresent()) {
            return unchanged.get();
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", REPORT_TYPE);
        payload.put("sessionId", sessionId);
        payload.put("at", at);
        payload.put("contentHash", hash);
        HashChain.ChainEntry recorded = store.append(StoredEvidenceRecorder.streamOf(sessionId), payload);

        return new Report(reportId(sessionId, recorded.seq()), sessionId, at, hash);
    }

    /** 마지막 발행 기록. 없으면 비어 있다 — 아직 리포트를 낸 적이 없는 세션이다. */
    public Optional<Report> latest(String sessionId) {
        return latestIssued(sessionId).map(Issued::report);
    }

    /** 발행 기록 + 그 항목의 seq. seq 는 발행 시점 재현에 쓰므로 밖으로 내지 않는다. */
    private record Issued(Report report, long seq) {}

    private Optional<Issued> latestIssued(String sessionId) {
        Issued found = null;
        for (HashChain.ChainEntry entry : store.replay(StoredEvidenceRecorder.streamOf(sessionId))) {
            Map<String, Object> payload = asMap(entry.payload());
            if (REPORT_TYPE.equals(payload.get("type"))) {
                found = new Issued(new Report(reportId(sessionId, entry.seq()), sessionId,
                        Instant.parse(String.valueOf(payload.get("at"))),
                        String.valueOf(payload.get("contentHash"))), entry.seq());
            }
        }
        return Optional.ofNullable(found);
    }

    /**
     * 발행된 리포트의 PDF. <b>발행 시점 지면을 다시 그린다</b>(F-GTE-004 2번).
     *
     * <p>❗<b>재현한 내용의 해시가 발행 기록의 해시와 같은지 먼저 확인한다.</b> 다르면 그
     * 문서는 우리가 교부한 것이 아니다 — 조용히 다른 지면을 내주면 종이와 기록이 갈리고,
     * 그 사실은 분쟁 시점까지 드러나지 않는다. 재현이 안 되는 쪽이 낫다.
     *
     * <p>❗<b>실패 둘을 다른 타입으로 낸다.</b> 성격이 정반대라 같은 타입이면 엔드포인트가
     * 둘을 가를 수 없다 — 자세한 근거는 {@link ReportNotReproducibleException}.
     *
     * <pre>
     *   미발행     NoSuchElementException          정상 상태 → 404 (GET /report 와 같은 규약)
     *   재현 실패  ReportNotReproducibleException  무결성 실패 → 500. 404 로 접히면 안 된다
     * </pre>
     *
     * @throws java.util.NoSuchElementException 아직 발행하지 않았다 — 오류가 아니라 상태다
     * @throws ReportNotReproducibleException  재현한 내용이 발행 기록과 다르다
     */
    public byte[] pdf(String sessionId) {
        Issued issued = latestIssued(sessionId).orElseThrow(() -> new NoSuchElementException(
                "발행된 리포트가 없다: " + sessionId + " — POST /sessions/{sid}/report 가 먼저다"));

        Map<String, Object> asIssued = render(sessionId, issued.seq());
        String hash = contentHash(asIssued);
        if (!hash.equals(issued.report().contentHash())) {
            throw new ReportNotReproducibleException(
                    "발행 시점 내용을 재현하지 못했다: " + sessionId
                            + " 기록=" + issued.report().contentHash() + " 재현=" + hash
                            + " — 체인이 상했거나 조립 규칙이 바뀌었다");
        }
        return pdf.render(asIssued, hash, issued.report().generatedAt());
    }

    /** 발행 기록의 위치에서 유도한다 — 난수를 쓰면 같은 발행이 재현되지 않는다. */
    static String reportId(String sessionId, long seq) {
        return "report-" + sessionId + "-" + seq;
    }

    /**
     * 판정 한 건의 이력 항목. <b>그 판정을 만든 값들을 함께 담는다</b> (이슈 #136).
     *
     * <p>{@code askedQuestion} 과 {@code promptVersion} 이 여기 있어야 하는 이유는 같다 —
     * <b>둘 다 측정을 결정하는데 재구성할 방법이 없다.</b> 질문은 ai-service 가 매번 생성하고
     * 세션 테이블은 재질문 시 덮어쓰므로, 기록에서 빼면 <i>"이 판정은 어느 질문에 대한 답을
     * 잰 것인가"</i> 에 답할 수 없다. {@code confidence} 는 프롬프트 버전마다 뜻이 다르다 —
     * v1 은 등급 확신도, v2 는 재현 가능성이다(PR #114). 값만 있고 정의가 없으면 감사 시점에
     * 0.65 가 두 가지 뜻일 수 있다(결정 10.38).
     *
     * <p>그래서 {@code promptVersion} 을 {@code confidence} <b>바로 뒤</b>에 둔다. 읽는 사람이
     * 그 숫자의 뜻을 같은 자리에서 보게 하려는 것이고, 떨어뜨려 놓으면 하나만 보고 해석한다.
     *
     * <p><b>null 을 생략하지 않는다</b> — {@code misconceptionType} 과 같은 규약이다. 없음과
     * 미기재는 다르고, 여기서 생략하면 <i>"필드가 생기기 전 레코드"</i> 와 <i>"값이 없는
     * 판정"</i> 이 같아진다. append-only 라 섞인 뒤에는 못 가른다.
     *
     * <p>두 값은 <b>해시 대상에 들어간다</b>(내용의 일부다). 그게 요지다 — 빠져 있으면 질문이
     * 바뀌어도 {@code contentHash} 가 같아서, 문서를 받은 사람이 대조로 그 변화를 못 잡는다.
     *
     * <h3>{@code misconceptionType} 은 리포트에 안 싣는다 (이슈 #144)</h3>
     *
     * <p>그 값이 <b>불공정영업 신호 그 자체</b>다 — {@code UnfairSalesTypes.isSignal} 이
     * 보는 것이 이 필드이고, {@code M08-TYING} 이 오면 COMPL 로 사건이 나간다(F-GTE-003).
     * 정책은 {@code signal:unfair:read} 를 COMPL 로 좁혀 뒀는데, 리포트는
     * {@code report:read} 라 <b>SELLER 가 자기 세션 것을 연다.</b> 같은 값이 다른 action 으로
     * 새면 그 좁힘이 무의미하다 — 판매자가 무엇이 탐지되는지 알면 문면만 바꿔 같은 영업을
     * 한다(기획 7-4 · ADR-001).
     *
     * <p><b>역할에 따라 가리지 않는다.</b> 신호일 때만 빼면 <b>그 부재가 곧 신호</b>이고,
     * 역할마다 다른 내용을 내면 {@code contentHash} 가 갈려 <i>"고객이 받은 문서를 나중에
     * 대조한다"</i> 가 성립하지 않는다. 그래서 <b>항상 뺀다.</b>
     *
     * <p>감사가 손해 보지 않는다 — 값은 {@link StoredEvidenceRecorder} 가 불변 기록에 그대로
     * 남기고, COMPL 은 {@code audit:read}(org)로 그 체인을 읽는다. <b>리포트는 기록이 아니라
     * 기록에서 만든 문서</b>이고, 이 필드는 그 문서의 독자(판매자 포함)에게 줄 것이 아니다.
     */
    private static Map<String, Object> judgmentHistoryEntry(Map<String, Object> judgment,
                                                            Map<String, Object> payload) {
        Map<String, Object> history = new LinkedHashMap<>();
        history.put("at", payload.get("at"));
        history.put("reverifyCount", payload.get("reverifyCount"));
        history.put("askedQuestion", payload.get("askedQuestion"));   // 봉투 층 — 서버가 채운다
        // 문면 바로 뒤다 — 고객이 그 문면을 봤는지가 문면의 뜻을 바꾼다 (#136 3항).
        history.put("questionSource", payload.get("questionSource"));
        history.put("grade", judgment.get("grade"));
        history.put("confidence", judgment.get("confidence"));
        history.put("promptVersion", judgment.get("promptVersion"));  // confidence 의 정의다
        history.put("evidence", judgment.get("evidence"));
        history.put("reason", judgment.get("reason"));
        return history;                     // 색은 담지 않는다 (ADR-004 §5)
    }

    private static Map<String, Object> ordered(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static String sha256Hex(byte[] input) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 은 모든 JVM 이 제공해야 한다", e);
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte b : digest.digest(input)) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }
}
