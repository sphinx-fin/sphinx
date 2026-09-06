package com.sphinxfin.sphinx.evidence;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import com.sphinxfin.sphinx.catalog.RiskItemCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 리포트 PDF (F-GTE-004 2번). 소유: 정세현
 *
 * <p>❗<b>PDF 를 다시 읽어서 대조한다.</b> 바이트 길이나 예외 없음만 보면 <i>"한글이 통째로
 * 두부인 PDF"</i> 도 초록이다 — 그 결함은 파일을 열어야 보인다.
 */
@DisplayName("F-GTE-004 리포트 PDF")
class ReportPdfTest {

    private final ReportPdf pdf = new ReportPdf(new RiskItemCatalog());
    private static final Instant AT = Instant.parse("2026-09-03T10:05:00Z");

    private static Map<String, Object> content(String sessionId, String utterance) {
        return Map.of(
                "sessionId", sessionId,
                "items", List.of(Map.of(
                        "itemId", "ELS-KNOCKIN-BARRIER",
                        "history", List.of(Map.of(
                                "at", "2026-09-03T10:00:00Z",
                                "grade", "U4",
                                "askedQuestion", "낙인이 무엇인지 설명해 주시겠어요?",
                                "reason", utterance)))),
                "gateHistory", List.of(Map.of(
                        "at", "2026-09-03T10:01:00Z", "signal", "RED", "ruleTrace", List.of("R-01"),
                        "unmeasured", 2, "rulesVersion", 3)),
                "overrides", List.of());
    }

    private static String textOf(byte[] bytes) throws IOException {
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    /** 게이트 항목 하나만 갈아 끼운 내용. 옛 기록을 만들려면 null 을 담아야 해서 Map.of 를 못 쓴다. */
    private static Map<String, Object> withGate(Map<String, Object> gate) {
        Map<String, Object> c = new java.util.LinkedHashMap<>(content("S-9", "원금 보장인 줄 알았어요"));
        c.put("gateHistory", List.of(gate));
        return c;
    }

    private static Map<String, Object> gate(Object... keyValues) {
        Map<String, Object> g = new java.util.LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            g.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return g;
    }

    @Test
    @DisplayName("★ 게이트 절이 **왜 그 신호였는지**를 그린다 — 룰과 판정 입력까지")
    void thePaperSaysWhyTheSignalWasWhatItWas() throws Exception {
        // ❗데모에서 증명해야 하는 것이 "RED 였다" 가 아니라 "왜 RED 였나" 다. 기록과 JSON 은
        // 넷을 들고 있는데 지면은 시각·신호뿐이었고, 픽스처가 ruleTrace 를 넣어 주면서도
        // 게이트 절에 대한 단정이 한 줄도 없어서 그 사실이 안 드러났다(PR #299 리뷰, 강희진).
        String text = textOf(pdf.render(content("S-7", "원금은 지켜지죠"), "abc123", AT));

        assertThat(text)
                .as("교부되는 것은 종이다 — 화면의 ruleTrace 는 닫히면 사라진다")
                .contains("R-01")
                .contains("미측정 2건")
                .contains("v3");
    }

    @Test
    @DisplayName("❗못 쟀는지 안 적었는지를 종이가 가른다 — 옛 항목은 '미측정 -' 이 아니다")
    void anOlderEntrySaysSoInsteadOfPrintingADash() throws Exception {
        // append-only 라 이 필드가 생기기 전에 적힌 게이트 항목은 영원히 그대로 남는다.
        // ReportService 는 그 항목을 "unmeasured": null 로 낸다 — 키를 지우지 않는 것이
        // 0(전부 쟀다)과 갈라 두려는 규약이다(StoredEvidenceRecorderTest 의 같은 단정).
        // str(null) 이 "-" 라서 아무 것도 안 하면 종이가 "미측정 -건" 이 된다.
        String text = textOf(pdf.render(
                withGate(gate("at", "2026-09-03T10:01:00Z", "signal", "RED",
                              "ruleTrace", List.of("R-01"), "unmeasured", null, "rulesVersion", null)),
                "abc123", AT));

        assertThat(text)
                .as("'미측정 -건' 은 0 과 구별되지 않고, 침묵은 그 사실을 숨긴다")
                .contains("판정 입력 미기재")
                .doesNotContain("미측정 -");
    }

    @Test
    @DisplayName("❗룰셋 0 을 'v0' 으로 찍지 않는다 — 존재하지 않는 버전을 말하는 것이다")
    void versionZeroIsNotAVersion() throws Exception {
        // rulesVersion 0 은 GateEngine.UNVERSIONED — 룰이 파일을 안 지나왔다는 뜻이다.
        // 종이가 "룰셋 v0" 이라고 하면 그 문서로는 어느 룰셋으로 판정했는지 되짚을 수 없고,
        // v0 인 gate_rules.yaml 을 찾으러 가게 된다.
        String text = textOf(pdf.render(
                withGate(gate("at", "2026-09-03T10:01:00Z", "signal", "RED",
                              "ruleTrace", List.of("R-01"), "unmeasured", 0, "rulesVersion", 0)),
                "abc123", AT));

        assertThat(text)
                .contains("미측정 0건")
                .contains("미버전")
                .doesNotContain("v0");
    }

    @Test
    @DisplayName("걸린 룰이 없으면 '없음' 이라고 적는다 — 빈 자리로 두지 않는다")
    void anEmptyTraceSaysNone() throws Exception {
        String text = textOf(pdf.render(
                withGate(gate("at", "2026-09-03T10:02:00Z", "signal", "GREEN",
                              "ruleTrace", List.of(), "unmeasured", 0, "rulesVersion", 3)),
                "abc123", AT));

        assertThat(text).contains("걸린 룰 없음");
    }

    @Test
    @DisplayName("★ 쓰는 폰트가 **대체 문자 자신**을 그릴 수 있다 — 아니면 모든 리포트가 실패한다")
    void theFontCanRenderItsOwnReplacementCharacter() throws Exception {
        // sanitize 는 모든 코드포인트를 검사하면서 **자기가 넣는 문자는 검사하지 않는다.**
        // 그래서 이 전제가 깨지면 드문 글자가 든 리포트만이 아니라 **전부** 실패한다
        // (PR #244 리뷰, 강희진이 라틴 전용 폰트로 실측 — 7건 전부 U+25A1 에서 죽었다).
        //
        // 폰트를 바꾸는 PR 은 이 단정을 먼저 만난다. 구현 쪽 loadFont 도 같은 것을 보지만,
        // 배포 전에 잡히는 쪽이 낫다.
        String withUnrenderable = "이름이 𠮷 입니다";   // 𠮷 는 CJK 확장 B — 대체를 유발한다

        String text = textOf(pdf.render(content("S-0", withUnrenderable), "h", AT));

        assertThat(text)
                .as("대체 문자를 못 그리는 폰트로 바뀌었다면 여기까지 오지 못하고 예외가 난다")
                .contains(String.valueOf(ReportPdf.UNRENDERABLE));
    }

    @Test
    @DisplayName("★ contentHash 가 지면에 찍힌다 — 이 문서가 기록과 대조되는 이유 전부다")
    void theHashIsOnThePage() throws Exception {
        String hash = "9f2b7c1a4e" + "0".repeat(54);

        String text = textOf(pdf.render(content("S-1", "원금은 지켜진다고 들었어요"), hash, AT));

        assertThat(text)
                .as("해시가 없으면 고객이 받은 종이를 불변 기록과 맞춰 볼 수 없다")
                .contains(hash);
    }

    @Test
    @DisplayName("❗내용이 실제로 그려진다 — 한글이 두부면 여기서 깨진다")
    void theContentIsActuallyRendered() throws Exception {
        String text = textOf(pdf.render(content("S-42", "낙인을 건드리면 무조건 손실이죠"), "abc123", AT));

        assertThat(text)
                .contains("이해 기록 리포트")
                .contains("S-42")
                .contains("ELS-KNOCKIN-BARRIER")
                .contains("U4")
                .contains("낙인을 건드리면 무조건 손실이죠")
                .contains("낙인이 무엇인지 설명해 주시겠어요?");
    }

    @Test
    @DisplayName("★ 종이가 항목 이름을 적는다 — 심사위원이 손에 받는 문서다 (이슈 #346)")
    void theItemLineCarriesItsDisplayName() throws Exception {
        String text = textOf(pdf.render(content("S-42", "낙인을 건드리면 무조건 손실이죠"), "abc123", AT));

        assertThat(text)
                .as("ID 원문만 찍히던 것이 이슈 #346 이다. 대시보드와 같은 결함인데 web 표로는 "
                        + "이쪽이 안 낫는다 — 그래서 서버가 이름을 싣는다")
                .contains("낙인 배리어 (ELS-KNOCKIN-BARRIER)");
    }

    @Test
    @DisplayName("❗이름을 모르는 항목이 교부를 막지 않는다 — ID 만 적고 계속 간다")
    void anUnknownItemStillGetsIssued() throws Exception {
        Map<String, Object> unknown = Map.of(
                "sessionId", "S-43",
                "items", List.of(Map.of(
                        "itemId", "ELS-NOT-IN-CATALOG",
                        "history", List.of(Map.of(
                                "at", "2026-09-03T10:00:00Z", "grade", "U1",
                                "askedQuestion", "질문", "reason", "사유")))),
                "gateHistory", List.of(),
                "overrides", List.of());

        String text = textOf(pdf.render(unknown, "abc123", AT));

        assertThat(text)
                .as("루브릭이 늘고 카탈로그가 안 따라온 상태에서도 교부는 돼야 한다 — 라벨 "
                        + "하나 때문에 기록이 죽으면 안 된다. 어긋남은 "
                        + "RiskItemCatalogMirrorsRubricsTest 가 CI 에서 잡는다")
                .contains("ELS-NOT-IN-CATALOG")
                .doesNotContain("null");
    }

    @Test
    @DisplayName("❗KS X 1001 밖 음절도 그려진다 — 임베딩이 런타임 서브셋이라 그렇다")
    void rareSyllablesSurvive() throws Exception {
        String rare = "똠얌꿍 쀍 뷁 꽠 쓩 홻 쨺 곷 뎧";

        String text = textOf(pdf.render(content("S-2", rare), "h", AT));

        assertThat(text)
                .as("미리 서브셋한 폰트를 쓰면 여기가 깨진다 (이슈 #233)")
                .contains(rare);
    }

    @Test
    @DisplayName("❗그릴 수 없는 글자가 발행을 막지 않는다 — 대체하고 **지면이 말한다**")
    void unrenderableCharactersAreReplacedAndDisclosed() throws Exception {
        // 𠮷 는 CJK 확장 B. 폰트에 없으면 PDFBox 가 IllegalStateException 을 던진다.
        String text = textOf(pdf.render(content("S-3", "이름이 𠮷 입니다"), "h", AT));

        assertThat(text)
                .as("예외로 죽으면 이모지 하나에 리포트 발행이 통째로 실패한다")
                .contains(String.valueOf(ReportPdf.UNRENDERABLE));
        assertThat(text)
                .as("조용히 바꾸면 종이가 원문과 다른데 그 사실이 어디에도 없다")
                .contains("표현할 수 없는 글자")
                .contains("contentHash");
    }

    @Test
    @DisplayName("❗못 그린 글자가 없으면 그 고지도 없다 — 매번 뜨면 신호가 아니다")
    void theDisclosureIsAbsentWhenNothingWasReplaced() throws Exception {
        String text = textOf(pdf.render(content("S-4", "원금 손실이 날 수 있다는 뜻이죠"), "h", AT));

        assertThat(text).doesNotContain("표현할 수 없는 글자");
    }

    @Test
    @DisplayName("❗렌더는 입력만의 함수다 — 같은 내용이면 같은 바이트다")
    void theRenderIsAFunctionOfItsInput() {
        Map<String, Object> c = content("S-5", "중간에 빼면 손해라던데요");

        byte[] first = pdf.render(c, "hash-1", AT);
        byte[] second = pdf.render(c, "hash-1", AT);

        assertThat(second)
                .as("판본·시각 같은 것이 섞이면 '해시가 가리키는 그 지면' 을 다시 못 그린다 "
                        + "— ADR-004 가 발행 기록을 안 지우는 이유와 같은 자리다")
                .isEqualTo(first);
    }

    @Test
    @DisplayName("긴 발화가 줄바꿈된다 — 잘리지 않는다")
    void longUtterancesWrapInsteadOfBeingCut() throws Exception {
        String longOne = "제가 이해하기로는 만기평가일에 기초자산 중 하나라도 최초기준가격의 "
                + "50% 미만이면 손실이 나고, 그 손실은 가장 많이 떨어진 종목의 하락률만큼 "
                + "제 원금에서 깎여 나간다는 것이고, 그래서 최악의 경우에는 넣은 돈을 거의 "
                + "돌려받지 못할 수도 있다는 말씀이신 거죠";

        String text = textOf(pdf.render(content("S-6", longOne), "h", AT)).replaceAll("\\s+", "");

        assertThat(text)
                .as("한 줄로 그리면 오른쪽이 잘려 나가는데 PDF 는 예외를 안 낸다")
                .contains(longOne.replaceAll("\\s+", ""));
    }

    // ── 빈 절 표식 ────────────────────────────────────────────────────────────
    //
    // ❗**절마다 다른 문면인 것이 요건이다.** 같은 문면이면 지면을 읽는 쪽이 어느 절이
    // 비었는지 못 가른다 — `scripts/walk_demo_session.sh` 가 `"기록 없음"` 하나로
    // *"판정 이력이 비었나"* 를 판정했는데 그것은 **게이트 절 표식**이었고, 판정 이력
    // 절에는 표식이 아예 없었다. 그래서 **판정 이력이 통째로 빈 리포트를 "교부 문서
    // 시연에 쓸 수 있다" 로 판정했다**(PR #302 리뷰 3번).

    /** 항목 없이, 게이트 기록만 있는 내용. #302 가 오판한 그 조합이다. */
    private static Map<String, Object> withoutItems() {
        return Map.of(
                "sessionId", "S-9",
                "items", List.of(),
                "gateHistory", List.of(Map.of(
                        "at", "2026-09-03T10:01:00Z", "signal", "RED", "ruleTrace", List.of("R-01"),
                        "unmeasured", 2, "rulesVersion", 3)),
                "overrides", List.of());
    }

    @Test
    @DisplayName("★ 판정 이력이 비면 지면이 그렇다고 적는다 — 빈 절은 게이트 표식과 구별된다")
    void anEmptyJudgmentHistorySaysSoInItsOwnWords() throws Exception {
        String text = textOf(pdf.render(withoutItems(), "abc123", AT));

        assertThat(text)
                .as("판정 이력 절에 빈 표식이 없으면 항목 0 인 지면과 불릿 글리프만 바뀐 "
                        + "지면이 같은 관측이 된다 — 그 둘을 가르는 것이 이 표식의 전부다")
                .contains("판정 이력 없음");
        assertThat(text)
                .as("게이트 기록은 있으므로 그쪽 표식은 없어야 한다. 두 절이 같은 문면을 쓰면 "
                        + "이 단정이 설 자리가 없다")
                .doesNotContain("게이트 기록 없음");
    }

    @Test
    @DisplayName("★ 세 절의 빈 표식이 서로 다르다 — 같으면 어느 절이 빈지 못 가른다")
    void eachSectionSaysEmptyInItsOwnWords() throws Exception {
        String text = textOf(pdf.render(
                Map.of("sessionId", "S-0", "items", List.of(),
                       "gateHistory", List.of(), "overrides", List.of()),
                "h", AT));

        assertThat(text)
                .contains("판정 이력 없음")
                .contains("게이트 기록 없음")
                .contains("예외 승인 없음");
    }

    @Test
    @DisplayName("찬 리포트에는 빈 표식이 하나도 없다 — 양성 대조")
    void aFullReportCarriesNoEmptyMarker() throws Exception {
        String text = textOf(pdf.render(content("S-1", "원금은 지켜지죠"), "h", AT));

        assertThat(text)
                .as("표식이 빈 경우에만 찍히는 것이 이 대조의 전제다. 늘 찍히면 위 두 "
                        + "테스트가 아무것도 안 잰다")
                .doesNotContain("판정 이력 없음")
                .doesNotContain("게이트 기록 없음");
    }
}
