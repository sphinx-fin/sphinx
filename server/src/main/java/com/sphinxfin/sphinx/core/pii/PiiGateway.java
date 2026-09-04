package com.sphinxfin.sphinx.core.pii;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * F-CMN-001 PII 마스킹 게이트웨이. 소유: 강희진
 *
 * ai-service(LLM) 호출 전 모든 고객 텍스트는 반드시 mask()를 거친다 (P3). ai-service를 직접
 * 호출하는 코드는 이 클래스를 경유하는 AiServiceClient 외에 금지. ai-service도 입구에서
 * 방어적으로 PII를 재검사한다(이중 방어선).
 *
 * 마스킹은 결정론적이다. 패턴은 삽입 순서대로(LinkedHashMap) 적용하며, 더 구체적인 패턴을
 * 먼저 둬(EMAIL·RRN·CARD·PHONE → ACCOUNT) 일반 패턴이 앞엣것을 삼키지 않게 한다.
 *
 * 한계: 성명·주소는 정규식으로 안정적으로 못 잡는다(오탐 위험). 사전 기반 마스킹이 필요하며
 * (기획서 "정규식+사전"), 사전 리소스 확보 전까지는 세션 입력 스키마에 성명·주소 필드를 두지
 * 않는 것(P3)이 1차 방어다.
 */
public final class PiiGateway {

    /**
     * 숫자 사이 구분자로 실제로 들어오는 문자들. ASCII 하이픈만 보면 안 된다.
     *
     * 모바일 자판·워드프로세서 자동 교정·문서 복붙이 하이픈을 유니코드 대시로 바꾼다.
     * 그걸 놓치면 "화면은 주민번호라고 경고했는데 서버는 마스킹하지 않은" 상태가 되고,
     * 고객이 경고를 무시하고 제출하는 순간 원문이 그대로 ai-service 로 나간다 (P3 위반).
     *
     * U+2010 hyphen · U+2011 non-breaking hyphen · U+2012 figure dash · U+2013 en dash
     * U+2014 em dash · U+2015 horizontal bar · U+2212 minus sign · U+FF0D fullwidth hyphen
     */
    private static final String DASH = "\\-\\u2010-\\u2015\\u2212\\uFF0D";

    /** 구분자 — 대시류 또는 공백. 없어도 매칭(붙여 쓴 경우). */
    private static final String SEP = "[" + DASH + "\\s]?";

    /** 계좌번호처럼 구분자가 반드시 있어야 하는 자리. */
    private static final String SEP_REQ = "[" + DASH + "]";

    private static final Map<String, Pattern> PATTERNS = new LinkedHashMap<>();

    static {
        // 이메일 — '@'로 구별돼 오탐 적음
        PATTERNS.put("EMAIL", Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"));
        // 주민등록번호 — 뒷자리 첫 숫자 1~4
        PATTERNS.put("RRN", Pattern.compile("\\d{6}" + SEP + "[1-4]\\d{6}"));
        // 카드번호 — 16자리(4-4-4-4)
        PATTERNS.put("CARD", Pattern.compile("\\d{4}" + SEP + "\\d{4}" + SEP + "\\d{4}" + SEP + "\\d{4}"));
        // 휴대전화
        PATTERNS.put("PHONE", Pattern.compile("01[016789]" + SEP + "\\d{3,4}" + SEP + "\\d{4}"));
        // 계좌번호 — 구분자 3구획(은행별 자릿수 상이). 카드·전화가 먼저 마스킹된 뒤 남은 것만.
        PATTERNS.put("ACCOUNT", Pattern.compile(
                "\\d{2,6}" + SEP_REQ + "\\d{2,6}" + SEP_REQ + "\\d{2,6}(?:" + SEP_REQ + "\\d{1,6})?"));
        // TODO(강희진): 성명·주소는 사전/NER 기반(정규식 오탐 큼) — 사전 리소스 확보 후 추가
    }

    /**
     * 마스킹 결과와 <b>무엇이 몇 번 지워졌는지</b>. (이슈 #326)
     *
     * @param text 마스킹된 문면
     * @param hits 패턴 종류 → 건수. 안 걸린 종류는 <b>키가 없다</b>
     *
     * <p>❗<b>원문 조각은 여기 없다.</b> 무엇이 걸렸는지를 남기면 그게 곧 PII 저장이고,
     * <b>지우려고 만든 경로가 새는 자리</b>가 된다. 종류와 개수까지만이다.
     */
    public record Masked(String text, Map<String, Integer> hits) {

        public Masked {
            hits = Map.copyOf(hits);
        }

        /** 이번 호출에서 지워진 총 건수. */
        public int total() {
            return hits.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    /**
     * P3 경계 — 고객 텍스트에서 PII 를 지운다.
     *
     * <p>{@link #maskWithHits(String)} 와 같은 일을 하고 <b>문면만</b> 돌려준다. 건수를
     *안 쓰는 호출부가 계량기를 몰라도 되게 남긴다 — 마스킹 호출 지점이 늘어나는 것보다
     * 낫다(이 클래스 javadoc 참고).
     */
    public static String mask(String text) {
        return maskWithHits(text).text();
    }

    /**
     * 마스킹하고 <b>종류별 건수</b>를 같이 낸다 (이슈 #326).
     *
     * <h2>왜 건수가 필요한가</h2>
     *
     * <p>P3 가 <i>"고객 텍스트가 ai-service 로 나가는 유일한 경로"</i> 로 이 함수를
     * 지목하는데, <b>그 경로를 몇 건이 지나갔고 무엇이 몇 번 지워졌는지는 어디에도 안
     * 남았다.</b> 마스킹이 동작했다는 증거가 코드를 읽는 것 말고 없었다.
     *
     * <p>❗<b>순서가 결과를 바꾼다.</b> 뒤 패턴은 앞이 이미 지운 문면을 본다 —
     * {@code CARD}·{@code PHONE} 이 먼저 걸러진 뒤에야 {@code ACCOUNT} 가 남은 것을 본다.
     * 그래서 건수도 <b>선언 순서대로</b> 센다. 각 패턴을 원문에 따로 돌려 세면 같은 숫자가
     * 두 종류로 중복 계상된다.
     */
    public static Masked maskWithHits(String text) {
        if (text == null) {
            return new Masked(null, Map.of());
        }
        String out = text;
        Map<String, Integer> hits = new LinkedHashMap<>();
        for (var e : PATTERNS.entrySet()) {
            java.util.regex.Matcher m = e.getValue().matcher(out);
            StringBuilder sb = new StringBuilder();
            int count = 0;
            while (m.find()) {
                m.appendReplacement(sb, "[" + e.getKey() + "]");
                count++;
            }
            m.appendTail(sb);
            out = sb.toString();
            if (count > 0) {
                hits.put(e.getKey(), count);
            }
        }
        return new Masked(out, hits);
    }

    private PiiGateway() {}
}
