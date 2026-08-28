package com.sphinxfin.sphinx.core.gate;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.sphinxfin.sphinx.domain.GateResult;
import com.sphinxfin.sphinx.domain.Grade;
import com.sphinxfin.sphinx.domain.Judgment;
import com.sphinxfin.sphinx.domain.Signal;

import java.io.IOException;
import java.math.BigDecimal;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * F-GTE-001 룰 엔진. 소유: 강희진
 *
 * 입력은 구조화된 Judgment(AI 측정값)만 받는다. LLM 원문은 이 클래스에 도달하지 않는다
 * (P1 — AI는 측정, 룰은 결정). 판정 로직은 Java에 하드코딩하지 않고 resources/gate_rules.yaml
 * 에서 선언적으로 로드한다 — 룰 변경은 그 파일의 PR로만 이뤄지고 감사 대상이 된다.
 *
 * 평가는 파일에 적힌 순서대로 first-match-wins다. 즉 파일 순서가 곧 우선순위이며(RED 룰이
 * YELLOW·GREEN보다 앞), 게이트 판정은 예측 가능하다(기획서 4장 아키텍처 원칙). 어떤 룰도
 * 매칭되지 않으면 fail-closed로 RED를 반환한다(판매 게이트는 보수적으로).
 */
public class GateEngine {

    /** 파일에 매칭되는 룰이 없을 때의 fail-closed 판정에 남기는 트레이스 ID. */
    static final String DEFAULT_TRACE = "R-DEFAULT";

    private final List<Rule> rules;

    /** 프로덕션 경로: classpath의 gate_rules.yaml을 로드한다. */
    public GateEngine() {
        this(loadRules("/gate_rules.yaml"));
    }

    /** 테스트/DI용: 컴파일된 룰을 직접 주입한다. */
    GateEngine(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    /**
     * 재검증 상한 — {@code R-03}({@code reverifyFailed >= N})의 {@code N}. (이슈 #66)
     *
     * <h2>왜 여기서 내는가</h2>
     *
     * <p>같은 숫자가 {@code application.yml}({@code sphinx.scoring.max-reverify})에도 있었다.
     * 둘은 <b>같은 논리값이어야 한다</b> — 상한만큼 실패하면 게이트가 잡아야 하기 때문이다.
     * 한쪽만 바꾸면 상한과 게이트가 따로 논다(상한 3인데 게이트가 2에서 RED 면 3번째 재설명
     * 기회가 무의미해진다).
     *
     * <p>대조 테스트로 막고 있었는데, 그건 <b>어긋난 뒤에 잡는 것</b>이다. 고칠 자리가 둘이면
     * 언젠가 한 곳만 바뀐다. ADR-005 가 <i>"임계값의 단일 출처는 {@code gate_rules.yaml}"</i>
     * 로 정해 뒀으니 그 방향으로 접는다 — 룰이 숫자를 소유하고 서비스가 여기서 읽는다.
     *
     * <p>❗<b>룰이 없으면 던진다.</b> 기본값으로 떨어뜨리면 R-03 을 지운 파일이 조용히 돌고,
     * 재검증이 영원히 안 끝나거나 게이트가 안 잡는다. 로드 시점 fail-fast 가 이 클래스의
     * 규약이다(알 수 없는 조건도 그때 던진다).
     */
    public int reverifyThreshold() {
        for (Rule rule : rules) {
            Matcher m = Pattern.compile("reverifyFailed\\s*>=\\s*(\\d+)").matcher(rule.ifExpr());
            if (m.matches()) {
                return Integer.parseInt(m.group(1));
            }
        }
        throw new IllegalStateException(
                "gate_rules.yaml 에 reverifyFailed >= N 룰이 없다 — 재검증 상한을 정할 근거가 없다 (#66)");
    }

    /**
     * @param judgments          항목별 이해도 판정 목록(AI 측정값)
     * @param suitabilityMismatch 적합성 모순이 **확인**됐는가(F-DET-002 → R-02)
     * @param suitabilityUnknown  판정을 시도했으나 **확인하지 못했는가**(결정 10.9 → R-02b).
     *                            모순 없음과 다른 상태다 — 자세한 근거는 SuitabilityStatus 참고
     * @param reverifyFailed     재검증 실패 누적 횟수(F-INT-004, 항목당 최대 2회)
     */
    public GateResult judge(List<Judgment> judgments, boolean suitabilityMismatch,
                           boolean suitabilityUnknown, int reverifyFailed) {
        List<Grade> grades = judgments.stream().map(Judgment::grade).toList();
        List<BigDecimal> confidences = judgments.stream().map(Judgment::confidence).toList();
        Context ctx = new Context(grades, confidences, suitabilityMismatch,
                suitabilityUnknown, reverifyFailed);

        // 신호는 first-match-wins(파일 순서 = 우선순위). 트레이스는 그 신호를 낸 발화 룰을 전부
        // 남긴다 — 감사 시점에 "왜 이 신호였나"를 모든 사유로 설명하기 위함(예: YELLOW가
        // R-04(부분이해)와 R-05(저신뢰)에서 동시에 나오면 둘 다 기록). #10 결정.
        Signal winning = null;
        List<String> trace = new ArrayList<>();
        for (Rule rule : rules) {
            if (rule.predicate().test(ctx)) {
                if (winning == null) {
                    winning = rule.signal();
                }
                if (rule.signal() == winning) {
                    trace.add(rule.id());
                }
            }
        }
        if (winning == null) {
            return new GateResult(Signal.RED, List.of(DEFAULT_TRACE));   // fail-closed
        }
        return new GateResult(winning, List.copyOf(trace));
    }

    // ── 룰 로딩·컴파일 ────────────────────────────────────────────────

    static List<Rule> loadRules(String classpathResource) {
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        try (InputStream in = GateEngine.class.getResourceAsStream(classpathResource)) {
            if (in == null) {
                throw new IllegalStateException("게이트 룰 파일을 찾을 수 없다: " + classpathResource);
            }
            RulesFile file = yaml.readValue(in, RulesFile.class);
            if (file.rules == null || file.rules.isEmpty()) {
                throw new IllegalStateException("게이트 룰이 비어있다: " + classpathResource);
            }
            List<Rule> compiled = new ArrayList<>(file.rules.size());
            for (RawRule raw : file.rules) {
                compiled.add(new Rule(raw.id, raw.ifExpr, compile(raw.ifExpr), Signal.valueOf(raw.then)));
            }
            return compiled;
        } catch (IOException e) {
            throw new UncheckedIOException("게이트 룰 로드 실패: " + classpathResource, e);
        }
    }

    /**
     * gate_rules.yaml의 if 문자열을 타입드 술어로 컴파일한다. 지원하는 문법만
     * 허용하고, 알 수 없는 조건은 로드 시점에 예외를 던진다(감사 대상 파일의 오타를
     * 런타임까지 숨기지 않기 위해 fail-fast).
     */
    static Predicate<Context> compile(String ifExpr) {
        String e = ifExpr == null ? "" : ifExpr.trim();

        if (e.matches("suitabilityUnknown\\s*==\\s*true")) {
            return Context::suitabilityUnknown;
        }
        if (e.matches("suitabilityMismatch\\s*==\\s*true")) {
            return Context::suitabilityMismatch;
        }
        Matcher reverify = Pattern.compile("reverifyFailed\\s*>=\\s*(\\d+)").matcher(e);
        if (reverify.matches()) {
            int threshold = Integer.parseInt(reverify.group(1));
            return ctx -> ctx.reverifyFailed() >= threshold;
        }
        Matcher conf = Pattern.compile("anyConfidenceBelow\\s+(\\d*\\.?\\d+)").matcher(e);
        if (conf.matches()) {
            // 임계값을 문자열 그대로 BigDecimal 로 만든다. Double.parseDouble 을 거치면
            // 0.7 이 이진 근사값이 되고, 같은 "0.7" 을 비교하는데 판정이 표현 오차에 걸린다.
            BigDecimal threshold = new BigDecimal(conf.group(1));
            return ctx -> ctx.confidences().stream().anyMatch(c -> c.compareTo(threshold) < 0);
        }
        if (e.startsWith("anyGrade") && e.contains(" in ")) {
            Set<Grade> allowed = parseGradeList(e);
            return ctx -> ctx.grades().stream().anyMatch(allowed::contains);
        }
        if (e.startsWith("anyGrade")) {
            Grade g = parseSingleGrade(e);
            return ctx -> ctx.grades().contains(g);
        }
        if (e.startsWith("allGrade")) {
            Grade g = parseSingleGrade(e);
            return ctx -> !ctx.grades().isEmpty() && ctx.grades().stream().allMatch(x -> x == g);
        }
        throw new IllegalArgumentException("알 수 없는 게이트 룰 조건: " + ifExpr);
    }

    private static Grade parseSingleGrade(String expr) {
        Matcher m = Pattern.compile("'([A-Za-z0-9_]+)'").matcher(expr);
        if (!m.find()) {
            throw new IllegalArgumentException("등급 리터럴을 찾을 수 없다: " + expr);
        }
        return Grade.valueOf(m.group(1));
    }

    private static Set<Grade> parseGradeList(String expr) {
        Matcher m = Pattern.compile("'([A-Za-z0-9_]+)'").matcher(expr);
        Set<Grade> grades = new java.util.LinkedHashSet<>();
        while (m.find()) {
            grades.add(Grade.valueOf(m.group(1)));
        }
        if (grades.isEmpty()) {
            throw new IllegalArgumentException("등급 목록이 비어있다: " + expr);
        }
        return grades;
    }

    // ── 내부 타입 ─────────────────────────────────────────────────────

    /** 평가 컨텍스트: 룰이 참조하는 값의 전부. */
    record Context(List<Grade> grades, List<BigDecimal> confidences,
                   boolean suitabilityMismatch, boolean suitabilityUnknown,
                   int reverifyFailed) {}

    /** 컴파일된 룰. */
    /**
     * 컴파일된 룰. {@code ifExpr} 원문을 함께 든다 — 술어로 접고 나면 임계값을 되읽을 수
     * 없는데, {@link #reverifyThreshold()} 가 그 숫자를 필요로 한다(이슈 #66).
     */
    record Rule(String id, String ifExpr, Predicate<Context> predicate, Signal signal) {}

    /** gate_rules.yaml 역직렬화 형태. */
    private static final class RulesFile {
        public int version;
        public List<RawRule> rules;
    }

    private static final class RawRule {
        public String id;
        @JsonProperty("if")
        public String ifExpr;
        public String then;
    }
}
