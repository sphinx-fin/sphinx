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
 * <h2>❗어떻게 보는가 — <b>허용 목록</b>이다 (금지 목록이 아니다)</h2>
 *
 * <p>{@link OpsStatus} 가 <b>도달할 수 있는 타입 전부</b>를 재귀로 모아, <b>허용된 것
 * 외에 하나라도 있으면</b> 실패시킨다. 허용되는 것은 {@code core.ops} 안에 선언된 것과
 * {@link #ALLOWED} 의 기본형뿐이다.
 *
 * <p>처음에는 <i>금지 목록</i>이었다 — 세션·집계 패키지 + {@code domain/} 의 판정 타입 넷을
 * 이름으로 열거했다. <b>PR #531 리뷰가 변이로 구멍 둘을 찾았다.</b>
 *
 * <pre>
 * 변이 A   OpsStatus 가 core.session.Session 을 참조          → 빨강 (설계대로)
 * 변이 B-1 OpsStatus 가 domain.SuitabilityMismatch 를 참조     → ❗초록
 * 변이 B-2 OpsStatus 에 Map&lt;String, Object&gt; 필드         → ❗초록
 * </pre>
 *
 * <p><b>B-1 은 이 파일이 스스로 비판한 방식이었다</b> — 아래 {@link #FORBIDDEN_ONE_OFF} 의
 * 주석이 <i>"이름을 열거하면 새로 생긴 타입이 목록에 없어서 통과한다"</i> 라고 적고 있었는데,
 * 정작 {@code domain/} 쪽을 그 방식으로 막았다. 그리고 하필 {@code SuitabilityMismatch} 가
 * {@code List<Map<String, Object>> contradictions} 를 들고 그 Map 안에
 * <b>{@code utterance_quote} — 고객 발화 원문</b>이 있다. 즉 옛 그물에서는 <b>고객 발화를
 * 이 응답에 얹어도 초록</b>이었다.
 *
 * <p><b>B-2 는 타입 그래프로 재는 방식의 구조적 한계다.</b> {@code Map<String, Object>} 안에
 * 무엇을 담아도 도달 타입에는 안 보인다. 그리고 그런 필드가 이 레포에 <b>이미 있다</b>
 * (위 {@code contradictions}) — 가정이 아니다. 그래서 {@code Map}·{@code Object} 는
 * 허용 목록에 <b>절대 넣지 않고</b>, 넣었는지를 {@link #theAllowListItselfForbidsOpaqueTypes()}
 * 가 따로 본다.
 *
 * <p>뒤집은 결과 <b>새 타입이 붙는 순간 무조건 빨강</b>이고, 사람이 그때 판단한다. 지금
 * {@code OpsStatus} 는 {@code String}·{@code long}·{@code Integer}·{@code List}·enum 만
 * 쓰므로 허용 목록이 좁게 잡힌다.
 *
 * <p>❗<b>문자열로 우회하는 것은 여전히 못 막는다.</b> {@code new Fact("세션", "3건")} 은 이
 * 테스트를 통과한다 — 이 그물이 닫는 것은 <b>실수로 얹는 경로</b>이고, 일부러 넣는 것은
 * 정책 주석과 리뷰가 막는다. 이 한계를 여기 적어 두는 이유는, 초록을 보고 <i>"검사가
 * 다 하고 있다"</i> 로 읽으면 정작 리뷰가 느슨해지기 때문이다.
 *
 * <p>{@code CorePackageBoundaryTest}·{@code ErrorCodeContractTest} 와 같은 계열의 단정이다 —
 * 문서와 리뷰에만 있는 규약을 코드가 대조하게 만든다.
 */
@DisplayName("/ops/status 응답에 고객 데이터가 없다")
class OpsStatusHasNoCustomerDataTest {

    /** 응답 타입이 살 수 있는 유일한 패키지. 여기 것은 전부 허용한다. */
    private static final String OPS_PACKAGE = "com.sphinxfin.sphinx.core.ops";

    /**
     * {@code core.ops} 밖에서 <b>허용되는 전부.</b> 값을 나르기만 하고 아무것도 감추지 않는
     * 타입들이다.
     *
     * <p>❗<b>{@code Map}·{@code Object}·{@code Optional} 을 넣지 않는다.</b> 그 셋은 타입으로
     * 아무것도 약속하지 않으므로, 이 응답에서는 <b>있는 것 자체가 그물을 끄는 것</b>이다
     * ({@code Map<String, Object>} 안에 고객 발화를 담아도 도달 타입에는 안 보인다).
     * 누가 넣었는지를 {@link #theAllowListItselfForbidsOpaqueTypes()} 가 본다.
     *
     * <p>여기 무언가를 <b>더하기 전에</b> 물을 것: <i>"이 타입 안에 무엇이 들었는지 이 테스트가
     * 볼 수 있는가."</i> 아니라면 더하지 않는다.
     */
    private static final Set<String> ALLOWED = Set.of(
            "java.lang.String",
            "java.lang.Integer", "java.lang.Long", "java.lang.Boolean",
            "int", "long", "boolean",
            // 원소 타입은 제네릭 인자로 풀어서 따로 검사한다 — 컨테이너 자체는 투명하다.
            "java.util.List");

    /**
     * 허용 목록에 절대 들어가서는 안 되는 것. {@link #ALLOWED} 를 늘리는 사람이 그 문단을
     * 안 읽었을 때 걸리는 자리다 — 리뷰 지적(PR #531)의 B-2 가 정확히 이 경로였다.
     */
    private static final Set<String> FORBIDDEN_ONE_OFF = Set.of(
            "java.util.Map", "java.lang.Object", "java.util.Optional");

    @Test
    @DisplayName("❗응답 타입이 core.ops 와 기본형 밖으로 나가지 않는다 — ADMIN 그랜트의 근거다")
    void theResponseReachesNothingButOpsTypesAndPrimitives() {
        Set<Class<?>> reachable = reachableFrom(OpsStatus.class);

        Set<String> offending = new TreeSet<>();
        for (Class<?> type : reachable) {
            String name = type.getName();
            if (!name.startsWith(OPS_PACKAGE + ".") && !ALLOWED.contains(name)) {
                offending.add(name);
            }
        }

        assertThat(offending)
                .as("""
                    /ops/status 응답이 core.ops 밖의 타입에 닿는다. `ops:status:read` 를 ADMIN 에게
                    준 근거가 «응답에 고객 데이터가 없다» 하나이므로, 이건 정책을 옮겨서 고칠 것이
                    아니라 **응답에서 값을 빼서** 고친다 (ADR-001 · 기획 7-4).

                    정말 필요한 값이면 core.ops 안의 record 로 옮겨 담는다 — 그러면 무엇이
                    실리는지가 이 테스트에 보인다. Map·Object 로 감싸는 것은 답이 아니다:
                    그건 값을 안전하게 만드는 게 아니라 검사를 끄는 것이다.""")
                .isEmpty();
    }

    /**
     * ★ <b>허용 목록 자체를 검사한다.</b>
     *
     * <p>위 단정은 {@link #ALLOWED} 를 신뢰한다. 그래서 거기 {@code java.util.Map} 한 줄이
     * 들어오면 <b>단정은 그대로 초록인데 그물은 꺼진다</b> — PR #531 리뷰의 변이 B-2 가
     * 그 경로였다. 목록을 늘리는 것은 정상 작업이므로(새 기본형이 필요할 수 있다) 막는 대신
     * <b>무엇을 못 넣는지</b>를 여기서 못박는다.
     */
    @Test
    @DisplayName("❗허용 목록에 Map·Object 가 없다 — 그건 값을 안전하게 만드는 게 아니라 검사를 끄는 것이다")
    void theAllowListItselfForbidsOpaqueTypes() {
        Set<String> opaque = new TreeSet<>(ALLOWED);
        opaque.retainAll(FORBIDDEN_ONE_OFF);

        assertThat(opaque)
                .as("""
                    허용 목록에 속을 볼 수 없는 타입이 들어왔다. 그러면 위 단정이 초록인 채로
                    아무것도 안 잰다 — 그 안에 고객 발화를 담아도 도달 타입에는 안 보인다
                    (`SuitabilityMismatch.contradictions` 가 실제로 그런 모양이다).""")
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
