/**
 * ❗**자동 생성 — 손으로 고치지 않는다.**
 *
 * 생성기  web/scripts/build-demo-answers.mjs
 * 입력    data/demo_answers/els.json
 *         (그 파일은 eval/tools/build_demo_answers.py 가 라벨 코퍼스에서 낸다 — PR #544)
 *
 * 라벨 합의분(`labeled`)만 담는다. 루브릭에서 «작성한» 예시와 dev set 기대값은
 * `demoAnswers.ts` 에 손으로 남고, 같은 자리가 겹치면 **이 파일이 이긴다**.
 * 고치려면 라벨을 고치고 두 생성기를 차례로 돌린다(이슈 #539 ②).
 */
import type { DemoAnswerPair } from "./demoAnswers";

/** 항목ID → 라벨 합의 답지. **없는 등급은 키가 없다** — 「비었다」가 아니라 「라벨이 없다」다. */
export const LABELED_ANSWERS: Readonly<Record<string, DemoAnswerPair>> = {
  "ELS-EARLY-REDEMPTION-CONDITION": {
    // els-0016
    u4: {
      text: "만기까지만 들고 있으면 원금이랑 약속한 이자는 나오는 거죠?",
      source: "labeled",
    },
  },
  "ELS-ISSUER-CREDIT-RISK": {
    // els-0060
    u1: {
      text: "이건 예금이 아니라 증권사가 자기 이름으로 찍어내는 거라, 나라에서 지켜 주는 것도 없고 그 회사가 넘어가면 제 돈도 같이 없어지는 거네요.",
      source: "labeled",
    },
    // els-0058
    u4: {
      text: "발행한 증권사가 잘못되면 제 돈도 위험하다는 거잖아요. 그래도 그만한 회사가 넘어가는 일은 없을 테고, 지수만 안 빠지면 되는 거 아닌가요?",
      source: "labeled",
    },
  },
  "ELS-KNOCKIN-BARRIER": {
    // els-0009
    u1: {
      text: "기간 중에 셋 중 어느 하나라도 그 선 밑으로 한 번 내려가 버리면 그때 조건이 걸리는 거고, 셋이 같이 떨어져야 하는 건 아니라는 거죠.",
      source: "labeled",
    },
    // els-0012
    u4: {
      text: "만기 때 가격만 보면 되는 거 아닌가요? 중간에 잠깐 빠지는 건 상관없잖아요.",
      source: "labeled",
    },
  },
  "ELS-LOSS-SIMULATION": {
    // els-0037
    u1: {
      text: "보여 주신 건 예전 데이터로 돌려 본 거라서, 앞으로도 그렇게 된다는 뜻은 아니라는 거죠.",
      source: "labeled",
    },
    // els-0040
    u4: {
      text: "지난 10년 동안 손실 난 적이 없었다면서요. 그럼 앞으로도 괜찮은 거죠.",
      source: "labeled",
    },
  },
  "ELS-MATURITY-LOSS-CONDITION": {
    // els-0017
    u1: {
      text: "만기에 기준보다 밑이면 손실이 나는데, 얼마나 떨어져 있느냐에 따라 손실도 같이 커지는 구조라는 거죠.",
      source: "labeled",
    },
    // els-0020
    u4: {
      text: "낙인만 안 건드리면 되고, 한 번 건드리면 그냥 손실 확정인 거죠?",
      source: "labeled",
    },
  },
  "ELS-MIDWAY-REDEMPTION-COST": {
    // els-0036
    u4: {
      text: "원금은 그대로 있고 이자만 못 받는 거 아니에요?",
      source: "labeled",
    },
  },
  "ELS-NO-DEPOSIT-INSURANCE": {
    // els-0060
    u1: {
      text: "이건 예금이 아니라 증권사가 자기 이름으로 찍어내는 거라, 나라에서 지켜 주는 것도 없고 그 회사가 넘어가면 제 돈도 같이 없어지는 거네요.",
      source: "labeled",
    },
    // els-0008
    u4: {
      text: "5천만 원까지는 어차피 나라에서 지켜 주는 거 아니에요?",
      source: "labeled",
    },
  },
  "ELS-NO-LISTING": {
    // els-0062
    u1: {
      text: "중간에 돈이 필요하면 시장에 파는 게 아니라 발행사한테 사 달라고 해야 하는 건데, 그때는 값에서 얼마가 빠지고 나와서 넣은 돈보다 적을 수도 있다는 거죠.",
      source: "labeled",
    },
    // els-0032
    u4: {
      text: "급하면 그냥 주식처럼 내놓고 팔면 되지 않나요?",
      source: "labeled",
    },
  },
  "ELS-PRINCIPAL-LOSS-WARNING": {
    // els-0001
    u1: {
      text: "제가 넣은 돈이 그대로 다 돌아오는 게 아니고, 상황에 따라서는 원금에서 깎여서 나올 수도 있다는 거네요.",
      source: "labeled",
    },
    // els-0004
    u4: {
      text: "그래도 은행 창구에서 파는 건데, 최소한 넣은 돈은 지켜 주는 거 아닌가요?",
      source: "labeled",
    },
  },
  "ELS-TOTAL-LOSS-SCENARIO": {
    // els-0024
    u4: {
      text: "아무리 나빠도 절반 정도는 건지는 거 아닌가요?",
      source: "labeled",
    },
  },
};
