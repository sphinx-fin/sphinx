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
 * <h2>❗임계값은 재고 정했다</h2>
 *
 * <p>{@link #THRESHOLD} 는 <i>"비슷한가"</i> 가 아니라 <b>"사실상 같은가"</b> 를 재는
 * 값이다. 재설명을 듣고 <b>표현을 다듬은 것</b>은 정상적인 이해 향상이고, 그것까지
 * 의심하면 재설명 기능 자체가 무의미해진다.
 *
 * <p>처음에 {@code 0.9} 로 잡았다가 실측에서 문면과 어긋나는 것이 드러났다 — 같은 답의
 * <b>어미만</b> 바꾼 것이 {@code 0.737} 이라 안 걸렸다. 기준 발화
 * <i>"낙인 하회하면 원금 손실 난다고 들었어요"</i> 에 대한 값이다.
 *
 * <pre>
 * 1.000   마침표만 붙였다                    같은 답
 * 1.000   띄어쓰기만 바꿨다                   같은 답
 * 0.737   "들었어요" → "들었습니다"           같은 답  ← 0.9 에서 샜다
 * 0.440   "날 수 있다고 이해했어요"            경계
 * 0.000   기초자산·절반·줄어든다로 다시 설명    다른 답
 * </pre>
 *
 * <p>{@code 0.6} 은 <b>어미 변경(0.74)을 여유 있게 물고 다시 설명한 답(0.44 이하)은 놓는</b>
 * 자리다. 두 무리 사이가 넓어서 그 안 어디를 잡아도 판정이 같다 — 경계에 민감하지 않다는
 * 뜻이고, 임계값으로는 그게 좋은 성질이다.
 *
 * <p>❗<b>0.44 는 안 잡는다.</b> 내용을 조금 더한 되풀이는 이 신호로 안 걸리고, 남는 것은
 * {@code R-05}(신뢰도)와 사람 검토다. 낮춰서 잡을 수도 있지만 그러면 <b>정상적인
 * 다듬기</b>까지 문다 — 이쪽 방향의 비용이 더 크다(재설명이 매번 황색이 되면 아무도 안 쓴다).
 */
final class AnswerRepetition {

    /**
     * 이 이상 겹치면 <b>같은 말</b>로 본다. 위 표에서 잰 값이다.
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
        if (a == null || b == null) {
            return false;
        }
        String na = normalize(a);
        String nb = normalize(b);
        if (na.isEmpty() || nb.isEmpty()) {
            return false;
        }
        if (na.equals(nb)) {
            return true;   // 짧은 답("네")은 바이그램이 안 나온다 — 같음은 여기서 답한다
        }
        Set<String> ba = bigrams(na);
        Set<String> bb = bigrams(nb);
        if (ba.isEmpty() || bb.isEmpty()) {
            return false;
        }
        Set<String> intersection = new HashSet<>(ba);
        intersection.retainAll(bb);
        Set<String> union = new HashSet<>(ba);
        union.addAll(bb);
        return (double) intersection.size() / union.size() >= THRESHOLD;
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
