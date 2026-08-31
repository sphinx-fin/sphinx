package com.sphinxfin.sphinx.evidence;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
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

    private final ReportPdf pdf = new ReportPdf();
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
                        "at", "2026-09-03T10:01:00Z", "signal", "RED", "ruleTrace", List.of("R-01"))),
                "overrides", List.of());
    }

    private static String textOf(byte[] bytes) throws IOException {
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            return new PDFTextStripper().getText(doc);
        }
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
}
