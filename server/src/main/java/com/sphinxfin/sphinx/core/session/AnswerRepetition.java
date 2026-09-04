package com.sphinxfin.sphinx.core.session;

import java.text.Normalizer;
import java.util.HashSet;
import java.util.Set;

/**
 * 두 발화가 <b>사실상 같은 말</b>인가. 소유: 강희진 (이슈 #268 (d))
 *
 * <h2>왜 필요한가 — 재검증이 되돌릴 수 없는 자리</h2>
 *
 * <p>재설명(F-INT-004) 뒤 다시 물었을 때 고객이 <b>직전과 같은 말을 되풀이</b>했는데
 * 등급만 올라가는 경우가 있다. 그러면 게이트에는 U1 만 남아 {@code R-06} 이 <b>GREEN</b>
 * 을 낸다 — 재설명이 이해를 올린 것이 아니라 <b>채점이 흔들린 것</b>인데 통과한다.
 *
 * <p>이 방향이 특히 비싼 이유는 <b>되돌아오지 않기 때문</b>이다. 판정이 서면 세션은
 * {@code JUDGED} 로 가고 그 뒤로 나가는 전이가 {@code CLOSE} 뿐이다. 미탐이 오탐보다
 * 비싸다는 P5 가 정확히 이 자리를 말한다.
 *
 * <h2>왜 결정론적으로 재나</h2>
 *
 * <p>같은 세션을 다시 판정하면 같은 답이 나와야 한다(P2). 여기에 LLM 을 부르면 회차마다
 * 다른 신호가 나오고, 게이트 입력이 흔들리면 <b>감사 기준점이 재현되지 않는다.</b>
 * 그리고 이 판단은 모델이 필요한 종류가 아니다 — <i>"같은 말인가"</i> 는 글자로 보인다.
 *
 * <p>글자 바이그램 자카드를 쓴다. 형태소 분석기를 안 쓰는 이유는 {@code ai-service} 의
 * 유사도 매칭과 같다 — 사전에 없는 말이 나오면 결과가 갈리고, 그러면 결정론이 깨진다.
 *
 * <h2>❗임계값은 라벨된 실발화로 쟀다</h2>
 *
 * <p>{@link #THRESHOLD} 는 <i>"비슷한가"</i> 가 아니라 <b>"사실상 같은가"</b> 를 재는
 * 값이다. 재설명을 듣고 <b>표현을 다듬은 것</b>은 정상적인 이해 향상이고, 그것까지
 * 의심하면 재설명 기능 자체가 무의미해진다.
 *
 * <p>처음엔 <b>손으로 지어낸 문장쌍 다섯</b>으로 {@code 0.9} 를 골랐다. 그 표본으로도
 * 어미 변경(0.737)이 안 걸리는 것은 드러났지만, <i>"이 값이 실제 발화에서 어떻게 도나"</i>
 * 에는 답하지 못했다. 코퍼스 70건(합의 라벨 51건)으로 다시 쟀다 —
 * {@code eval/tools/measure_repetition.py} 로 재현한다.
 *
 * <pre>
 * 오탐 방향   같은 항목의 **서로 다른** 발화 212쌍
 *              최대 0.444 · 중앙 0.029 · 0.5 이상 0쌍
 *
 * 미탐 방향   실발화에 되풀이 변형을 가한 쌍
 *              마침표만    70/70      띄어쓰기만  70/70
 *              맞장구 추가  69/70      어미 변경   64/70
 *              ❗놓친 것 중 U1 은 0건 — 전부 11자 이하의 U3 다
 * </pre>
 *
 * <p><b>놓치는 구간에 통과 판정이 없다.</b> 합의 라벨에서 {@code U1} 발화의 최소 길이가
 * <b>50자</b>(중앙 77자)다 — 이해로 인정되려면 요소를 전부 자기 말로 설명해야 하므로
 * 구조적으로 짧을 수 없다. 못 잡는 것은 <i>"네."</i>·<i>"숫자가 좀 어렵네요"</i> 류이고
 * 그건 {@code R-04}(U2·U3 → YELLOW)가 이미 받는다.
 *
 * <h2>❗0.444 는 하한이다 — 그래서 여유를 남긴다</h2>
 *
 * <p>측정한 오탐 모집단은 <b>서로 다른 사람의 답</b>이다. 진짜 오탐 모집단은
 * <i>"같은 사람이 재설명을 듣고 다시 설명한 답"</i> 인데 <b>코퍼스에 재검증 쌍이 없다.</b>
 * 같은 사람은 자기 어휘를 다시 쓰므로 겹침이 남보다 높다 — 즉 실제 분포는 0.444 보다
 * 위에 있다.
 *
 * <p>그래서 데이터에 딱 붙는 {@code 0.5}(여유 0.056)가 아니라 {@code 0.6}(여유 0.156)을
 * 쓴다. 미탐 쪽으로 잃는 것이 <b>전부 U1 이 아닌 짧은 답</b>이라 이 방향의 대가가 싸다.
 * 재검증 쌍이 쌓이면({@code #327}) 그 분포로 다시 본다.
 */
public final class AnswerRepetition {

    /**
     * 이 이상 겹치면 <b>같은 말</b>로 본다. 위 표에서 잰 값이다
     * ({@code eval/tools/measure_repetition.py} 가 재현한다).
     *
     * <p>{@code ai-service} 의 오해 매칭({@code NGRAM_THRESHOLD = 0.62})과 우연히 가깝지만
     * <b>같은 값으로 묶지 않는다.</b> 그쪽은 <i>"이 발화가 이 오해에 해당하는가"</i> 를 재고
     * 여기는 <i>"같은 답을 다시 냈는가"</i> 를 잰다 — 재는 대상이 다르므로 한쪽을 튜닝할 때
     * 다른 쪽이 따라 움직이면 안 된다.
     */
    static final double THRESHOLD = 0.6;

    private AnswerRepetition() {}

    /** 두 발화가 사실상 같은 말인가. 어느 한쪽이 없으면 {@code false}. */
    static boolean essentiallySame(String a, String b) {
        return similarity(a, b) >= THRESHOLD;
    }

    /**
     * 두 발화의 겹침 {@code 0.0 ~ 1.0}. 어느 한쪽이 없거나 비면 {@code 0.0}.
     *
     * <p>❗<b>이 계산을 두 벌 만들지 않는다.</b> 코칭 정황 집계(기획 7-4 2단계)가
     * <i>"답변의 문장 유사도가 지나치게 균질한가"</i> 를 재는 데 같은 값을 쓴다. 각자
     * 정규화하면 <b>미묘하게 다른 두 정규화</b>가 생기고, 그러면 되풀이 판정과 균질도가
     * 서로를 설명하지 못한다({@code CanonicalJson} 을 한 벌만 두는 것과 같은 이유).
     *
     * <p>돌려주는 것이 불리언이 아니라 <b>값</b>인 것도 그래서다 — 집계는 임계값이 아니라
     * <b>분포</b>를 봐야 한다.
     */
    public static double similarity(String a, String b) {
        if (a == null || b == null) {
            return 0.0;
        }
        String na = normalize(a);
        String nb = normalize(b);
        if (na.isEmpty() || nb.isEmpty()) {
            return 0.0;
        }
        if (na.equals(nb)) {
            return 1.0;   // 짧은 답("네")은 바이그램이 안 나온다 — 같음은 여기서 답한다
        }
        Set<String> ba = bigrams(na);
        Set<String> bb = bigrams(nb);
        if (ba.isEmpty() || bb.isEmpty()) {
            return 0.0;
        }
        Set<String> intersection = new HashSet<>(ba);
        intersection.retainAll(bb);
        Set<String> union = new HashSet<>(ba);
        union.addAll(bb);
        return (double) intersection.size() / union.size();
    }

    /**
     * 공백·구두점을 지우고 유니코드를 정규화한다.
     *
     * <p>❗<b>여기서 지우는 것이 곧 "같다고 볼 차이"</b>다. 띄어쓰기와 문장부호만 바꾼 답은
     * 같은 답이다 — 그것을 다른 답으로 세면 이 신호가 아무것도 안 잡는다.
     */
    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replaceAll("[\\s\\p{Punct}·…]+", "");
    }

    private static Set<String> bigrams(String text) {
        Set<String> out = new HashSet<>();
        for (int i = 0; i + 2 <= text.length(); i++) {
            out.add(text.substring(i, i + 2));
        }
        return out;
    }
}
