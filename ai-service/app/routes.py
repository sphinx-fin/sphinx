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
               coveragegap,
               rubricgen,
               rubrics, scoring, templates)
from .llm_client import LlmError, LlmNotConfigured, client as default_client
from .pii import PiiDetected, assert_clean
from .schemas import (
    CoverageGap,
    CoverageGapRequest,
    CoverageGapResponse,
    RubricListResponse,
    RubricView,
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
    except templates.TemplateNotFound as exc:
        # 템플릿 없는 상품유형은 후보 범위가 정의되지 않았다 — 위 `/extract`·`/question` 과
        # **같은 422** 다(#476 리뷰). ❗`except Exception` 으로 접으면 `_all()` 의 YAML 파싱
        # 오류(item_id 누락·중복·importance 불량)까지 400 이 되어, 부른 쪽은 자기 요청이
        # 잘못된 줄 알고 진짜 원인은 detail 문자열에만 남는다. 나머지 예외는 안 잡는다 —
        # 500 이 "판정을 못 했다" 의 옳은 표현이다.
        raise HTTPException(status_code=422, detail=str(exc)) from exc

    wanted = body.item_ids or known
    unknown = [i for i in wanted if i not in known]
    targets = [i for i in wanted if i in known]

    client = default_client()
    proposals, warnings = [], [f"템플릿에 없는 항목: {i}" for i in unknown]

    # ❗**문서 단위 준비물은 한 번만 만든다** (#476 리뷰).
    #
    # `chunk_document`·`Bm25`·`Dense.embed` 는 항목이 바뀌어도 같은 값이다. 항목마다
    # 바뀌는 것은 `query`(name+cue)와 그 아래 `qvec`·`search`·리랭킹뿐이다.
    # 항목마다 다시 만들면 ELS 13항목에서 **임베딩이 450여 텍스트 · 왕복 13회**가 된다
    # (청크 30~40개 × 13). `llm_client.embed` 에 캐시가 없어 전부 실제 호출이고,
    # `pii.assert_clean` 도 청크마다 13번 더 돈다.
    prep = None
    if targets:
        try:
            prep = rubricgen.prepare(doc, client)
        except LlmError as exc:
            raise _llm_unavailable(exc)

    for item_id in targets:
        try:
            proposals.append(rubricgen.propose_one(item_id, doc, client, prep=prep))
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


# ── 루브릭 열람 (이슈 #474 ③) ────────────────────────────────────────────────
#
# ❗**왜 조회가 필요한가** — 루브릭은 **공개 의무 대상**인데 지금 그것을 읽는 경로가
# 레포 어디에도 없었다(라우트 0 · server 0 · web 0). 화면이 볼 수 있는 것은 채점 결과에
# 인용된 **조항 한 줄**(`evidence.rubric_clause`)뿐이라, *"그 조항이 어느 기준에서 왔나"*
# 를 심사자가 대조할 방법이 없다.
#
# ❗**읽기 전용이다.** 승인 산출물은 `app/rubrics/*.yaml` **파일**이고, 승인은 git 커밋이다
# (`#475` 에서 강희진 확정 — 서버가 승인 상태를 갖지 않는다). 이 경로로 **쓰지 않는다** —
# 쓰면 정본이 둘(git 이력 ↔ 런타임 상태)이 되고, 채점이 파일만 읽는 규약이 깨지면
# `verify_rubric_clause_is_published` 가 순환한다(P4 · `#358` 과 같은 자리).
def _view(r: rubrics.Rubric) -> RubricView:
    return RubricView(
        item_id=r.item_id,
        product_type=r.product_type,
        name=r.name,
        status=r.status,
        required_elements=list(r.required_elements),
        u1_requires=r.u1_requires,
        misconception_conditions=list(r.misconception_conditions),
        related_misconceptions=list(r.related_misconceptions),
        # ❗`is not None` 이다 (`#493` 리뷰, 정세현). 빈 값을 `None` 으로 접으면
        #   **이 필드의 존재 이유**(빈 것과 없는 것을 가른다 — `#284`·`#396`)가
        #   그 자리에서 사라진다. 지금 빈 튜플은 도달 불가하지만(`rubrics._parse` 가
        #   `reason`·`until` 둘 다 없으면 던진다) **그 불변식을 여기서 끌어오지 않는다.**
        unlinked_until=list(r.unlinked_until) if r.unlinked_until is not None else None,
    )


@router.get("/rubrics", response_model=RubricListResponse)
def list_rubrics(product_type: str | None = None) -> RubricListResponse:
    """루브릭 전체(또는 상품유형별). **파일에 있는 것을 그대로 낸다.**

    `total` 은 **필터 전 전체 개수**다 — 걸러진 목록만 보이면 화면이 *"이게 전부"* 로
    읽는다(`R-00` 이 분모를 지키는 것과 같은 이유).
    """
    every = rubrics.all_rubrics()
    picked = [r for r in every.values() if product_type in (None, r.product_type)]
    return RubricListResponse(
        rubrics=[_view(r) for r in sorted(picked, key=lambda r: r.item_id)],
        total=len(every),
    )


@router.get("/rubrics/{item_id}", response_model=RubricView)
def get_rubric(item_id: str) -> RubricView:
    """항목 하나의 루브릭. 없으면 404 — **빈 루브릭을 지어내지 않는다.**

    채점은 루브릭이 없는 항목을 아예 못 매긴다(`recommended` 항목이 그렇다 — `#435`).
    여기서 빈 것을 내주면 화면이 *"기준이 없다"* 와 *"기준이 비어 있다"* 를 못 가른다.
    """
    try:
        return _view(rubrics.get(item_id))
    except rubrics.RubricNotFound:
        raise HTTPException(status_code=404, detail=f"루브릭이 없다: {item_id}") from None


# ── 커버리지 사각 (이슈 #474 1번 칸) ─────────────────────────────────────────
#
# ❗**「항목을 제안」하지 않는다 — 「안 덮는 문면」만 낸다.** `tools/find_coverage_gaps.py`
# 가 위험 어휘 필터를 두었다가 순환을 실측으로 겪었다(어휘를 앵커에서 유도하니 그 어휘를
# 든 문장은 당연히 앵커와 겹쳤다 — ELS 는 교집합 0 이라 사각을 하나도 못 냈다).
# **무엇이 위험 항목인지는 사람이 정한다.** `#476` 이 `u1_requires` 를 안 내는 것과 같은
# 규율이고, `importance` 도 결정 10.1 이 근거를 요구하는 규범이라 여기서 안 정한다.
#
# ❗**LLM 을 안 부른다.** `textsim.containment` 뿐이라 결정론적이고 쿼터를 안 쓴다.
@router.post("/template/gaps", response_model=CoverageGapResponse)
def coverage_gaps(body: CoverageGapRequest) -> CoverageGapResponse:
    """문서에서 어느 항목·조항도 안 덮는 문면을 낸다. 겹침이 낮은 것부터."""
    doc = body.parsed_document
    product_type = body.product_type or doc.product_type
    try:
        templates.get(product_type)
    except templates.TemplateNotFound as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from None
    limit = coveragegap.COVERED_MIN if body.limit is None else body.limit
    gaps = coveragegap.find_gaps(doc, product_type, limit=limit)
    return CoverageGapResponse(
        product_type=product_type,
        gaps=[CoverageGap(page=g.page, start=g.start, end=g.end, text=g.text,
                          best_overlap=g.best_overlap, covered_by=g.covered_by)
              for g in gaps],
        sentences_scanned=len(coveragegap.sentences(doc)),
        anchors_used=len(coveragegap.anchors(product_type)),
        limit=limit,
    )
