package com.sphinxfin.sphinx.core.pii;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * P3 경계가 <b>몇 번 작동했는가</b>. 소유: 강희진 (이슈 #326 1번)
 *
 * <h2>왜 계량기인가</h2>
 *
 * <p>보안·개인정보 주장이 전부 코드 주석과 정책 파일로만 있었다. 심사에서
 * <i>"개인정보는 어떻게 처리하나요"</i> 가 나오면 답이 <b>주석</b>이었다. 이 계량기가
 * 있으면 <b>숫자</b>가 된다 — 경계를 지나간 호출 수와 종류별 삭제 건수.
 *
 * <h2>❗원문은 어디에도 안 남는다</h2>
 *
 * <p>무엇이 걸렸는지를 남기면 그게 곧 PII 저장이고, <b>지우려고 만든 경로가 새는 자리</b>가
 * 된다. 여기 쌓이는 것은 <b>종류 이름과 개수뿐</b>이다 — 문자열 조각도, 세션 ID 도,
 * 행위자도 없다. 그래서 이 값은 개인을 식별하지 않고, 집계로만 존재한다.
 *
 * <p>같은 이유로 <b>세션별로 안 쌓는다.</b> 세션 축이 붙는 순간 <i>"이 고객이 주민번호를
 * 적었다"</i> 가 되고, 그건 우리가 지운 사실 자체를 다시 만드는 것이다.
 *
 * <h2>수명</h2>
 *
 * <p>프로세스와 함께 사라진다({@code evidence/} 와 달리 영속하지 않는다). 감사 기록이
 * 아니라 <b>운영 관측값</b>이기 때문이다 — 영속시키려면 그 자체가 보관 정책 대상이 되고,
 * 그 판단은 {@code #326} 2번(감사 조회 경로)과 같이 해야 한다.
 */
@Component
public class PiiMeter {

    private final AtomicLong calls = new AtomicLong();
    private final Map<String, AtomicLong> removed = new ConcurrentHashMap<>();

    /** 경계를 한 번 지났다. {@code hits} 가 비어도 부른다 — 분모가 있어야 비율이 선다. */
    public void record(PiiGateway.Masked masked) {
        calls.incrementAndGet();
        masked.hits().forEach((kind, count) ->
                removed.computeIfAbsent(kind, k -> new AtomicLong()).addAndGet(count));
    }

    /** 경계를 지나간 호출 수 — 마스킹이 한 건도 안 걸린 호출도 센다. */
    public long calls() {
        return calls.get();
    }

    /** 종류 → 누적 삭제 건수. 안 걸린 종류는 키가 없다. */
    public Map<String, Long> removed() {
        Map<String, Long> out = new TreeMap<>();
        removed.forEach((k, v) -> out.put(k, v.get()));
        return Map.copyOf(out);
    }

    /** {@code 호출 12건 · EMAIL=1 PHONE=2} — 로그·스크립트가 그대로 쓴다. */
    public String summary() {
        StringBuilder sb = new StringBuilder("호출 ").append(calls.get()).append("건");
        removed().forEach((k, v) -> sb.append(" · ").append(k).append('=').append(v));
        return sb.toString();
    }
}
