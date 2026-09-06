"""`/internal/*` 라우트. 소유: 윤지석 (엔드포인트 목록은 강희진 `AiServiceClient`와 합의)

라우트는 얇게 유지한다 — 검증 → PII 재검사 → 기능 함수 호출 → 응답. 기능 로직은
각 모듈의 순수 함수에 둔다(HTTP 없이 단위 테스트·프롬프트 튜닝을 돌릴 수 있어야 한다).

미구현 기능은 500이 아니라 **501 Not Implemented**를 돌려준다 — 강희진이 붙일 때
"아직 없음"과 "터짐"을 구분할 수 있어야 한다.

F-DET-002(적합성 모순)는 7번째 엔드포인트 `/internal/mismatch`로 확정됐다(강희진 결정).
모순 판정은 설문 전체 + 세션 발화 전체가 입력이라 항목 단위 /internal/score와 분리한다.
"""
from __future__ import annotations

import logging

from fastapi import APIRouter, HTTPException, status

from . import (extraction, misconception, mismatch, parsing, question_gen, reexplain,
               rubricgen,
               rubrics, scoring, templates)
from .llm_client import LlmError, LlmNotConfigured, client as default_client
from .pii import PiiDetected, assert_clean
from .schemas import (
    ConditionNotExtracted,
    ExtractRequest,
    ExtractResponse,
    Judgment,
    MisconceptionRequest,
    MisconceptionResponse,
    MismatchRequest,
    ParseRequest,
    QuestionRequest,
    QuestionResponse,
    ReexplainRequest,
    ReexplainResponse,
    SuitabilityMismatch,
    ScoreRequest,
    RubricProposeRequest,
    RubricProposeResponse,
)

log = logging.getLogger(__name__)

router = APIRouter(prefix="/internal", tags=["internal"])


def _not_implemented(feature: str, owner: str = "윤지석") -> HTTPException:
    return HTTPException(
        status_code=status.HTTP_501_NOT_IMPLEMENTED,
        detail=f"{feature} 미구현 (TODO({owner}))",
    )


def _llm_unavailable(exc: LlmError) -> HTTPException:
    """LLM 실패는 502 — 우리 버그가 아니라 상류 의존성 문제임을 구분한다."""
    code = (
        status.HTTP_503_SERVICE_UNAVAILABLE
        if isinstance(exc, LlmNotConfigured)
        else status.HTTP_502_BAD_GATEWAY
    )
    return HTTPException(status_code=code, detail=str(exc))


#: `/internal/*` 가 기계가 읽을 코드를 실어 보내는 유일한 경우. **문자열 detail 과 공존한다.**
#:
#: 나머지 실패는 `detail` 이 사람이 읽는 문자열이고 그대로 둔다 — 내부 오류 응답의 형식은
#: 아직 계약에 없다(결정 10.40, `ExtractResponse` 가 `openapi.yaml` 에 없는 것과 같은 구멍).
#: 전부 구조화하는 것은 그 결정이 난 뒤에 한 번에 하는 것이 맞다. 지금 한 자리만 구조화하는
#: 이유는 **다른 방법이 없어서**다: 이 실패와 상류 장애가 둘 다 502 라 상태 코드로는 못 가른다.
MEASUREMENT_INVALID_CODE = "MEASUREMENT_INVALID"


def _measurement_invalid(exc: scoring.MeasurementInvalid) -> HTTPException:
    """측정값이 우리 검증을 통과 못 한 경우 (`#280` ③).

    ## 왜 상태 코드로 못 가르나

    Spring 계약에서 `MEASUREMENT_INVALID` 와 `AI_SERVICE_UNAVAILABLE` 이 **둘 다 502** 다
    (`contracts/openapi.yaml` `ApiError.code`). 502 를 바꾸면 그건 다른 뜻이 된다 —
    이 실패는 진짜로 상류(우리) 문제이고 요청은 정상이었다. 그래서 본문에 코드를 싣는다.

    ## 왜 갈라야 하나

    지금은 `AiServiceClient` 가 상태 코드만 보고 `AiServiceException` 을 던져서, 화면과
    로그가 *"AI 가 죽었다"* 로 말한다. 실물은 *"모델이 인용을 지어냈고 다시 물어도 그랬다"*
    이고 **고칠 곳이 반대편**이다. 상류에 무엇을 고치라고 말하려면 둘이 달라야 한다 —
    `GlobalExceptionHandler` 가 두 코드를 가른 이유와 같은 문장이다.

    Spring 쪽 수신 배선은 강희진 영역이라 이 PR 에 없다. 여기서 내보내는 것까지가 내 몫이고,
    받기 전까지는 지금과 똑같이 502 로 취급된다 — **깨지는 것 없이 먼저 나갈 수 있다.**
    """
    return HTTPException(
        status_code=status.HTTP_502_BAD_GATEWAY,
        detail={"code": MEASUREMENT_INVALID_CODE, "message": str(exc)},
    )


# ── F-EXT-001 (정세현) ─────────────────────────────────────────────────────────
@router.post("/parse")
def parse(body: ParseRequest) -> dict:
    """상품문서 파싱. 구현은 `parsing.py`(정세현 소유) — 이 파일에서 대신 구현하지 않는다.

    **`response_model` 을 붙이지 않는다.** 붙이면 pydantic 이 미설정 optional 을 `null` 로
    채워 내보내는데(`parsed_at: null`) 계약의 그 필드는 nullable 이 아니고, 파서 출력이
    한 번 더 직렬화를 거치면 재현성 비교(P2)의 대상이 파서 출력이 아니게 된다.

    실패 셋을 각각 다른 코드로 낸다 — 고치는 자리가 전부 다르기 때문이다.
    경로 규칙 위반 400 · 파일 없음 404 · PDF 로 안 열림 422.
    """
    try:
        return parsing.parse_upload(
            body.document_path,
            product_type=body.product_type,
            document_id=body.document_id,
            parsed_at=body.parsed_at,
        )
    except parsing.DocumentPathRejected as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except parsing.DocumentNotFound as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    except parsing.DocumentUnreadable as exc:
        # 문서가 안 열리는 것은 상류 장애가 아니라 입력 문제다 — 502 로 나가면 Spring 쪽에서
        # "ai-service 장애"로 오진된다(`/extract` 의 413 과 같은 이유, PR #60 리뷰).
        raise HTTPException(status_code=422, detail=str(exc)) from exc


# ── F-EXT-002 ─────────────────────────────────────────────────────────────────
@router.post("/extract", response_model=ExtractResponse)
def extract(body: ExtractRequest) -> ExtractResponse:
    try:
        return extraction.extract(
            body.product_id, body.product_type,
            body.parsed_document.model_dump(),
        )
    except extraction.DocumentTooLarge as exc:
        # 문서가 큰 것은 상류 LLM 장애가 아니라 입력 문제다 — 502 로 나가면
        # Spring 쪽에서 "ai-service 장애"로 오진된다 (PR #60 리뷰)
        raise HTTPException(status_code=413, detail=str(exc)) from exc
    except templates.TemplateNotFound as exc:
        # 템플릿 없는 상품유형은 추출 범위가 정의되지 않았다 — 500 이 아니라 422다
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    except NotImplementedError:
        raise _not_implemented("F-EXT-002 추출")
    except LlmError as exc:
        raise _llm_unavailable(exc)


# ── F-INT-002 ─────────────────────────────────────────────────────────────────
@router.post("/question", response_model=QuestionResponse)
def question(body: QuestionRequest) -> QuestionResponse:
    try:
        return question_gen.generate(
            body.risk_item, body.asked_types, body.product_type,
            variant=body.variant, context=body.context)
    except ConditionNotExtracted as exc:
        # 계약이 허용하는 값이다(status=extraction_failed → condition: null). 500 이 아니라
        # 422 로 낸다 — 그 항목으로는 물을 것도 잴 것도 없다는 사실을 알린다 (이슈 #165 후속).
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    except templates.TemplateNotFound as exc:
        # 템플릿 밖 항목은 인터뷰 대상이 아니다 — 500 이 아니라 422다
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    except NotImplementedError:
        raise _not_implemented("F-INT-002 질문 생성")
    except LlmError as exc:
        raise _llm_unavailable(exc)


# ── F-SCR-001 ─────────────────────────────────────────────────────────────────
@router.post("/score", response_model=Judgment)
def score(body: ScoreRequest) -> Judgment:
    """고객 발화 채점. 미들웨어가 이미 본문을 검사했지만 발화는 여기서 한 번 더 본다 (P3)."""
    assert_clean(body.answer_text, "score.answer_text")
    try:
        return scoring.score(
            body.item_id, body.question, body.answer_text, body.risk_item, body.product_type,
            input_meta=body.input_meta,
        )
    except ConditionNotExtracted as exc:
        # 계약이 허용하는 값이다(status=extraction_failed → condition: null). 500 이 아니라
        # 422 로 낸다 — 그 항목으로는 물을 것도 잴 것도 없다는 사실을 알린다 (이슈 #165 후속).
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    except rubrics.RubricNotFound as exc:
        # 루브릭이 없는 항목은 채점하지 않는다 — 근거 없는 판정은 무효다 (P4)
        raise HTTPException(status_code=422, detail=f"루브릭 없음: {body.item_id}") from exc
    except NotImplementedError:
        raise _not_implemented("F-SCR-001 채점")
    except scoring.MeasurementInvalid as exc:
        # ❗`LlmError` 보다 **먼저** 잡는다 — 부분집합이라 순서가 바뀌면 아래로 삼켜진다.
        # `test_measurement_invalid_is_not_swallowed_by_the_generic_handler` 가 잠근다.
        raise _measurement_invalid(exc) from exc
    except LlmError as exc:
        raise _llm_unavailable(exc)


# ── F-DET-001 ─────────────────────────────────────────────────────────────────
@router.post("/misconception", response_model=MisconceptionResponse)
def detect_misconception(body: MisconceptionRequest) -> MisconceptionResponse:
    assert_clean(body.text, "misconception.text")
    try:
        # ❗**여기는 게이트를 안 태운다.** 이 엔드포인트는 재분석·큐 조회용이고, 답하는
        # 질문이 *"이 발화가 어느 오해 패턴과 겹치는가"* 라는 **결정론적 측정**이다.
        # 기획서 5절이 재현성을 그 성질로 설명한다 — LLM 을 끼우면 같은 입력이 회차마다
        # 갈릴 수 있고, 조회하는 사람이 그걸 구분할 방법이 없다.
        #
        # 게이트는 **판정 입력**에만 건다(`/score`) — 거기서만 `apply_misconception_floor`
        # 가 U4 를 확정하고, 오탐의 값이 거기서만 발생한다.
        return misconception.match(body.text, body.product_type)
    except NotImplementedError:
        raise _not_implemented("F-DET-001 오해 탐지")
    except LlmError as exc:
        raise _llm_unavailable(exc)


# ── F-DET-002 ─────────────────────────────────────────────────────────────────
@router.post("/mismatch", response_model=SuitabilityMismatch)
def detect_mismatch(body: MismatchRequest) -> SuitabilityMismatch:
    """설문 기재 vs 발화 모순. 세션 단위 판정이므로 /score와 분리한다.

    출력의 `mismatch`가 gate_rules.yaml R-02로 들어간다. 취약 요인 가중·코칭 스코어는
    여기서 하지 않는다 — 강희진 소유(역할분담표 v1.2 §38).
    """
    for utterance in body.utterances:
        text = utterance.get("text")
        if isinstance(text, str):
            assert_clean(text, "mismatch.utterances[].text")
    try:
        return mismatch.detect(
            body.session_id, body.survey_result, body.utterances, body.survey_schema_version
        )
    except mismatch.UnknownSurveyQuestion as exc:
        # 설문 세트가 우리가 아는 축을 벗어났다 — 요청이 잘못된 것이지 LLM 이 죽은 게 아니다.
        # 502 로 내면 "AI 가 안 된다"로 읽히고 세트 버전 불일치가 안 보인다.
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    except NotImplementedError:
        raise _not_implemented("F-DET-002 적합성 모순 탐지")
    except LlmError as exc:
        raise _llm_unavailable(exc)


# ── F-INT-004 (콘텐츠) ────────────────────────────────────────────────────────
@router.post("/reexplain", response_model=ReexplainResponse)
def do_reexplain(body: ReexplainRequest) -> ReexplainResponse:
    try:
        return reexplain.reexplain(
            body.risk_item, body.judgment, body.age_band, body.experience_level
        )
    except NotImplementedError:
        raise _not_implemented("F-INT-004 재설명")
    except LlmError as exc:
        raise _llm_unavailable(exc)


# ── 루브릭 후보 생성 (이슈 #474 ①) ────────────────────────────────────────────
@router.post("/rubric/propose", response_model=RubricProposeResponse)
def propose_rubric(body: RubricProposeRequest) -> RubricProposeResponse:
    """문서에서 루브릭 후보를 낸다. **파일을 쓰지 않는다 — 제안만 낸다.**

    ❗승인 산출물은 `app/rubrics/*.yaml` **파일**이다. 채점(`rubrics.get()`)은 그 파일만
    읽고 이 경로를 안 지난다 — 채점이 런타임 생성 기준을 쓰면
    `verify_rubric_clause_is_published` 가 순환한다(P4 · `#358` 과 같은 자리).

    항목 하나가 실패해도 나머지를 낸다. **실패를 은폐하지 않고 `warnings` 로 노출한다**
    (E-EXT-03 과 같은 규약) — 조용히 빠지면 사람이 "이 항목은 후보가 없구나" 와
    "이 항목이 죽었구나" 를 못 가른다.
    """
    doc = body.parsed_document
    try:
        known = [i.item_id for i in templates.get(doc.product_type).items]
    except Exception as exc:  # noqa: BLE001 — 모르는 상품유형은 400 이 맞다
        raise HTTPException(status_code=400, detail=f"상품유형 템플릿이 없다: {doc.product_type} — {exc}")

    wanted = body.item_ids or known
    unknown = [i for i in wanted if i not in known]
    targets = [i for i in wanted if i in known]

    client = default_client()
    proposals, warnings = [], [f"템플릿에 없는 항목: {i}" for i in unknown]
    for item_id in targets:
        try:
            proposals.append(rubricgen.propose_one(item_id, doc, client))
        except LlmError as exc:
            # 하나가 죽어도 나머지를 낸다 — 전부 실패면 아래에서 502 로 올린다.
            warnings.append(f"{item_id}: 후보 생성 실패 ({type(exc).__name__})")
        except Exception as exc:  # noqa: BLE001
            warnings.append(f"{item_id}: 후보 생성 실패 ({type(exc).__name__}: {exc})")

    if targets and not proposals:
        raise _llm_unavailable(LlmError("루브릭 후보를 하나도 못 냈다: " + "; ".join(warnings)))

    return RubricProposeResponse(
        document_id=doc.document_id,
        product_type=doc.product_type,
        proposals=proposals,
        warnings=warnings,
    )


__all__ = ["router", "PiiDetected"]
