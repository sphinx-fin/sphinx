package com.sphinxfin.sphinx.evidence;

import com.sphinxfin.sphinx.domain.Grade;
import com.sphinxfin.sphinx.domain.Judgment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-008 규약을 조항 단위로 고정한다. 소유: 정세현
 *
 * <p>이 테스트가 지키는 것은 "같은 내용이면 같은 바이트"다. 깨지면 리포트 해시와 감사 로그
 * 해시를 교차 검증할 수 없게 되는데, 그 결함은 감사 시점까지 드러나지 않는다(ADR-003).
 * 그래서 결정마다 하나씩 케이스를 둔다 — 어느 결정이 깨졌는지가 실패 이름으로 보여야 한다.
 */
@DisplayName("CanonicalJson — ADR-008 정규화 규약")
class CanonicalJsonTest {

    @Nested
    @DisplayName("키 정렬 — RFC 8785 (UTF-16 코드유닛 순서)")
    class KeyOrdering {

        @Test
        @DisplayName("입력 순서와 무관하게 키 오름차순")
        void sortsKeys() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("b", 2);
            m.put("a", 1);
            m.put("C", 3);
            assertThat(CanonicalJson.serialize(m)).isEqualTo("{\"C\":3,\"a\":1,\"b\":2}");
        }

        @Test
        @DisplayName("HashMap 이라 순회 순서가 달라져도 같은 바이트")
        void hashMapIterationOrderDoesNotLeak() {
            Map<String, Object> one = new HashMap<>();
            Map<String, Object> two = new HashMap<>();
            for (String k : List.of("zeta", "alpha", "mu", "beta", "omega")) {
                one.put(k, k.length());
            }
            for (String k : List.of("omega", "beta", "mu", "alpha", "zeta")) {
                two.put(k, k.length());
            }
            assertThat(CanonicalJson.serialize(one)).isEqualTo(CanonicalJson.serialize(two));
        }

        @Test
        @DisplayName("코드포인트가 아니라 UTF-16 코드유닛 순서 — 보조평면 문자로 갈린다")
        void sortsByUtf16CodeUnitsNotCodePoints() {
            // U+1F600 은 UTF-16 으로 D83D DE00, U+FB33 은 FB33.
            // 코드유닛 순서면 D83D < FB33 이라 U+1F600 이 앞, 코드포인트 순서면 반대다.
            String supplementary = new String(Character.toChars(0x1F600));
            String bmp = "דּ";
            Map<String, Object> m = new LinkedHashMap<>();
            m.put(bmp, "bmp");
            m.put(supplementary, "supplementary");
            assertThat(CanonicalJson.serialize(m))
                    .as("RFC 8785 는 UTF-16 코드유닛 순서다 — String.compareTo() 가 그 순서다")
                    .isEqualTo("{\"" + supplementary + "\":\"supplementary\",\""
                            + bmp + "\":\"bmp\"}");
        }
    }

    @Nested
    @DisplayName("숫자 — 부동소수는 해시 대상에 담지 않는다")
    class Numbers {

        @Test
        @DisplayName("double 은 거부한다 (금액은 long, 비율은 BigDecimal)")
        void rejectsDouble() {
            assertThatThrownBy(() -> CanonicalJson.serialize(Map.of("rate", 0.91d)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ADR-008");
        }

        @Test
        @DisplayName("float 도 거부한다")
        void rejectsFloat() {
            assertThatThrownBy(() -> CanonicalJson.serialize(Map.of("rate", 0.91f)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("NaN·Infinity 는 double 이므로 같은 경로에서 막힌다")
        void rejectsNonFinite() {
            for (double d : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("v", d);
                assertThatThrownBy(() -> CanonicalJson.serialize(m))
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }

        @Test
        @DisplayName("금액은 원 단위 정수 그대로")
        void writesIntegersPlainly() {
            assertThat(CanonicalJson.serialize(Map.of("payout", 51_500_000L)))
                    .isEqualTo("{\"payout\":51500000}");
        }

        @Test
        @DisplayName("BigDecimal — 1.0 과 1 은 같은 바이트, 지수 표기가 섞이지 않는다")
        void normalizesBigDecimal() {
            assertThat(CanonicalJson.serialize(new BigDecimal("1.0")))
                    .isEqualTo(CanonicalJson.serialize(new BigDecimal("1")));
            assertThat(CanonicalJson.serialize(new BigDecimal("0.910")))
                    .isEqualTo("0.91");
            assertThat(CanonicalJson.serialize(new BigDecimal("1E+3")))
                    .as("지수 표기는 남기지 않는다")
                    .isEqualTo("1000");
        }
    }

    @Nested
    @DisplayName("타임스탬프 — UTC · 밀리초 3자리 고정")
    class Timestamps {

        @Test
        @DisplayName("밀리초 세 자리로 고정 — 0 이어도 적는다")
        void alwaysThreeFractionalDigits() {
            assertThat(CanonicalJson.serialize(Instant.parse("2026-08-25T09:34:16Z")))
                    .isEqualTo("\"2026-08-25T09:34:16.000Z\"");
        }

        @Test
        @DisplayName("나노초 정밀도는 밀리초로 잘린다 — 플랫폼 차이가 해시로 새지 않는다")
        void truncatesToMillis() {
            Instant nanos = Instant.parse("2026-08-25T09:34:16.123456789Z");
            Instant micros = Instant.parse("2026-08-25T09:34:16.123456Z");
            Instant millis = Instant.parse("2026-08-25T09:34:16.123Z");
            assertThat(CanonicalJson.serialize(nanos))
                    .isEqualTo(CanonicalJson.serialize(micros))
                    .isEqualTo(CanonicalJson.serialize(millis))
                    .isEqualTo("\"2026-08-25T09:34:16.123Z\"");
        }

        @Test
        @DisplayName("같은 시각이면 오프셋 표기가 달라도 같은 바이트")
        void normalizesOffsetToUtc() {
            assertThat(CanonicalJson.serialize(Instant.parse("2026-08-25T18:34:16+09:00")))
                    .isEqualTo(CanonicalJson.serialize(Instant.parse("2026-08-25T09:34:16Z")));
        }
    }

    @Nested
    @DisplayName("null — 생략하지 않는다")
    class Nulls {

        @Test
        @DisplayName("null 값과 필드 부재가 다른 바이트여야 한다")
        void keepsNullFields() {
            Map<String, Object> withNull = new LinkedHashMap<>();
            withNull.put("cell", "A");
            withNull.put("misrate", null);

            Map<String, Object> without = new LinkedHashMap<>();
            without.put("cell", "A");

            assertThat(CanonicalJson.serialize(withNull)).isEqualTo("{\"cell\":\"A\",\"misrate\":null}");
            assertThat(CanonicalJson.serialize(withNull))
                    .as("misrate: null 은 소표본 마스킹이 동작했다는 증거다 — 생략하면 해시에서 사라진다")
                    .isNotEqualTo(CanonicalJson.serialize(without));
        }
    }

    @Nested
    @DisplayName("정규화를 직렬화기가 하지 않는다")
    class NoNormalization {

        @Test
        @DisplayName("NFC 와 NFD 는 서로 다른 바이트로 남는다 — 직렬화가 내용을 바꾸면 안 된다")
        void doesNotNormalizeUnicode() {
            String nfc = "가";              // 가 (완성형)
            String nfd = "가";        // ㄱ + ㅏ (조합형) — NFC 로 합치면 U+AC00
            assertThat(nfc).isNotEqualTo(nfd);
            assertThat(CanonicalJson.serialize(nfc))
                    .as("여기서 정규화하면 저장된 utteranceQuote 와 해시 대상이 갈린다 (ADR-008)")
                    .isNotEqualTo(CanonicalJson.serialize(nfd));
        }

        @Test
        @DisplayName("한글은 이스케이프하지 않는다 — 출력이 UTF-8 이다")
        void keepsNonAsciiLiteral() {
            assertThat(CanonicalJson.serialize("원금은 지켜지죠"))
                    .isEqualTo("\"원금은 지켜지죠\"");
        }

        @Test
        @DisplayName("제어문자는 최소 이스케이프 — 짧은 형식이 있으면 그것, 없으면 소문자 유니코드 escape")
        void escapesControlCharacters() {
            String withControl = "a\"b\\c\nd\te" + (char) 1 + "f";
            assertThat(CanonicalJson.serialize(withControl))
                    .isEqualTo("\"a\\\"b\\\\c\\nd\\te\\u0001f\"");
        }
    }

    @Nested
    @DisplayName("배열 — 순서가 내용이다. 단 itemId 객체는 정렬한다 (ADR-004)")
    class Arrays {

        record Item(String itemId, String note) {}

        @Test
        @DisplayName("문자열 배열은 순서를 보존한다 — gateRuleTrace 의 R-04·R-05 는 룰 파일 순서가 의미다")
        void preservesArrayOrder() {
            assertThat(CanonicalJson.serialize(List.of("R-04", "R-05")))
                    .isEqualTo("[\"R-04\",\"R-05\"]");
            assertThat(CanonicalJson.serialize(List.of("R-05", "R-04")))
                    .isNotEqualTo(CanonicalJson.serialize(List.of("R-04", "R-05")));
        }

        @Test
        @DisplayName("원소가 전부 itemId 를 가지면 itemId 오름차순 — HashMap 순회 순서가 해시로 새지 않는다")
        void sortsObjectsByItemId() {
            String a = CanonicalJson.serialize(List.of(new Item("B", "second"), new Item("A", "first")));
            String b = CanonicalJson.serialize(List.of(new Item("A", "first"), new Item("B", "second")));
            assertThat(a).isEqualTo(b);
            assertThat(a).startsWith("[{\"itemId\":\"A\"");
        }

        @Test
        @DisplayName("정렬은 안정적이다 — 같은 항목의 재검증 이력 순서는 그대로 남는다")
        void sortIsStable() {
            List<Item> history = List.of(
                    new Item("A", "U3"), new Item("B", "U1"), new Item("A", "U1"));
            assertThat(CanonicalJson.serialize(history))
                    .as("A:U3 → A:U1 은 이력 순서라 뒤집히면 안 된다 (이슈 #54 의 2번)")
                    .isEqualTo("[{\"itemId\":\"A\",\"note\":\"U3\"},"
                            + "{\"itemId\":\"A\",\"note\":\"U1\"},"
                            + "{\"itemId\":\"B\",\"note\":\"U1\"}]");
        }

        @Test
        @DisplayName("하나라도 itemId 가 없으면 정렬하지 않는다")
        void doesNotSortMixedArrays() {
            List<Object> mixed = new ArrayList<>();
            mixed.add(Map.of("itemId", "B"));
            mixed.add(Map.of("other", "A"));
            assertThat(CanonicalJson.serialize(mixed)).isEqualTo("[{\"itemId\":\"B\"},{\"other\":\"A\"}]");
        }
    }

    @Nested
    @DisplayName("타입 — 담을 수 있는 것만 담는다")
    class Types {

        record Plain(String itemId, long amount) {}

        static class NotARecord {
            @Override
            public String toString() {
                return "이 문자열이 해시에 들어가면 안 된다";
            }
        }

        @Test
        @DisplayName("레코드는 컴포넌트 이름으로 펼친다")
        void serializesRecords() {
            assertThat(CanonicalJson.serialize(new Plain("ELS-001", 50_000_000L)))
                    .isEqualTo("{\"amount\":50000000,\"itemId\":\"ELS-001\"}");
        }

        @Test
        @DisplayName("enum 은 name() 이다 — 표시 문면이 바뀌어도 해시가 안 흔들린다")
        void serializesEnumByName() {
            assertThat(CanonicalJson.serialize(Grade.U4)).isEqualTo("\"U4\"");
        }

        @Test
        @DisplayName("모르는 타입은 toString 으로 흘리지 않고 던진다")
        void rejectsUnknownTypes() {
            assertThatThrownBy(() -> CanonicalJson.serialize(new NotARecord()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("담을 수 없는 타입");
        }


        @Test
        @DisplayName("Judgment 를 그대로 담을 수 있다 — enum · 중첩 레코드 · nullable · BigDecimal")
        void judgmentIsSerializable() {
            Judgment judgment = new Judgment(
                    "ELS-PRINCIPAL-LOSS-WARNING", Grade.U4, new BigDecimal("0.91"),
                    new Judgment.Evidence("원금은 지켜지죠", "원금손실 조건"),
                    "원금 보장으로 오해", null);          // misconceptionType 은 nullable 이다

            assertThat(CanonicalJson.serialize(judgment))
                    .as("적재 쪽이 Map 으로 풀지 않고 레코드를 그대로 넘겨도 된다는 확인이다 "
                            + "— Grade(enum) · Evidence(중첩 레코드) · null 이 전부 지원 타입이다")
                    .isEqualTo("{\"confidence\":0.91,"
                            // escalate 는 boolean 이라 안 실린 응답도 false 로 들어온다(#160).
                            // 이 줄이 늘어난 것은 **정규화가 바뀐 것이 아니라 판정이 바뀐 것**이다 —
                            // append-only 라 이전 항목은 그대로이고, 이후 항목부터 이 키가 있다
                            // (promptVersion 이 들어올 때와 같은 모양, 결정 10.38).
                            + "\"escalate\":false,"
                            + "\"evidence\":{\"rubricClause\":\"원금손실 조건\",\"utteranceQuote\":\"원금은 지켜지죠\"},"
                            + "\"grade\":\"U4\","
                            + "\"itemId\":\"ELS-PRINCIPAL-LOSS-WARNING\","
                            + "\"misconceptionType\":null,"
                            + "\"promptVersion\":null,"
                            + "\"reason\":\"원금 보장으로 오해\"}");
        }

        @Test
        @DisplayName("misconceptionType 이 채워진 판정도 담긴다 — 적재 쪽이 Map 으로 풀 필요가 없다")
        void judgmentWithMisconceptionIsSerializable() {
            // decision-log 10.32 가 닫혔다(confidence: double → BigDecimal). 예전에는 이 자리에
            // "아직 담을 수 없다"를 단정하는 테스트가 있었고, 타입이 바뀌면 깨지도록 두었다.
            // 그 역할이 끝났으므로 담긴다는 쪽으로 뒤집는다.
            Judgment judgment = new Judgment("ELS-PRINCIPAL-LOSS-WARNING", Grade.U4,
                    new BigDecimal("0.91"),
                    new Judgment.Evidence("원금은 지켜지죠", "원금손실 조건"),
                    "원금 보장으로 오해", "M01-PRINCIPAL-GUARANTEE");

            assertThat(CanonicalJson.serialize(judgment))
                    .contains("\"confidence\":0.91")
                    .contains("\"misconceptionType\":\"M01-PRINCIPAL-GUARANTEE\"");
        }
    }

    @Nested
    @DisplayName("결정론")
    class Determinism {

        @Test
        @DisplayName("같은 입력 100 회 — 같은 출력")
        void sameInputGivesSameOutput() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("sessionId", "S-1");
            payload.put("at", Instant.parse("2026-08-25T09:34:16.123Z"));
            payload.put("trace", List.of("R-04", "R-05"));
            payload.put("misrate", null);
            payload.put("amount", 50_000_000L);

            String first = CanonicalJson.serialize(payload);
            for (int i = 0; i < 100; i++) {
                assertThat(CanonicalJson.serialize(payload)).isEqualTo(first);
            }
        }

        @Test
        @DisplayName("bytes() 는 serialize() 의 UTF-8 — 인코딩도 고정 지점이다")
        void bytesIsUtf8OfSerialize() {
            Object value = Map.of("q", "원금은 지켜지죠");
            assertThat(CanonicalJson.bytes(value))
                    .isEqualTo(CanonicalJson.serialize(value).getBytes(StandardCharsets.UTF_8));
        }
    }
}
