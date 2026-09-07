package com.sphinxfin.sphinx.core.ops;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★ <b>{@code /ops/status} 응답에 고객 데이터가 실리면 여기서 빨개진다.</b> 소유: 강희진
 *
 * <h2>왜 이 그물이 필요한가 — 권한 설계 전체가 이 전제 하나에 걸려 있다</h2>
 *
 * <p>{@code ops:status:read} 를 ADMIN 에게 준 근거는 <i>"운영이라서"</i> 가 아니라
 * <b>응답에 데이터가 없어서</b>다. ADR-001 데이터 범위 표가 ADMIN 에게 닫아 둔 것은
 * <b>개별 세션과 집계</b> — 고객이 무엇을 오해했는가 — 이고, 세션 수 하나만 얹어도
 * 이 엔드포인트가 <b>그 우회 경로</b>가 된다.
 *
 * <p>그런데 그 전제를 지키는 것이 <b>리뷰뿐</b>이었다({@code #522} 리뷰가 짚은 자리).
 * {@code note} 와 {@code facts[].value} 가 자유 문자열이라 <i>"진행 중 세션 3건"</i> 을
 * 넣는 데 문법적 저항이 하나도 없다.
 *
 * <h2>어떻게 보는가 — 타입 의존성을 대조한다</h2>
 *
 * <p>{@link OpsStatus} 가 <b>도달할 수 있는 타입 전부</b>를 재귀로 모아, 세션·판정·집계
 * 계열이 하나라도 있으면 실패시킨다. 누가 세션 수를 얹으려고 {@code SessionRepository} 의
 * 결과를 응답 타입에 넣으면 컴파일 의존성이 늘어나 빨강이 된다.
 *
 * <p>❗<b>문자열로 우회하는 것은 못 막는다.</b> {@code new Fact("세션", "3건")} 은 이
 * 테스트를 통과한다 — 이 그물이 닫는 것은 <b>실수로 얹는 경로</b>이고, 일부러 넣는 것은
 * 정책 주석과 리뷰가 막는다. 이 한계를 여기 적어 두는 이유는, 초록을 보고 <i>"검사가
 * 다 하고 있다"</i> 로 읽으면 정작 리뷰가 느슨해지기 때문이다.
 *
 * <p>{@code CorePackageBoundaryTest}·{@code ErrorCodeContractTest} 와 같은 계열의 단정이다 —
 * 문서와 리뷰에만 있는 규약을 코드가 대조하게 만든다.
 */
@DisplayName("/ops/status 응답에 고객 데이터가 없다")
class OpsStatusHasNoCustomerDataTest {

    /**
     * 고객 데이터가 사는 패키지. <b>이름이 아니라 패키지로 막는다</b> — 클래스 이름을
     * 열거하면 새로 생긴 타입이 목록에 없어서 통과한다.
     */
    private static final List<String> FORBIDDEN_PACKAGES = List.of(
            "com.sphinxfin.sphinx.core.session",   // Session·발화·오버라이드
            "com.sphinxfin.sphinx.aggregate",      // 집계 (ADR-001 이 ADMIN 에 닫은 쪽)
            "com.sphinxfin.sphinx.evidence",       // 불변 기록 (판정 근거·발화 인용)
            "com.sphinxfin.sphinx.simulator");     // 금액 계산 결과

    /**
     * {@code domain/} 은 통째로 막을 수 없다 — 거기에 고객 데이터가 아닌 타입도 산다.
     * 판정·측정 계열만 이름으로 막는다.
     */
    private static final List<String> FORBIDDEN_TYPES = List.of(
            "com.sphinxfin.sphinx.domain.Judgment",
            "com.sphinxfin.sphinx.domain.InputMeta",
            "com.sphinxfin.sphinx.domain.GateResult",
            "com.sphinxfin.sphinx.domain.Utterance");

    @Test
    @DisplayName("❗응답 타입이 세션·판정·집계 타입을 하나도 참조하지 않는다 — ADMIN 그랜트의 근거다")
    void theResponseCannotReachCustomerData() {
        Set<Class<?>> reachable = reachableFrom(OpsStatus.class);

        Set<String> offending = new TreeSet<>();
        for (Class<?> type : reachable) {
            String name = type.getName();
            if (FORBIDDEN_PACKAGES.stream().anyMatch(name::startsWith)
                    || FORBIDDEN_TYPES.contains(name)) {
                offending.add(name);
            }
        }

        assertThat(offending)
                .as("""
                    /ops/status 응답이 고객 데이터 타입에 닿는다. `ops:status:read` 를 ADMIN 에게
                    준 근거가 «응답에 데이터가 없다» 하나이므로, 이건 정책을 옮겨서 고칠 것이
                    아니라 **응답에서 값을 빼서** 고친다 (ADR-001 · 기획 7-4).""")
                .isEmpty();
    }

    /**
     * ★ <b>탐색이 실제로 돌았는지 잰다.</b>
     *
     * <p>위 단정은 도달 집합이 비어도 <b>조용히 통과</b>한다 — 리플렉션이 낡거나 타입이
     * 레코드가 아니게 되면 0개를 검사하고 초록이 된다. 그게 이 레포에서 반복된 실패
     * 양식이라({@code WebTypesMirrorContractTest} 가 같은 이유로 짝 개수를 잰다) 여기서도 센다.
     */
    @Test
    @DisplayName("★ 탐색이 응답 타입 전부를 실제로 돈다 — 0개를 검사하고 통과하면 그물이 없는 것이다")
    void theTraversalItselfIsMeasured() {
        Set<Class<?>> reachable = reachableFrom(OpsStatus.class);

        assertThat(reachable)
                .as("응답 타입을 하나도 못 돌았다 — OpsStatus 가 레코드가 아니게 됐거나 "
                        + "리플렉션이 낡았다. 고치기 전까지 위 단정은 아무것도 안 잰다")
                .contains(OpsStatus.Deployment.class, OpsStatus.Component.class,
                        OpsStatus.Fact.class, OpsStatus.Health.class);
    }

    /** 레코드 컴포넌트를 타고 도달하는 타입 전부. 제네릭 인자({@code List<Component>})도 푼다. */
    private static Set<Class<?>> reachableFrom(Class<?> root) {
        Set<Class<?>> seen = new HashSet<>();
        Deque<Class<?>> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            Class<?> current = queue.poll();
            if (current == null || !seen.add(current) || current.isPrimitive()
                    || current.getName().startsWith("java.")) {
                continue;
            }
            if (!current.isRecord()) {
                continue;
            }
            for (RecordComponent component : current.getRecordComponents()) {
                for (Class<?> type : flatten(component.getGenericType())) {
                    queue.add(type);
                }
            }
        }
        seen.remove(root);
        return seen;
    }

    /** {@code List<Fact>} → {@code List}, {@code Fact}. 원시·java.* 는 호출부가 건너뛴다. */
    private static List<Class<?>> flatten(Type type) {
        List<Class<?>> out = new ArrayList<>();
        if (type instanceof Class<?> clazz) {
            out.add(clazz);
        } else if (type instanceof ParameterizedType parameterized) {
            out.addAll(flatten(parameterized.getRawType()));
            for (Type argument : parameterized.getActualTypeArguments()) {
                out.addAll(flatten(argument));
            }
        }
        return out;
    }
}
