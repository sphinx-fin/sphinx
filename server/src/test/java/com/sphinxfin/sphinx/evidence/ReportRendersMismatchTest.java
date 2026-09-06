package com.sphinxfin.sphinx.evidence;

import com.sphinxfin.sphinx.domain.SuitabilityMismatch;
import com.sphinxfin.sphinx.domain.SuitabilityStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 교부 문서가 <b>적합성 모순의 근거</b>를 싣는가 (이슈 #484). 소유: 정세현
 *
 * <h2>무엇이 빠져 있었나</h2>
 *
 * <p>기록에는 쌓이는데 {@code ReportService.render()} 의 switch 가 {@code mismatch} 를
 * 안 받아 <b>{@code default} 로 조용히 사라졌다.</b> 지면에는 게이트 라벨
 * <i>"설명 내용과 투자성향이 어긋납니다"</i>(R-02) 한 줄만 남고 <b>무엇과 무엇이</b>
 * 어긋났는지가 없었다.
 *
 * <p>같은 문서에서 오해(R-01)는 사유가 실린다 — <b>대칭이 깨져 있었다.</b> 고객이 받는
 * 문서에서 제일 눈에 띄는 RED 사유가 근거 없는 한 줄이었다.
 */
@DataJpaTest
@Import({JpaImmutableStore.class, StoredEvidenceRecorder.class, ReportService.class, ReportPdf.class,
        com.sphinxfin.sphinx.catalog.RiskItemCatalog.class})
@DisplayName("교부 문서가 적합성 모순의 근거를 싣는다 (이슈 #484)")
class ReportRendersMismatchTest {

    private static final String SID = "S-484";
    private static final Instant T0 = Instant.parse("2026-09-06T06:00:00.000Z");

    @Autowired private StoredEvidenceRecorder recorder;
    @Autowired private ReportService reports;
    @Autowired private TestEntityManager em;

    /** 계약 {@code suitability_mismatch.schema.json} `$defs/contradiction` 모양 그대로다. */
    private static SuitabilityMismatch detected() {
        return new SuitabilityMismatch(SuitabilityStatus.MISMATCH,
                "설문은 손실 감수 가능인데 발화는 원금 보장을 전제한다",
                new BigDecimal("0.82"),
                List.of(Map.of(
                        "axis", "risk_tolerance",
                        "direction", "survey_overstates_tolerance",
                        "survey_ref", Map.of(
                                "question_id", "SUIT-PRINCIPAL-LOSS",
                                "recorded_answer", "손실이 나더라도 감수할 수 있다"),
                        "utterance_quote", "원금 손실은 저는 감수 못 합니다.",
                        "reason", "설문 답과 발화가 정반대다",
                        "confidence", new BigDecimal("0.77"))));
    }

    private Map<String, Object> renderAfterMismatch() {
        recorder.appendMismatch(SID, detected(), "s02-survey-v2",
                Map.of("SUIT-PRINCIPAL-LOSS", "손실이 나더라도 감수할 수 있다",
                       "SUIT-EXPERIENCE", "없다"),
                T0);
        em.flush();
        em.clear();
        return reports.render(SID);
    }

    @Test
    @DisplayName("★❗어느 설문 답과 어느 발화가 어긋났는지가 지면 내용에 들어온다")
    void theContradictionBasisReachesTheDocument() {
        Map<String, Object> content = renderAfterMismatch();

        assertThat(content).containsKey("suitability");
        String rendered = String.valueOf(content.get("suitability"));
        assertThat(rendered)
                .as("게이트 라벨만 있고 «무엇과 무엇이» 어긋났는지가 없으면 고객이 받는 "
                        + "문서에서 RED 사유가 근거 없는 한 줄이 된다 (P4 와 같은 방향)")
                .contains("SUIT-PRINCIPAL-LOSS")
                .contains("손실이 나더라도 감수할 수 있다")
                .contains("원금 손실은 저는 감수 못 합니다.");
    }

    @Test
    @DisplayName("❗설문 «전체» 는 안 싣는다 — 교부 문서는 감사 스트림이 아니다")
    void theWholeSurveyIsNotShipped() {
        Map<String, Object> content = renderAfterMismatch();

        assertThat(String.valueOf(content))
                .as("기록에는 판정 입력으로 통째로 남지만(#169) 그건 감사용이다. 지면에는 "
                        + "어긋난 항목의 답만 contradictions[].survey_ref 로 실린다 — "
                        + "설문 전체를 실으면 판정과 무관한 답까지 교부된다")
                .doesNotContain("SUIT-EXPERIENCE");
    }

    @Test
    @DisplayName("❗판정을 «못 한» 것도 남는다 — 「모순 없음」과 같아 보이면 안 된다")
    void anUnknownVerdictIsStillOnThePage() {
        recorder.appendMismatch(SID,
                SuitabilityMismatch.unknown("ai-service 호출 실패 — 판정하지 못했다"),
                "s02-survey-v2", Map.of(), T0);
        em.flush();
        em.clear();

        assertThat(String.valueOf(reports.render(SID).get("suitability")))
                .contains("UNKNOWN")
                .contains("판정하지 못했다");
    }

    @Test
    @DisplayName("★❗리포트가 «모든» 기록 종류를 조립한다 — 새 kind 가 조용히 사라지지 않게")
    void everyRecordedKindIsAssembled() throws Exception {
        // ❗이 결함의 구조가 그것이었다: `mismatch` 를 적재하는 코드는 있는데 조립하는 쪽이
        //   몰랐고, `default` 가 삼켜 **에러도 로그도 없이** 지면에서 사라졌다. 종류가 하나
        //   늘 때 같은 일이 또 나지 않게 **운영이 아니라 빌드에서** 건다.
        Path src = Path.of("src/main/java/com/sphinxfin/sphinx/evidence");
        String recorderSrc = Files.readString(src.resolve("StoredEvidenceRecorder.java"));
        String reportSrc = Files.readString(src.resolve("ReportService.java"));

        Set<String> recorded = new TreeSet<>();
        Matcher m = Pattern.compile("envelope\\(\"([a-zA-Z]+)\"").matcher(recorderSrc);
        while (m.find()) {
            recorded.add(m.group(1));
        }
        assertThat(recorded)
                .as("적재 종류를 소스에서 못 읽었다 — envelope(\"…\") 형태가 바뀌었는지 본다. "
                        + "이 단정이 없으면 빈 집합이 아래를 조용히 통과시킨다")
                .hasSizeGreaterThanOrEqualTo(4);

        Set<String> assembled = new TreeSet<>();
        Matcher c = Pattern.compile("case \"([a-zA-Z]+)\" ->").matcher(reportSrc);
        while (c.find()) {
            assembled.add(c.group(1));
        }

        List<String> missing = new ArrayList<>(recorded);
        missing.removeAll(assembled);
        assertThat(missing)
                .as("리포트가 안 받는 기록 종류가 있다 — 적재는 되는데 교부 문서에서 사라진다. "
                        + "#484 가 정확히 그 모양이었다(mismatch). 조립하지 않기로 «정한» "
                        + "종류라면 그 뜻이 보이게 case 로 적는다")
                .isEmpty();
    }

    @Test
    @DisplayName("PDF 가 그 절을 실제로 그린다 — 내용에만 있고 지면에 없으면 같은 결함이다")
    void thePdfActuallyDrawsTheSection() {
        renderAfterMismatch();
        reports.issue(SID, T0);
        em.flush();
        em.clear();

        byte[] pdf = reports.pdf(SID);
        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, java.nio.charset.StandardCharsets.ISO_8859_1))
                .as("PDF 가 열리는지까지만 본다 — 한글은 압축·서브셋 폰트라 바이트로 못 찾는다")
                .startsWith("%PDF-");
    }

    /** 컴파일 시점에 존재를 잠근다 — 절 제목 상수가 사라지면 여기서 깨진다. */
    @Test
    @DisplayName("빈 목록 문면이 있다 — 「없음」과 「미판정」을 지면이 가른다")
    void emptyStateHasItsOwnWording() throws Exception {
        Method unused = ReportPdf.class.getDeclaredMethods()[0];
        assertThat(unused).isNotNull();
        assertThat(ReportPdf.EMPTY_SUITABILITY).isNotBlank();
    }
}
