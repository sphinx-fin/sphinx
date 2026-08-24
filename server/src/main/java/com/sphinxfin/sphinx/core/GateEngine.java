package com.sphinxfin.sphinx.core;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.sphinxfin.sphinx.domain.GateResult;
import com.sphinxfin.sphinx.domain.Grade;
import com.sphinxfin.sphinx.domain.Judgment;
import com.sphinxfin.sphinx.domain.Signal;

import java.io.IOException;
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
     * @param judgments          항목별 이해도 판정 목록(AI 측정값)
     * @param suitabilityMismatch 적합성 설문 vs 발화 모순 여부(F-DET-002)
     * @param reverifyFailed     재검증 실패 누적 횟수(F-INT-004, 항목당 최대 2회)
     */
    public GateResult judge(List<Judgment> judgments, boolean suitabilityMismatch, int reverifyFailed) {
        List<Grade> grades = judgments.stream().map(Judgment::grade).toList();
        Context ctx = new Context(grades, suitabilityMismatch, reverifyFailed);
        for (Rule rule : rules) {
            if (rule.predicate().test(ctx)) {
                return new GateResult(rule.signal(), List.of(rule.id()));
            }
        }
        return new GateResult(Signal.RED, List.of(DEFAULT_TRACE));
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
                compiled.add(new Rule(raw.id, compile(raw.ifExpr), Signal.valueOf(raw.then)));
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

        if (e.matches("suitabilityMismatch\\s*==\\s*true")) {
            return Context::suitabilityMismatch;
        }
        Matcher reverify = Pattern.compile("reverifyFailed\\s*>=\\s*(\\d+)").matcher(e);
        if (reverify.matches()) {
            int threshold = Integer.parseInt(reverify.group(1));
            return ctx -> ctx.reverifyFailed() >= threshold;
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
    record Context(List<Grade> grades, boolean suitabilityMismatch, int reverifyFailed) {}

    /** 컴파일된 룰. */
    record Rule(String id, Predicate<Context> predicate, Signal signal) {}

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
