/**
 * 데모 진행용 답지 — 항목별 「U1 로 채점될 답변」·「U4 로 채점될 답변」 예시. 소유: 오준서.
 *
 * 이슈 #539. 진행자·테스터가 답지를 따로 들고 있지 않아도 S-03 에서 바로 넣어 볼 수 있게
 * 한다. **`VITE_SPHINX_DEMO_CAPTIONS=1` 로 빌드했을 때만 화면에 나온다**(S03_Interview.tsx).
 *
 * ── ❗이 파일은 고객에게 보이면 안 되는 종류다 ───────────────────────────────
 *
 * S-03 은 고객 화면이다(`role: CUST`). 거기 *"이렇게 답하면 통과"* 가 상시로 붙으면 기획
 * 7-4 역이용 방지와 정면으로 부딪친다 — `#494` 에서 루브릭을 **판매자에게도 주지 않기로**
 * 한 것보다 직접적이다. 그래서 화면은 플래그가 없으면 **렌더하지 않고**(숨기는 것이 아니다),
 * 플래그가 없는 빌드에는 이 문자열들이 **번들에 들어가지도 않는다** — 정적 치환된
 * `import.meta.env` 가 죽은 분기를 만들고 Rollup 이 이 모듈째 떨어뜨린다. 그 사실은 CI 가
 * 매번 확인한다(`.github/workflows/ci.yml` — "데모 답지가 기본 빌드에 없어야 한다").
 * `display:none` 으로 가리는 길로 가지 않는 이유가 이것이다: DOM 에 남으면 답지가 페이지
 * 소스에 그대로 있다.
 *
 * ── 출처를 문장마다 적는다 — 셋이 서로 다른 물건이다 ────────────────────────
 *
 * `labeled`  eval 코퍼스의 **실제 발화**이고 두 라벨러(강희진·정세현)가 **합의한** 등급이다.
 *            `eval/corpus/els.jsonl` + `eval/data/labels/*.jsonl` (합의 49 / 70, 실측).
 * `devset`   ai-service dev set 의 케이스와 그 `expected_grade`
 *            (`ai-service/tests/fixtures/utterances/*.yaml` · `tools/run_devset.py` 로 돈다).
 *            **사람 라벨이 아니라 팀이 기대값으로 정한 것**이다.
 * `written`  둘 다 없는 자리에 **내가 루브릭을 보고 쓴 대본**이다(`ai-service/app/rubrics/`).
 *            사람이 매긴 등급이 아니다 — 화면도 그렇게 적는다(이슈 #539 ①의 두 번째 길).
 *
 * ❗**`written` 을 «실측»으로 보이게 하지 않는다.** 이슈가 요구한 구별이 이것이고, 표시가
 * 사라지면 대본이 라벨된 데이터인 척하게 된다. 태그는 데이터에 있고 화면이 그대로 그린다.
 *
 * ── 어느 쪽이 어느 자리를 채우나 ────────────────────────────────────────────
 *
 * 이슈 #539 ②의 산출물이 나왔다(`eval/tools/build_demo_answers.py` → `data/demo_answers/`
 * · PR #544 · 정세현). **ELS 의 `labeled` 는 이제 그 파일에서 온다** —
 * `web/scripts/build-demo-answers.mjs` 가 `demoAnswers.generated.ts` 로 옮기고, 아래
 * `overlay` 가 이 표 위에 **등급 단위로** 덮는다. 라벨이 바뀌면 두 생성기를 차례로 돌리는
 * 것으로 화면이 따라온다 — 손으로 옮기던 것이 조용히 낡던 자리가 이것이었다.
 *
 * 그래서 이 파일에 남는 것은 **생성기가 낼 수 없는 것**뿐이다.
 *
 *   - 변액 7종 전부 — `eval/corpus/` 에 변액이 없다(`els.jsonl` 하나).
 *   - 라벨러가 **합의하지 못한** 자리의 U1 셋 — 생성기는 합의분만 담고 불합의는 키를 아예
 *     만들지 않는다(`build_demo_answers.py` 머리말). 그 구멍을 작성본이 메운다.
 *
 * ❗**web 은 `data/` 를 직접 못 읽는다.** Docker 빌드 컨텍스트가 `web/` 이라 이미지 안에
 * `data/` 가 없고(그 경로는 **로컬 빌드만 통과하고 배포에서만 깨진다**), 런타임 fetch 로
 * 두면 답지가 URL 로 열려 위 「고객 화면」 조건이 깨진다. 그래서 생성본을 **커밋**하고
 * CI 가 `--check` 로 낡음을 잡는다 — 근거는 `web/scripts/build-demo-answers.mjs` 머리말.
 *
 * ── 등급이 매번 같게 나오지는 않는다 ────────────────────────────────────────
 *
 * 채점은 LLM 이 한다. `labeled` 는 **사람이 매긴 등급**이고 `devset` 은 **기대값**이라,
 * 둘 다 "이 문장을 넣으면 그 등급이 나온다"는 보장이 아니다 — 자기일관성 캡(`#533`)이
 * 재고 있는 그 변동이다. 화면 아래 한 줄이 그 사실을 적는다. 테스터가 U1 예시로 U2 를
 * 받고 결함으로 올리는 일을 막으려는 것이다.
 */

import { LABELED_ANSWERS } from "./demoAnswers.generated";

/** 예시 문장의 출처. 화면이 이 값으로 라벨을 고른다. */
export type DemoAnswerSource = "labeled" | "devset" | "written";

export interface DemoAnswer {
  /** 그대로 입력창에 넣을 발화. */
  readonly text: string;
  readonly source: DemoAnswerSource;
}

/**
 * 항목 하나의 예시 쌍. **두 등급 다 «없을 수» 있다.**
 *
 * ❗필수로 두지 않는 이유는 생성본이 **부분**이기 때문이다 — 라벨러가 합의하지 못한 자리는
 * 키가 아예 없고(`build_demo_answers.py` 머리말), 그걸 필수 타입으로 받으면 «라벨이 없다»를
 * 채우려고 빈 문자열이나 대본이 들어간다. 화면은 없는 등급의 줄을 **안 그린다**.
 */
export interface DemoAnswerPair {
  /** 이해(U1) 쪽 예시 — 루브릭 `required_elements` 를 다 짚는 답변. */
  readonly u1?: DemoAnswer;
  /** 오해(U4) 쪽 예시 — `misconception_conditions` 에 걸리는 답변. */
  readonly u4?: DemoAnswer;
}

/** 화면에 적는 출처 문면. **`written` 이 무엇인지 반드시 드러나야 한다.** */
export const SOURCE_LABEL: Readonly<Record<DemoAnswerSource, string>> = {
  labeled: "라벨 합의 (실제 발화 · 두 명 일치)",
  devset: "dev set 기대값 (ai-service)",
  written: "루브릭에서 작성한 예시 (사람 라벨 아님)",
};

/**
 * **손으로 쓴 자리만** 남는 표. 키는 `ai-service/app/rubrics/<item_id>.yaml` 의 `item_id` 다 —
 * S-03 이 들고 있는 `askedItemId`(`NextQuestion.itemId` · `ReExplanation.itemId`)와 같은 값.
 *
 * 라벨 합의분은 여기 없다(`LABELED_ANSWERS` 가 덮는다 · 머리말). 사전적재 2종은 둘을 합쳐서
 * 다 덮는다(ELS 10 · 변액 7) — 이슈 #539 ①이 *"ELS 로만 갈지"* 를 물었는데, 시연에서 어느
 * 상품이 뜨는지는 그날 정해지고 **빈 캡션은 답지가 없는 것과 같으므로** 변액도 채운다.
 * 대신 그 출처가 `written`·`devset` 이라는 것이 화면에 그대로 나온다.
 */
const WRITTEN_ANSWERS: Readonly<Record<string, DemoAnswerPair>> = {
  // ── ELS (사전적재: 키움 4181) ───────────────────────────────────────────────
  // ❗**여기 셋만 남는다.** 나머지 일곱 항목과 이 셋의 U4 는 라벨 합의가 있어
  //   `demoAnswers.generated.ts` 에서 온다. 남은 U1 셋은 라벨러 둘이 **갈린** 자리라
  //   생성기가 키를 만들지 않았고(합의분만 담는다), 그 구멍을 루브릭에서 쓴 대본이 메운다.
  //   합의가 생기면 생성본에 키가 서고 아래 `overlay` 가 이 셋을 덮는다 — 그때 이 항목을
  //   지우는 것은 **선택**이다(라벨이 다시 갈리면 되살아나는 그물이 된다).
  "ELS-EARLY-REDEMPTION-CONDITION": {
    // 가까운 둘(els-0013 · els-0051)이 전부 U1↔U2 불일치라 답지에 «정답»으로 싣지 않는다.
    // 대신 그 둘이 갈린 지점(뒤 요소의 «만기까지»)을 명시해 루브릭 요소 2개를 다 짚는다.
    u1: {
      text: "평가일에 세 종목이 다 기준가 위에 있어야 조기상환이 되는 거고, 하나라도 못 미치면 그날은 그냥 넘어가서 다음 평가일이나 만기까지 계속 가는 거네요.",
      source: "written",
    },
  },
  "ELS-MIDWAY-REDEMPTION-COST": {
    // U1 합의 없음. 요소 둘(공정가액에서 «차감» · 그 결과 «원금 미달»)을 명시한다.
    u1: {
      text: "중간에 빼면 그 시점 값을 쳐서 거기서 또 얼마를 떼고 주는 거라, 넣은 돈보다 적게 나올 수 있다는 거죠.",
      source: "written",
    },
  },
  "ELS-TOTAL-LOSS-SCENARIO": {
    // U1 합의 없음(els-0021 이 U1↔U2 불일치). 요소 둘 중 뒤쪽(«본인 금액으로 환산»)이
    // 있어야 하므로 금액을 넣어 둔다 — 데모 세션의 가입금액과 다르면 그 자리만 바꿔 넣는다.
    u1: {
      text: "최악이면 제가 넣는 5천만 원이 한 푼도 안 남고 다 없어질 수도 있다는 거네요.",
      source: "written",
    },
  },

  // ── 변액연금 (사전적재: 삼성 B2601) ─────────────────────────────────────────
  // ❗**이 일곱은 사람이 매긴 라벨이 없다.** `eval/corpus/` 에 변액이 없어서다(이슈 #539 ①).
  //   `devset` 은 ai-service dev set 의 기대값이고, 나머지는 루브릭에서 쓴 대본이다.
  "VAR-PRINCIPAL-LOSS": {
    // dev set VAR-PRINCIPAL-UNDERSTOOD / VAR-SAVINGS-MISCONCEPTION(M05 결정론 매칭).
    u1: {
      text: "펀드 실적에 따라 변하고 최저보증이 없어서 중도해지하면 원금보다 적게 받을 수 있다고 들었어요.",
      source: "devset",
    },
    u4: { text: "그냥 저축인 줄 알고 넣었는데요.", source: "devset" },
  },
  "VAR-SURRENDER-BELOW-PREMIUM": {
    // 요소 하나(«납입보험료보다 적거나 없을 수 있음»)를 그대로 짚는다.
    u1: {
      text: "중간에 해지하면 그동안 낸 보험료보다 적게 나올 수 있고, 아예 못 받는 경우도 있다는 거죠.",
      source: "written",
    },
    // dev set VAR-FULL-REFUND-MISCONCEPTION — M06 결정론 매칭이라 재현이 안정적이다.
    u4: { text: "낸 돈은 다 돌려받는 거죠?", source: "devset" },
  },
  "VAR-FEE-DEDUCTION": {
    // dev set SPEECH-FORMAL-FEE-U1 / VAR-FEE-UNAWARE.
    // ❗이 항목은 `u1_requires: 3` 이다 — 차감 셋(매월 · 해지 시 · 펀드)을 다 말해야 U1 이다.
    u1: {
      text: "매달 위험보험료와 사업비가 먼저 빠지고, 중도해지하면 미상각신계약비도 빠지고, 펀드에서도 운용보수와 거래비용이 따로 차감됩니다",
      source: "devset",
    },
    u4: { text: "낸 돈 전부가 펀드에 들어가서 굴려지는 거 아닌가요?", source: "devset" },
  },
  "VAR-EARLY-SURRENDER-RATIO": {
    // dev set VAR-RATIO-UNDERSTOOD. **수치를 짚어야 U1 이다**(ADR-006 이 항목을 가른 이유) —
    // 58% 는 상품요약서 p12 해약환급금 예시표(적립형 · 3개월 · 58.4%)에서 온다.
    u1: { text: "3개월이면 낸 돈의 58% 정도만 돌아온다고 표에서 봤어요.", source: "devset" },
    // dev set 의 짝(VAR-RATIO-VAGUE)은 U2 라 오답 예시로 못 쓴다 — 오해 조건에서 쓴다.
    u4: { text: "몇 달 넣다가 해지해도 낸 돈은 거의 그대로 돌려받는 거죠?", source: "written" },
  },
  "VAR-PERFORMANCE-LINKED": {
    u1: {
      text: "보험금이나 해약환급금이 정해져 있는 게 아니라, 특별계정 운용 실적에 따라 오르내린다는 거죠.",
      source: "written",
    },
    // dev set VAR-FIXED-INTEREST.
    u4: { text: "정해진 이자가 붙어서 나오는 거죠?", source: "devset" },
  },
  "VAR-PARTIAL-DEPOSIT-INSURANCE": {
    // ❗**범위**를 요구하는 루브릭이다 — 「전액 보호」와 「전혀 보호 안 됨」이 **둘 다 U4** 다
    //   (dev set VAR-DEPOSIT-FULL · VAR-DEPOSIT-NONE). 한도 1억 원은 상품요약서 p2 원문.
    u1: {
      text: "계약 전체가 보호되는 게 아니라 약관에서 정한 최저사망지급금이랑 최저연금, 특약만 1억 원 한도로 보호된다는 거네요.",
      source: "written",
    },
    u4: { text: "보험사도 예금자보호 되니까 낸 돈은 다 보호되는 거죠?", source: "devset" },
  },
  "VAR-NOT-BANK-SAVINGS": {
    u1: {
      text: "은행 적금처럼 낸 돈이 그대로 쌓이는 게 아니라, 보장에 쓰이는 몫과 비용이 빠지고 남는 것만 적립되는 거라 예금하고는 성격이 다르다는 거죠.",
      source: "written",
    },
    // dev set VAR-SAME-AS-SAVINGS.
    u4: { text: "은행 적금이랑 같은 거라고 들었어요.", source: "devset" },
  },
};

/**
 * 항목ID 로 답지를 찾는다. 없으면 `null` — 화면은 그때 캡션 자리를 아예 그리지 않는다.
 *
 * **작성본 위에 라벨 합의분을 덮는다.** 라벨 합의가 작성본을 이긴다 — 사람이 매긴 등급이
 * 있는데 대본을 보여 줄 이유가 없고, 우선순위가 뒤집히면 라벨을 고쳐도 화면이 안 따라온다
 * (이 배선을 넣은 이유가 그것이다). ❗**항목이 아니라 «등급» 단위로** 덮는 것이 요점이다:
 * 항목째 갈아 끼우면 U4 만 합의된 항목에서 작성본 U1 이 같이 사라져 캡션이 반쪽이 된다 —
 * ELS 셋이 지금 그 모양이다.
 *
 * ── ❗**합친 표를 모듈 최상위에 두지 않는다** ───────────────────────────────
 *
 * `const DEMO_ANSWERS = overlay(WRITTEN_ANSWERS, LABELED_ANSWERS)` 로 두면 **꺼진 빌드에
 * 답지가 그대로 실린다.** 최상위 함수 호출은 Rollup 이 부수효과로 보고 모듈을 못 지우기
 * 때문이다 — 화면 쪽 `__DEMO_CAPTIONS__ ? … : null` 이 접혀서 아무도 이 모듈을 안 써도
 * 모듈은 남는다. 실측으로 **답지 34건이 기본 번들에 실렸다**(CI 「데모 답지가 기본 빌드에
 * 없어야 한다」가 그 자리에서 잡았다). `/* @__PURE__ *\/` 주석으로도 되지만, 그건 번들러가
 * 그 주석을 계속 지킨다는 데 거는 것이다. **최상위에 호출이 없으면 걸 것이 없다.**
 *
 * 그래서 찾을 때 둘을 합친다. 호출은 화면 하나에서 `useMemo` 로 항목이 바뀔 때만 돈다.
 */
export function demoAnswersFor(itemId: string | null): DemoAnswerPair | null {
  if (!itemId) return null;
  const pair: DemoAnswerPair = { ...WRITTEN_ANSWERS[itemId], ...LABELED_ANSWERS[itemId] };
  // ❗**두 등급이 다 없으면 «없다»로 답한다.** 빈 객체를 그대로 주면 화면이 답지 상자를
  //   열어 놓고 그 안이 비게 된다 — 진행자에게는 답지가 깨진 것으로 보인다.
  if (!pair.u1 && !pair.u4) return null;
  return pair;
}
