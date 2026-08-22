package com.sphinxfin.sphinx.core;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * F-CMN-001 PII 마스킹 게이트웨이. 소유: 강희진
 * ai-service(LLM) 호출 전 모든 텍스트는 반드시 mask()를 거친다 (P3).
 * ai-service를 직접 호출하는 코드는 이 클래스를 경유하는 AiServiceClient 외에 금지.
 */
public final class PiiGateway {

    private static final Map<String, Pattern> PATTERNS = Map.of(
            "RRN", Pattern.compile("\\d{6}[-\\s]?[1-4]\\d{6}"),
            "PHONE", Pattern.compile("01[016789][-\\s]?\\d{3,4}[-\\s]?\\d{4}")
            // TODO(강희진): 계좌번호(은행별 자릿수), 성명 사전, 주소 패턴
    );

    public static String mask(String text) {
        String out = text;
        for (var e : PATTERNS.entrySet()) {
            out = e.getValue().matcher(out).replaceAll("[" + e.getKey() + "]");
        }
        return out;
    }

    private PiiGateway() {}
}
