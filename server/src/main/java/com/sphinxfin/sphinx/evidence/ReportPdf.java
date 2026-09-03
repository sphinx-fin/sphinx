package com.sphinxfin.sphinx.evidence;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.stereotype.Component;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.BreakIterator;
import java.time.Instant;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 이해 기록 리포트 PDF (F-GTE-004 2번). 소유: 정세현
 *
 * <h2>❗이 문서는 두 번째 진실이 아니다</h2>
 *
 * <p>여기서 그리는 것은 {@link ReportService#render(String)} 가 만든 <b>그 내용</b>뿐이다.
 * 값을 새로 만들거나 다르게 반올림하면 <b>해시가 가리키는 문서와 종이가 달라진다</b> —
 * 그 어긋남은 분쟁 시점까지 드러나지 않는다(ADR-003 · ADR-008).
 *
 * <p>그래서 {@code contentHash} 를 <b>지면에 찍는다.</b> 고객이 받은 종이와 불변 기록을
 * 대조할 수 있어야 하고, 계약이 요약본에도 같은 해시를 싣는 이유와 같다.
 *
 * <p>HTML→PDF 를 안 쓴 이유도 이것이다(이슈 #233). CSS 레이아웃 엔진을 거치면 엔진·폰트
 * 판본이 바뀔 때 같은 내용이 다른 지면이 되고, <b>렌더가 입력만의 함수가 아니게 된다.</b>
 *
 * <h2>폰트를 통째로 커밋하고, PDF 에는 쓴 글자만 넣는다</h2>
 *
 * <p>둘은 다른 얘기다(실측, 이슈 #233).
 *
 * <ul>
 *   <li><b>커밋하는 파일은 서브셋하지 않는다</b> — 미리 글자를 골라 둔 파일을 쓰면 그
 *       밖의 음절이 없다. 어떤 음절이 올지 우리가 못 정한다(고객 발화 원문을 싣는다).
 *       {@code git archive} 가 추적 파일만 담으므로 받아오는 방식도 안 된다.</li>
 *   <li><b>{@code embedSubset=true} 로 넣는다</b> — PDFBox 의 서브셋은 <b>런타임에 실제로
 *       그린 글자만</b> 전체 폰트에서 뽑는다. 희귀 음절도 그대로 나온다(KS X 1001 밖
 *       글자로 확인). {@code false} 면 교부 리포트 한 장이 1.2MB 다 — 실측 5.8KB 대비
 *       206배이고 얻는 것이 없다.</li>
 * </ul>
 *
 * <h2>❗그릴 수 없는 글자가 발행을 막지 않게 한다</h2>
 *
 * <p>폰트에 없는 글자를 그리면 PDFBox 가 {@code IllegalStateException} 을 던진다(두부로
 * 조용히 나오지 않는다 — 실측). 그런데 이 문서는 <b>고객 발화 원문</b>을 싣는다. 이모지나
 * 확장 한자가 하나 섞이면 <b>리포트 발행이 통째로 실패한다.</b>
 *
 * <p>그래서 그릴 수 없는 글자를 {@link #UNRENDERABLE} 로 바꾸고, <b>몇 자를 못 그렸는지
 * 지면에 적고 로그에 남긴다.</b> 조용히 바꾸지 않는 것이 요점이다 — 해시의 근거는 JSON
 * 이므로, 종이가 <i>"전부 그리지는 못했다"</i> 를 말하면 둘의 관계가 유지된다.
 */
@Slf4j
@Component
public class ReportPdf {

    /**
     * 폰트에 없는 글자를 대신하는 문자. 지면에서 눈에 띄어야 하므로 공백이 아니다.
     *
     * <p>❗<b>이 문자 자신이 폰트에 있어야 대체 경로가 성립한다.</b> {@link Painter#sanitize}
     * 가 모든 코드포인트를 검사하면서 자기가 넣는 이 문자는 검사하지 않으므로, 없는 폰트로
     * 바꾸면 <b>드문 글자가 든 리포트만 실패하는 것이 아니라 전부 실패한다</b> — 그리고 예외가
     * {@code U+25A1} 을 가리켜 <i>"왜 리포트가 네모 때문에 죽지"</i> 로 읽힌다.
     * {@link #loadFont} 가 그 전제를 로드 때 확인하고, {@code ReportPdfTest} 가 지금 쓰는
     * 폰트에 이 글자가 있다는 것을 단정한다(PR #244 리뷰, 강희진이 라틴 전용 폰트로 실측).
     */
    static final char UNRENDERABLE = '□';   // U+25A1

    private static final String FONT = "/fonts/Pretendard-Regular.ttf";

    private static final float MARGIN = 50f;
    private static final float SIZE_TITLE = 16f;
    private static final float SIZE_HEAD = 11f;
    private static final float SIZE_BODY = 9.5f;
    private static final float LEADING = 1.5f;

    /**
     * 내용 → PDF 바이트. <b>부작용이 없고 입력만의 함수다.</b>
     *
     * @param content     {@link ReportService#render(String)} 의 결과
     * @param contentHash 그 내용의 해시. 지면에 찍힌다
     * @param generatedAt 발행 기록의 시각. <b>지금 시각이 아니다</b> — 아래 결정성 참조
     */
    public byte[] render(Map<String, Object> content, String contentHash, Instant generatedAt) {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            stampDeterministically(doc, contentHash, generatedAt);
            PDType0Font font = loadFont(doc);
            Painter p = new Painter(doc, font);

            p.text(SIZE_TITLE, "이해 기록 리포트");
            p.gap(6);
            p.text(SIZE_BODY, "세션 " + str(content.get("sessionId")));
            // ❗해시를 지면에 찍는 줄. 이 문서가 불변 기록과 대조 가능한 이유 전부다.
            p.text(SIZE_BODY, "contentHash " + contentHash);
            p.rule();

            p.text(SIZE_HEAD, "이해항목별 판정 이력");
            for (Map<String, Object> item : list(content.get("items"))) {
                p.gap(4);
                p.text(SIZE_BODY, "· " + str(item.get("itemId")));
                for (Map<String, Object> h : list(item.get("history"))) {
                    p.indented(SIZE_BODY, historyLine(h));
                }
            }

            p.gap(8);
            p.text(SIZE_HEAD, "게이트 판정 변천");
            List<Map<String, Object>> gate = list(content.get("gateHistory"));
            if (gate.isEmpty()) {
                p.indented(SIZE_BODY, "기록 없음");
            }
            for (Map<String, Object> g : gate) {
                p.indented(SIZE_BODY, gateLine(g));
            }

            p.gap(8);
            p.text(SIZE_HEAD, "오버라이드 승인");
            List<Map<String, Object>> overrides = list(content.get("overrides"));
            if (overrides.isEmpty()) {
                p.indented(SIZE_BODY, "없음");
            }
            for (Map<String, Object> o : overrides) {
                p.indented(SIZE_BODY, str(o.get("at")) + "  승인자 " + str(o.get("approver"))
                        + "  사유 " + str(o.get("reason")));
            }

            // ❗못 그린 글자를 지면이 말한다. 안 적으면 종이가 조용히 원문과 달라진다.
            if (p.unrenderable > 0) {
                p.gap(8);
                p.text(SIZE_BODY, "❗이 문서의 서체로 표현할 수 없는 글자 " + p.unrenderable
                        + "자를 '" + UNRENDERABLE + "' 로 대체했습니다. 원문은 위 contentHash 의 기록에 있습니다.");
                log.warn("리포트 PDF: 표현 불가 글자 {}자를 대체했다 — session={} hash={}",
                        p.unrenderable, str(content.get("sessionId")), contentHash);
            }

            p.close();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("리포트 PDF 를 만들지 못했다", e);
        }
    }

    /**
     * ❗<b>렌더를 입력만의 함수로 만든다.</b> 안 하면 같은 내용이 실행마다 다른 바이트가 된다 —
     * PDFBox 가 생성·수정 시각과 파일 식별자({@code /ID})를 <b>지금 시각</b>으로 넣기 때문이다.
     *
     * <p>그러면 <i>"해시가 가리키는 그 지면을 다시 그려서 맞춰 본다"</i> 를 못 한다. ADR-004 가
     * 발행 기록을 안 지우는 이유와 같은 자리다 — 교부 시점에 무엇이 적혀 있었는지에 답하려면
     * 그 지면이 재현되어야 한다.
     *
     * <p>시각은 <b>발행 기록의 시각</b>을 쓴다(지금 시각이 아니다). 같은 내용을 다시 발행하면
     * 기존 것을 돌려주므로(멱등) 이 값도 안 바뀐다. 파일 식별자는 {@code contentHash} 에서
     * 만든다 — 내용이 같으면 같고, 다르면 다르다.
     */
    private static void stampDeterministically(PDDocument doc, String contentHash, Instant generatedAt) {
        Calendar at = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        at.setTimeInMillis(generatedAt.toEpochMilli());

        PDDocumentInformation info = new PDDocumentInformation();
        info.setTitle("이해 기록 리포트");
        info.setProducer("SphinX");          // PDFBox 기본값은 판본이 섞여 들어온다
        info.setCreator("SphinX");
        info.setCreationDate(at);
        info.setModificationDate(at);
        doc.setDocumentInformation(info);

        COSString id = new COSString(contentHash.getBytes(StandardCharsets.UTF_8));
        doc.getDocument().setDocumentID(new COSArray(List.of(id, id)));
    }

    private static String historyLine(Map<String, Object> h) {
        StringBuilder sb = new StringBuilder();
        sb.append(str(h.get("at"))).append("  ").append(str(h.get("grade")));
        Object q = h.get("askedQuestion");
        if (q != null) {
            sb.append("  질문: ").append(str(q));
        }
        Object reason = h.get("reason");
        if (reason != null) {
            sb.append("  사유: ").append(str(reason));
        }
        return sb.toString();
    }

    private PDType0Font loadFont(PDDocument doc) throws IOException {
        try (InputStream in = ReportPdf.class.getResourceAsStream(FONT)) {
            if (in == null) {
                // 폰트가 없으면 한글이 통째로 안 나온다. 조용히 기본 폰트로 떨어지지 않는다 —
                // 그 PDF 는 열어 보기 전까지 정상으로 보인다.
                throw new IOException("리포트 폰트가 클래스패스에 없다: " + FONT
                        + " — server/src/main/resources/fonts/ 를 본다 (이슈 #233)");
            }
            // ❗embedSubset=true. 클래스 javadoc 참조 — 커밋 파일을 서브셋하지 않는 것과 다른 얘기다.
            PDType0Font font = PDType0Font.load(doc, in, true);
            assertCanRenderTheReplacement(font);
            return font;
        }
    }

    /**
     * ❗<b>대체 문자가 없는 폰트는 여기서 거부한다.</b> 조용히 내려가지 않는 이유는 이 문서의
     * 성격이다 — 대체 문자가 없다는 것은 <i>"폰트 선택이 잘못됐다"</i> 이지 <i>"이 리포트만 좀
     * 흐리다"</i> 가 아니다. {@code '?'} 로 내려가는 길도 있지만, 그러면 교부 문서가 조용히
     * 나빠지고 그 사실이 아무 데도 안 남는다(PR #244 리뷰에서 같은 판단).
     */
    private static void assertCanRenderTheReplacement(PDType0Font font) {
        try {
            font.getStringWidth(String.valueOf(UNRENDERABLE));
        } catch (IOException | IllegalArgumentException | IllegalStateException e) {
            // ❗IOException 으로 던지지 않는다 — 바깥 catch 가 "리포트 PDF 를 만들지 못했다"
            // 로 감싸서 **원인이 한 겹 아래로 들어간다.** 폰트를 바꾼 사람이 첫 줄에서
            // 알아야 하는 종류라 그대로 올라가게 둔다.
            throw new IllegalStateException(String.format(
                    "이 폰트에는 대체 문자(U+%04X '%s')가 없다 — 그릴 수 없는 글자를 대신할 수 "
                    + "없으므로 대체 경로가 성립하지 않는다. 폰트를 바꿨으면 그 글자가 있는지 "
                    + "먼저 본다 (이슈 #233 · PR #244)", (int) UNRENDERABLE, UNRENDERABLE), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object o) {
        return o instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }

    /**
     * 게이트 판정 한 줄. <b>신호만이 아니라 그 신호를 만든 것을 적는다.</b>
     *
     * <p>시각과 신호뿐이면 종이가 <i>"RED 였다"</i> 까지만 말한다. 데모에서 증명해야 하는 것은
     * <b>왜 RED 였는가</b>이고, 그 답은 어느 룰이 걸렸는지와 그 판정이 무엇을 보고 내려졌는지다.
     * 기록과 JSON 은 넷을 다 들고 있는데 지면만 하나였다(이슈 #295 · PR #299 리뷰).
     *
     * <p>교부되는 것은 종이다 — 화면은 닫히면 사라진다.
     */
    private static String gateLine(Map<String, Object> g) {
        StringBuilder sb = new StringBuilder();
        sb.append(str(g.get("at"))).append("  ").append(str(g.get("signal")));
        List<?> trace = g.get("ruleTrace") instanceof List<?> l ? l : List.of();
        sb.append("  걸린 룰 ").append(trace.isEmpty() ? "없음"
                : trace.stream().map(ReportPdf::ruleText).collect(Collectors.joining(" · ")));

        // 판정을 만든 입력. 이 키가 없거나 null 인 항목은 **이 필드가 생기기 전 기록**이다 —
        // append-only 라 옛 세션의 항목은 고쳐지지 않는다. "미측정 -" 로 찍으면 0(전부 쟀다)과
        // 구별되지 않고, 아무 말도 안 하면 종이가 그 사실을 숨긴다.
        Object unmeasured = g.get("unmeasured");
        Object rulesVersion = g.get("rulesVersion");
        if (unmeasured == null && rulesVersion == null) {
            sb.append("  (판정 입력 미기재 — 이 항목이 적힐 때는 기록하지 않았다)");
            return sb.toString();
        }
        sb.append("  미측정 ").append(str(unmeasured)).append("건");
        // ❗0 을 "v0" 으로 찍지 않는다. rulesVersion 0 은 버전이 0 인 룰셋이 아니라
        // 파일을 안 지나온 룰이다(GateEngine.UNVERSIONED). 종이가 존재하지 않는 버전을
        // 말하면 그 문서로는 어느 룰셋으로 판정했는지 되짚을 수 없다.
        sb.append("  룰셋 ").append(Integer.valueOf(0).equals(rulesVersion)
                ? "미버전(룰 파일을 지나지 않았다)" : "v" + str(rulesVersion));
        return sb.toString();
    }

    /**
     * 룰 하나 — {@code R-00 채점되지 않은 항목이 있습니다}.
     *
     * <p>❗<b>ID 만 찍으면 종이가 {@code R-00} 이라는 코드로 근거를 말한다</b>(이슈 #320).
     * 문면만 찍는 것도 답이 아니다 — 감사·심사에서는 룰 ID 자체가 근거이고, 문면은
     * {@code gate_rules.yaml} 이 바뀌면 달라진다.
     *
     * <p>문면이 없는 항목은 <b>이 필드가 생기기 전 기록</b>이다. append-only 라 못 고치므로
     * ID 만 찍는다 — 없는 문면을 지어내지 않는다.
     */
    private static String ruleText(Object o) {
        if (o instanceof Map<?, ?> m) {
            Object id = m.get("id");
            Object label = m.get("label");
            return label == null ? str(id) : str(id) + " " + str(label);
        }
        return str(o);   // 옛 기록은 문자열 ID 였다
    }

    private static String str(Object o) {
        return o == null ? "-" : String.valueOf(o);
    }

    /**
     * 한 장씩 그리며 내려간다. 줄바꿈과 표현 불가 글자를 여기서 다룬다.
     *
     * <p>줄바꿈은 {@link BreakIterator#getLineInstance(Locale)} 에 맡긴다. 한글은 <b>음절
     * 단위</b>로 끊는 것이 한국어 조판이고 JDK 가 그대로 해 준다 — 라틴 낱말은 통째로,
     * 문장부호는 앞 글자에 붙여서 끊는다. 글자 단위로 자르면 {@code ELS} 가 갈리고
     * 마침표가 다음 줄로 떨어진다.
     */
    private static final class Painter implements AutoCloseable {
        private final PDDocument doc;
        private final PDType0Font font;
        private final Map<Integer, Boolean> renderable = new HashMap<>();
        private PDPage page;
        private PDPageContentStream cs;
        private float y;
        private int unrenderable;

        Painter(PDDocument doc, PDType0Font font) throws IOException {
            this.doc = doc;
            this.font = font;
            newPage();
        }

        private void newPage() throws IOException {
            if (cs != null) {
                cs.close();
            }
            page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            cs = new PDPageContentStream(doc, page);
            y = page.getMediaBox().getHeight() - MARGIN;
        }

        void gap(float h) {
            y -= h;
        }

        void text(float size, String raw) throws IOException {
            write(size, sanitize(raw), MARGIN);
        }

        void indented(float size, String raw) throws IOException {
            write(size, sanitize(raw), MARGIN + 14f);
        }

        void rule() throws IOException {
            y -= 6;
            cs.moveTo(MARGIN, y);
            cs.lineTo(page.getMediaBox().getWidth() - MARGIN, y);
            cs.stroke();
            y -= 10;
        }

        private void write(float size, String text, float x) throws IOException {
            float width = page.getMediaBox().getWidth() - x - MARGIN;
            for (String line : wrap(text, size, width)) {
                if (y < MARGIN) {
                    newPage();
                }
                cs.beginText();
                cs.setFont(font, size);
                cs.newLineAtOffset(x, y);
                cs.showText(line);
                cs.endText();
                y -= size * LEADING;
            }
        }

        private List<String> wrap(String text, float size, float width) throws IOException {
            List<String> lines = new ArrayList<>();
            BreakIterator it = BreakIterator.getLineInstance(Locale.KOREAN);
            it.setText(text);
            StringBuilder line = new StringBuilder();
            int prev = it.first();
            for (int i = it.next(); i != BreakIterator.DONE; prev = i, i = it.next()) {
                String chunk = text.substring(prev, i);
                if (!line.isEmpty() && widthOf(line + chunk, size) > width) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                line.append(chunk);
            }
            if (!line.isEmpty() || lines.isEmpty()) {
                lines.add(line.toString());
            }
            return lines;
        }

        private float widthOf(String s, float size) throws IOException {
            return font.getStringWidth(s) / 1000f * size;
        }

        /** 폰트가 못 그리는 글자를 {@link #UNRENDERABLE} 로 바꾸고 개수를 센다. */
        private String sanitize(String raw) {
            StringBuilder sb = new StringBuilder(raw.length());
            raw.codePoints().forEach(cp -> {
                if (cp == '\n' || cp == '\r' || cp == '\t') {
                    sb.append(' ');
                } else if (renderable.computeIfAbsent(cp, this::canRender)) {
                    sb.appendCodePoint(cp);
                } else {
                    sb.append(UNRENDERABLE);
                    unrenderable++;
                }
            });
            return sb.toString();
        }

        private boolean canRender(int cp) {
            try {
                font.getStringWidth(new String(Character.toChars(cp)));
                return true;
            } catch (IOException | IllegalArgumentException | IllegalStateException e) {
                return false;
            }
        }

        @Override
        public void close() throws IOException {
            if (cs != null) {
                cs.close();
                cs = null;
            }
        }
    }
}
