package com.sphinxfin.sphinx.evidence;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 정규화 직렬화. 소유: 정세현
 * ReportService(F-GTE-004)와 AuditLog(F-CMN-002)의 공통 기반 — 두 곳의 해시가 교차 검증
 * 가능해야 하므로 정규화는 이 클래스 하나만 쓴다. 여기서 갈라지면 감사 시점까지 안 보인다.
 *
 * 논리적으로 동일한 내용 → 항상 동일한 바이트. 규약은 <b>ADR-008</b>에 있다
 * (docs/adr/008-canonical-json.md). 요지만 옮기면:
 *
 * <ul>
 *   <li><b>RFC 8785(JCS) 그대로</b>가 기본이다 — 키 정렬은 UTF-16 코드유닛 순서
 *       ({@code String.compareTo()}가 그 순서다), 최소 이스케이프, 숫자는 최단 왕복 표기.
 *       외부 감사자가 우리 코드 없이 재계산할 수 있어야 contentHash가 증거가 된다.</li>
 *   <li><b>해시 대상에 double/float를 담지 않는다.</b> 금액은 원 단위 long, 비율은 BigDecimal.
 *       부동소수는 계산 경로가 조금 달라지면 마지막 자리가 흔들리고, 그 순간 같은 판정이 다른
 *       해시를 낸다 — 체인이면 그 뒤 전체가 검증 실패다. NaN/Infinity는 직렬화 거부.</li>
 *   <li><b>NFC 정규화를 여기서 하지 않는다.</b> 직렬화가 내용을 바꾸면 저장된 utteranceQuote와
 *       해시 대상이 갈리고, verify_quote_is_verbatim이 대조하는 문자열이 어느 쪽인지 모호해진다
 *       (화면이 PII를 미리 마스킹하면 안 되는 것과 같은 구조). 정규화는 입력 경계에서 한다.</li>
 *   <li><b>타임스탬프는 UTC + 밀리초 3자리 고정</b>({@code 2026-08-25T09:34:16.000Z}).
 *       Instant.now()의 정밀도가 플랫폼마다 달라서, 자릿수만 다른 같은 시각이 다른 해시를
 *       내는 것을 막는다. 적재 순서는 타임스탬프가 아니라 prev_hash가 정하므로 밀리초로 충분하다.</li>
 *   <li><b>null 필드를 생략하지 않는다.</b> 생략하면 "값이 null"과 "필드가 없음"이 같은 바이트가
 *       된다. 히트맵의 {@code misrate: null}은 소표본 마스킹이 동작했다는 증거인데, 생략하면
 *       그 증거가 해시에서 사라진다.</li>
 *   <li><b>컬렉션 순서는 itemId 정렬을 강제한다</b>(ADR-004). Session.judgmentsByItem이 HashMap이라
 *       Session.judgments()의 순서는 명세되지 않는다 — 게이트 판정에는 영향이 없지만
 *       (GateEngine은 grade 집합 멤버십만 본다) 리포트·감사 로그 해시는 호출측 순서에 기대면
 *       안 된다. 안 그러면 같은 세션이 다른 해시를 낼 수 있고, 위 첫 문단이 경고하는
 *       "감사 시점까지 안 보이는" 결함이 된다.</li>
 * </ul>
 *
 * 규약을 여기와 ADR 두 곳에 적으면 갈린다. 바뀌면 새 ADR을 추가하고 이 주석은 참조만 고친다.
 *
 * <h2>구현 노트</h2>
 *
 * <p><b>모르는 타입은 직렬화하지 않고 던진다.</b> 임의 객체를 {@code toString()}으로 흘리면
 * 해시에 들어가는 내용이 그 클래스의 구현 세부에 묶인다 — 필드를 하나 고치면 지난 기록의
 * 재계산이 실패하는데, 그 원인이 직렬화기에 있다는 것이 안 보인다. 담을 수 있는 타입을
 * 좁게 유지하고, 새 타입이 필요하면 여기에 명시적으로 추가한다.
 *
 * <p><b>BigDecimal은 {@code stripTrailingZeros().toPlainString()}으로 적는다</b> — 지수 표기가
 * 섞이지 않고 {@code 1.0}과 {@code 1}이 같은 바이트가 된다. 다만 RFC 8785의 숫자 규칙은 IEEE-754
 * double로 표현 가능한 값을 전제하므로, <b>해시 대상의 소수는 double 정밀도(유효숫자 15자리)
 * 안에 두어야</b> 외부 감사자의 JCS 구현과 결과가 같다. 우리 payload는 금액이 원 단위 정수이고
 * 비율이 소수 두세 자리라 이 범위 안이다.
 */
public final class CanonicalJson {

    /** ADR-008 — UTC · 밀리초 3자리 고정. 자릿수만 다른 같은 시각이 다른 해시를 내지 않게 한다. */
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    /** 객체 배열을 정렬하는 기준 필드(ADR-004). 원소 전부가 이 키를 가질 때만 정렬한다. */
    private static final String ORDER_KEY = "itemId";

    /** 정규화된 JSON 문자열. 같은 입력은 언제나 같은 출력이어야 한다. */
    public static String serialize(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    /** 해시 입력용 바이트. serialize()의 UTF-8 인코딩 — 인코딩도 고정 지점이다. */
    public static byte[] bytes(Object value) {
        return serialize(value).getBytes(StandardCharsets.UTF_8);
    }

    // ── 값 쓰기 ──────────────────────────────────────────────────────────────

    private static void write(Object value, StringBuilder out) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String s) {
            writeString(s, out);
        } else if (value instanceof Boolean b) {
            out.append(b.booleanValue());
        } else if (value instanceof Double || value instanceof Float) {
            throw new IllegalArgumentException(
                    "해시 대상에 double/float를 담지 않는다 (ADR-008): " + value + " — "
                            + "금액은 원 단위 long, 비율은 BigDecimal로 담는다");
        } else if (value instanceof BigDecimal d) {
            out.append(d.stripTrailingZeros().toPlainString());
        } else if (value instanceof BigInteger || value instanceof Long || value instanceof Integer
                || value instanceof Short || value instanceof Byte) {
            out.append(value);
        } else if (value instanceof Number n) {
            throw new IllegalArgumentException(
                    "직렬화 규칙이 정의되지 않은 수 타입 (ADR-008): " + n.getClass().getName());
        } else if (value instanceof Instant i) {
            writeString(TIMESTAMP.format(i.truncatedTo(ChronoUnit.MILLIS)), out);
        } else if (value instanceof LocalDate d) {
            writeString(d.toString(), out);
        } else if (value instanceof Enum<?> e) {
            writeString(e.name(), out);
        } else if (value instanceof Map<?, ?> m) {
            writeObject(m, out);
        } else if (value instanceof Collection<?> c) {
            writeArray(new ArrayList<>(c), out);
        } else if (value instanceof Object[] a) {
            writeArray(List.of(a), out);
        } else if (value.getClass().isRecord()) {
            writeObject(recordToMap(value), out);
        } else {
            throw new IllegalArgumentException(
                    "해시 대상에 담을 수 없는 타입: " + value.getClass().getName()
                            + " — 담을 타입은 CanonicalJson에 명시적으로 추가한다 (구현 노트 참조)");
        }
    }

    /**
     * 객체. 키는 UTF-16 코드유닛 순서(RFC 8785)이고 {@code String.compareTo()}가 정확히 그 순서다.
     * null 값은 생략하지 않는다 — "값이 null"과 "필드가 없음"이 같은 바이트가 되면 안 된다.
     */
    private static void writeObject(Map<?, ?> map, StringBuilder out) {
        Map<String, Object> sorted = new TreeMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!(e.getKey() instanceof String key)) {
                throw new IllegalArgumentException(
                        "JSON 객체의 키는 문자열이어야 한다: " + e.getKey());
            }
            if (sorted.put(key, e.getValue()) != null) {
                throw new IllegalArgumentException("키가 중복됐다: " + key);
            }
        }
        out.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> e : sorted.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            writeString(e.getKey(), out);
            out.append(':');
            write(e.getValue(), out);
        }
        out.append('}');
    }

    /**
     * 배열. JSON 배열은 순서가 곧 내용이므로 <b>기본은 보존</b>이다 —
     * {@code gateRuleTrace}의 {@code [R-04, R-05]}는 룰 파일 순서가 의미다.
     *
     * <p>예외가 하나 있다(ADR-004): 원소가 전부 {@code itemId}를 가진 객체이면 그 값으로
     * 오름차순 정렬한다. {@code Session.judgmentsByItem}이 HashMap이라 호출측이 넘기는 순서가
     * 명세되지 않기 때문이다. <b>정렬은 안정적</b>이라 같은 항목의 재검증 이력
     * ({@code A:U3 → A:U1}) 상대 순서는 그대로 남는다.
     */
    private static void writeArray(List<?> list, StringBuilder out) {
        List<?> ordered = orderByItemId(list);
        out.append('[');
        for (int i = 0; i < ordered.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            write(ordered.get(i), out);
        }
        out.append(']');
    }

    private static List<?> orderByItemId(List<?> list) {
        List<String> keys = new ArrayList<>(list.size());
        for (Object element : list) {
            String key = itemIdOf(element);
            if (key == null) {
                return list;                       // 하나라도 없으면 정렬하지 않는다
            }
            keys.add(key);
        }
        if (keys.isEmpty()) {
            return list;
        }
        List<Integer> index = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            index.add(i);
        }
        index.sort((a, b) -> keys.get(a).compareTo(keys.get(b)));   // 동률이면 원래 순서(안정)
        List<Object> sorted = new ArrayList<>(list.size());
        for (int i : index) {
            sorted.add(list.get(i));
        }
        return sorted;
    }

    /** 원소의 itemId. 레코드든 맵이든 문자열 값일 때만 인정한다. */
    private static String itemIdOf(Object element) {
        if (element instanceof Map<?, ?> m) {
            return m.get(ORDER_KEY) instanceof String s ? s : null;
        }
        if (element != null && element.getClass().isRecord()) {
            Object value = recordToMap(element).get(ORDER_KEY);
            return value instanceof String s ? s : null;
        }
        return null;
    }

    /** 레코드 → 컴포넌트 맵. 키 정렬은 writeObject가 하므로 선언 순서는 무관하다. */
    private static Map<String, Object> recordToMap(Object record) {
        Map<String, Object> map = new TreeMap<>();
        for (RecordComponent component : record.getClass().getRecordComponents()) {
            Object value;
            try {
                value = component.getAccessor().invoke(record);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(
                        "레코드 컴포넌트를 읽지 못했다: " + record.getClass().getName()
                                + "." + component.getName(), e);
            }
            map.put(component.getName(), value);
        }
        return map;
    }

    /**
     * 문자열. RFC 8785의 이스케이프는 최소 집합이다 — 필수 두 개(따옴표·역슬래시), 짧은 형식이
     * 있는 제어문자 다섯 개, 나머지 U+0020 미만은 {@code \\u00xx}(소문자 hex). 그 밖의 문자는
     * 비ASCII를 포함해 그대로 둔다. 출력 인코딩이 UTF-8이므로 한글도 이스케이프하지 않는다.
     */
    private static void writeString(String s, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private CanonicalJson() {}
}
