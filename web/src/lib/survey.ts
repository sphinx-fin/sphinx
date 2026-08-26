/**
 * S-02 적합성(투자성향) 설문 정의. 소유: 오준서.
 *
 * ── 이 파일이 F-DET-002 의 입력 계약이다 ─────────────────────────────────────
 *
 * `ai-service/app/mismatch.py` 는 지금 `NotImplementedError("설문 스키마 확정 대기")` 다.
 * 대기 대상이 **이 화면**이다 — 설문을 만드는 곳이 S-02 뿐이라, 여기서 정한 문항 세트가
 * 그대로 모순 판정의 입력이 된다.
 *
 * 강희진 결정 ⓑ(`ai-service/proposals/F-DET-002-mismatch.md`)로 `surveyResult` 는
 * **freeform `Map<String,Object>`** 이고 `axis` 를 주지 않는다. 윤지석이 그 한계를 이렇게
 * 적어 뒀다:
 *
 *   > 어느 축의 모순인지는 **문항 키·값의 문면을 해석**해서 판정해야 한다.
 *   > 문항 문면이 바뀌면 판정이 흔들린다. 여기가 이 기능의 약한 고리다.
 *
 * 그래서 화면이 할 수 있는 보완을 두 가지 해 둔다.
 *
 *   ① **키가 축을 말하게 한다.** `SUIT-PRINCIPAL-LOSS` 처럼 축이 키에 드러나면 문면 해석이
 *      값 한 쪽으로 줄어든다. 문항 텍스트를 다듬어도 키는 그대로라 판정이 덜 흔들린다.
 *   ② **값을 등급이 아니라 문장으로 보낸다.** `"3"` 이나 `"HIGH"` 로 보내면 발화와 대조할
 *      문면이 없다. `"손실이 나더라도 감수할 수 있다"` 로 보내야 `"원금은 지켜지는 거죠?"`
 *      와 나란히 놓고 모순을 말할 수 있다 — P4 의 근거(무엇과 무엇이 모순인가)가 된다.
 *
 * **키 규약**: `surveyResult` 에는 `SUIT-` 로 시작하는 **문항만** 들어간다. 메타데이터는
 * 섞지 않는다.
 *
 * 문항 세트 버전은 예전에 `_surveySchemaVersion` 키로 이 맵에 얹어 우회했었다 —
 * `MismatchRequest.survey_schema_version` 자리는 있는데 `CreateSessionRequest` 에 대응
 * 필드가 없어서였다. **#61 로 typed 필드가 생겨 우회를 걷었다**(decision-log 10.8).
 * 이제 버전은 `CreateSessionRequest.surveySchemaVersion` 으로 나가고, 이 맵은 문항만
 * 담는다 — `mismatch.py` 가 `_` 접두어를 걸러내야 할 이유 자체가 없어졌다.
 *
 * ── 왜 이 6개인가 ────────────────────────────────────────────────────────────
 *
 * 기획서 4절이 드는 모순 예시("원금 손실 감수 가능 체크 ↔ 원금은 절대 손해 보면 안 된다")와
 * 3절 판결 동향("고객이 투자자정보확인서에 스스로 기재한 투자성향"이 은행 책임을 제한한
 * 근거로 인정됨)이 다투는 축을 덮는다. 취약 요인(연령·금액대·경험·채널)은 여기 없다 —
 * 세션 typed 필드로 가고 강희진이 가중한다(결정 ⓓ). 중복해서 묻지 않는다.
 */

/** 문항 세트의 버전. 문항을 추가·삭제·재문면화하면 올린다. */
export const SURVEY_SCHEMA_VERSION = "s02-survey-v1";

export interface SurveyQuestion {
  /** `surveyResult` 의 키. 축이 드러나게 짓는다. */
  id: string;
  /** 화면 문면. */
  text: string;
  /** 선택지 = 그대로 서버에 나가는 "기재 답변" 문장. */
  options: readonly string[];
}

export const SURVEY_QUESTIONS: readonly SurveyQuestion[] = [
  {
    id: "SUIT-RISK-PROFILE",
    text: "투자성향 진단 결과는 어디에 해당하십니까?",
    options: ["안정형", "안정추구형", "위험중립형", "적극투자형", "공격투자형"],
  },
  {
    id: "SUIT-PRINCIPAL-LOSS",
    text: "원금 손실이 발생할 수 있다는 점을 감수하실 수 있습니까?",
    options: [
      "손실이 나더라도 감수할 수 있다",
      "일부 손실까지만 감수할 수 있다",
      "원금 손실은 감수할 수 없다",
    ],
  },
  {
    id: "SUIT-LOSS-TOLERANCE",
    text: "감내하실 수 있는 손실 폭은 어느 정도입니까?",
    options: [
      "손실을 원하지 않는다",
      "10% 이내라면 감수할 수 있다",
      "20% 이내라면 감수할 수 있다",
      "20%를 넘는 손실도 감수할 수 있다",
    ],
  },
  {
    id: "SUIT-PURPOSE",
    text: "이 상품에 가입하시는 목적은 무엇입니까?",
    options: [
      "원금을 지키는 것이 가장 중요하다",
      "예금보다 조금 높은 수익을 원한다",
      "손실 위험을 감수하고 적극적인 수익을 원한다",
    ],
  },
  {
    id: "SUIT-HORIZON",
    text: "이 자금은 언제 사용하실 예정입니까?",
    options: [
      "1년 안에 써야 한다",
      "1~3년 안에 쓸 것 같다",
      "3~5년은 묶어둘 수 있다",
      "5년 이상 묶어둘 수 있다",
    ],
  },
  {
    id: "SUIT-PRODUCT-EXPERIENCE",
    text: "ELS·변액보험 등 원금비보장 상품에 가입해 보신 적이 있습니까?",
    options: [
      "없다",
      "있다 — 손실을 본 적은 없다",
      "있다 — 손실을 본 적이 있다",
    ],
  },
];

/**
 * 기획서 7-2 메인 데모 ①번의 응답 조합 — "위험 감수 가능에 체크 → 적합 판정".
 *
 * 리허설 때 6문항을 손으로 다시 고르지 않으려고 둔다. **연출이 아니다** — 이건 입력을
 * 채워 넣을 뿐이고, 적합/부적합도 신호등도 서버가 정한다(P1). 판매자가 값을 바꿔서
 * 시작할 수도 있다.
 *
 * 이 조합이 데모의 전제다: 설문은 전부 "감수 가능" 인데 ③번 발화가 "은행에서 파는 거니까
 * 원금은 지켜지는 거죠?" 로 나온다 → F-DET-002 모순 → R-02 로 적색.
 * 투자경험 "없음" + 적극투자형 조합은 기획서 3절이 지적한 "설문이 상품에 맞춰 유도되기
 * 쉽다" 의 표본이라 일부러 그대로 뒀다.
 */
export const DEMO_SURVEY_ANSWERS: Readonly<Record<string, string>> = {
  "SUIT-RISK-PROFILE": "적극투자형",
  "SUIT-PRINCIPAL-LOSS": "손실이 나더라도 감수할 수 있다",
  "SUIT-LOSS-TOLERANCE": "20%를 넘는 손실도 감수할 수 있다",
  "SUIT-PURPOSE": "손실 위험을 감수하고 적극적인 수익을 원한다",
  "SUIT-HORIZON": "5년 이상 묶어둘 수 있다",
  "SUIT-PRODUCT-EXPERIENCE": "없다",
};

/**
 * 화면 상태(문항ID → 답변) → `CreateSessionRequest.surveyResult` 맵.
 *
 * **문항만 담는다.** 세트 버전은 `SURVEY_SCHEMA_VERSION` 을 `surveySchemaVersion` 필드로
 * 따로 보낸다(10.8). 정의에 없는 문항 키는 넣지 않는다 — 화면 상태에 남은 옛 문항이 그대로
 * 나가면 `mismatch.py` 가 세트에 없는 축을 해석하려 든다.
 */
export function toSurveyResult(
  answers: Readonly<Record<string, string>>,
): Record<string, string> {
  const result: Record<string, string> = {};
  for (const q of SURVEY_QUESTIONS) {
    const answer = answers[q.id];
    if (answer) result[q.id] = answer;
  }
  return result;
}
