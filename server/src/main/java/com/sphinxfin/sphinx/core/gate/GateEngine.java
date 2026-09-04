package com.sphinxfin.sphinx.core.gate;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.sphinxfin.sphinx.domain.GateResult;
import com.sphinxfin.sphinx.domain.Grade;
import com.sphinxfin.sphinx.domain.Judgment;
import com.sphinxfin.sphinx.domain.RuleRef;
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

    /**
     * 그때의 문면. 룰 파일에 없는 판정이라 라벨도 여기서 낸다 — 화면이 {@code R-DEFAULT} 만
     * 받으면 <b>왜 막혔는지</b>를 아무도 못 말한다(이슈 #320). 조건을 말하지 않는 것은
     * 다른 라벨과 같다.
     */
    static final RuleRef DEFAULT_RULE = new RuleRef(DEFAULT_TRACE, "판정 근거를 만들지 못했습니다");

    /** 룰을 직접 주입할 때 쓰는 버전 값 — 파일에서 온 게 아니라 "모른다" 는 뜻이다. */
    static final int UNVERSIONED = 0;

    private final List<Rule> rules;
    private final int rulesVersion;

    /** 프로덕션 경로: classpath의 gate_rules.yaml을 로드한다. */
    public GateEngine() {
        this(load("/gate_rules.yaml"));
    }

    /** 테스트/DI용: 컴파일된 룰을 직접 주입한다. 파일을 안 지나왔으므로 버전은 없다. */
    GateEngine(List<Rule> rules) {
        this(new Ruleset(UNVERSIONED, rules));
    }

    GateEngine(Ruleset ruleset) {
        this.rules = List.copyOf(ruleset.rules());
        this.rulesVersion = ruleset.version();
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
        return judge(judgments, suitabilityMismatch, suitabilityUnknown, reverifyFailed, 0);
    }

    /**
     * {@code unmeasured} 는 <b>물어봤는데 판정이 없는 항목 수</b>다 (이슈 #280 ②).
     *
     * <p>❗<b>이 값이 없던 동안 게이트가 분모를 몰랐다.</b> 13항목 중 12개가 U1 이고 1개가
     * 채점 실패(502)면 {@code judgments} 에 12건이 들어오고 <b>전부 U1 이라 R-06 이 GREEN</b>
     * 을 냈다. {@code SessionService} 의 {@code judgments().isEmpty()} 가드는 0건만 막는다 —
     * 부분은 열려 있었다.
     *
     * <p>그리고 조용했다. 기록에 남는 것은 {@code GateResult(signal, ruleTrace)} 뿐이라
     * 감사 시점에 GREEN 을 보면 <b>전 항목이 통과한 것으로 읽힌다.</b>
     *
     * <p>❗<b>"몇 항목이어야 하는가"(추출 결과)를 쓰지 않는다.</b> 그 값이 아직 목이다
     * ({@code MockData.RISK_ITEMS}). 대신 <b>질문을 보낸 항목</b>과 대조한다 — 물어본 것조차
     * 못 잰 상태가 제일 나쁘고, 그게 {@code #280} 이 보여준 실물이다. 추출이 붙으면 그 값으로
     * 바꾸는 것이 맞고, 그때까지도 이 구멍은 막힌다.
     */
    public GateResult judge(List<Judgment> judgments, boolean suitabilityMismatch,
                           boolean suitabilityUnknown, int reverifyFailed, int unmeasured) {
        return judge(judgments, suitabilityMismatch, suitabilityUnknown, reverifyFailed,
                unmeasured, 0);
    }

    /**
     * {@code repeatedAnswerUpgraded} 는 <b>재검증에서 직전과 사실상 같은 답을 냈는데 등급이
     * 올라간 항목 수</b>다 (이슈 #268 (d)).
     *
     * <p>❗<b>이 값이 없으면 그 세션이 GREEN 이다.</b> 게이트가 보는 것은 최종 등급뿐이라
     * 전 항목이 U1 이 되고 {@code R-06} 이 문다. 재설명이 이해를 올린 것이 아니라 채점이
     * 흔들린 것인데 통과하고, 판정이 서면 되돌릴 수 없다(P5 가 말하는 방향).
     *
     * <p><b>등급을 고치지 않는다</b>(P1). 측정은 그대로 두고 판정만 룰이 바꾼다 —
     * {@code R-07} 이 YELLOW 를 내서 재확인으로 보낸다. RED 가 아닌 이유는 비례다:
     * 표현을 다듬지 않고 같은 말을 다시 한 것이 곧 오해는 아니다.
     */
    public GateResult judge(List<Judgment> judgments, boolean suitabilityMismatch,
                           boolean suitabilityUnknown, int reverifyFailed, int unmeasured,
                           int repeatedAnswerUpgraded) {
        List<Grade> grades = judgments.stream().map(Judgment::grade).toList();
        List<BigDecimal> confidences = judgments.stream().map(Judgment::confidence).toList();
        Context ctx = new Context(grades, confidences, suitabilityMismatch,
                suitabilityUnknown, reverifyFailed, unmeasured, repeatedAnswerUpgraded);

        // 신호는 first-match-wins(파일 순서 = 우선순위). 트레이스는 그 신호를 낸 발화 룰을 전부
        // 남긴다 — 감사 시점에 "왜 이 신호였나"를 모든 사유로 설명하기 위함(예: YELLOW가
        // R-04(부분이해)와 R-05(저신뢰)에서 동시에 나오면 둘 다 기록). #10 결정.
        Signal winning = null;
        List<RuleRef> trace = new ArrayList<>();
        for (Rule rule : rules) {
            if (rule.predicate().test(ctx)) {
                if (winning == null) {
                    winning = rule.signal();
                }
                if (rule.signal() == winning) {
                    trace.add(rule.ref());
                }
            }
        }
        if (winning == null) {
            // fail-closed. 여기서도 입력을 싣는다 — 룰이 하나도 안 맞은 판정일수록
            // "무엇을 보고 그랬나" 가 남아야 한다.
            return new GateResult(Signal.RED, List.of(DEFAULT_RULE), unmeasured, rulesVersion);
        }
        return new GateResult(winning, List.copyOf(trace), unmeasured, rulesVersion);
    }

    // ── 룰 로딩·컴파일 ────────────────────────────────────────────────

    /** 파일에서 온 룰셋 — 룰과 그 룰을 정한 버전이 같이 다닌다. */
    record Ruleset(int version, List<Rule> rules) {}

    /** 버전이 필요 없는 호출부용. */
    static List<Rule> loadRules(String classpathResource) {
        return load(classpathResource).rules();
    }

    static Ruleset load(String classpathResource) {
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
                if (raw.label == null || raw.label.isBlank()) {
                    // 라벨 없는 룰이 들어오면 화면이 그 룰에 대해 아무 말도 못 한다.
                    // 로드 시점에 막는다 — 런타임까지 숨기면 판정이 난 뒤에야 드러난다.
                    throw new IllegalStateException("룰에 label 이 없다: " + raw.id);
                }
                compiled.add(new Rule(raw.id, raw.label, raw.ifExpr,
                        compile(raw.ifExpr), Signal.valueOf(raw.then)));
            }
            return new Ruleset(file.version, compiled);
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
        Matcher repeated = Pattern.compile("repeatedAnswerUpgraded\\s*>\\s*(\\d+)").matcher(e);
        if (repeated.matches()) {
            int limit = Integer.parseInt(repeated.group(1));
            return ctx -> ctx.repeatedAnswerUpgraded() > limit;
        }
        Matcher unmeasured = Pattern.compile("unmeasured\\s*>\\s*(\\d+)").matcher(e);
        if (unmeasured.matches()) {
            int limit = Integer.parseInt(unmeasured.group(1));
            return ctx -> ctx.unmeasured() > limit;
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
                   int reverifyFailed, int unmeasured, int repeatedAnswerUpgraded) {}

    /** 컴파일된 룰. */
    /**
     * 컴파일된 룰. {@code ifExpr} 원문을 함께 든다 — 술어로 접고 나면 임계값을 되읽을 수
     * 없는데, {@link #reverifyThreshold()} 가 그 숫자를 필요로 한다(이슈 #66).
     */
    record Rule(String id, String label, String ifExpr, Predicate<Context> predicate, Signal signal) {
        RuleRef ref() {
            return new RuleRef(id, label);
        }
    }

    /** gate_rules.yaml 역직렬화 형태. */
    private static final class RulesFile {
        public int version;
        public List<RawRule> rules;
    }

    private static final class RawRule {
        public String id;
        public String label;
        @JsonProperty("if")
        public String ifExpr;
        public String then;
    }
}
