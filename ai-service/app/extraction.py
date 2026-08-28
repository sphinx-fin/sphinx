"""F-EXT-002 필수 이해항목 추출. 소유: 윤지석

기획서 5절 통제: *"상품유형 템플릿으로 추출 범위를 고정한다."*
`templates.get(product_type)` 에 없는 항목은 뽑지 않는다 — 그 목록이 F-EXT-003 재현율의
분모이기도 하다(이슈 #26).

출력은 `contracts/risk_item.schema.json`.

## 스팬은 모델에게 묻지 않는다

모델은 **인용과 페이지만** 낸다. `[start, end)` 는 `parsing.resolve_span()` 이 원문에서
찾는다. 모델이 준 숫자를 믿으면 계약 항등식
`pages[page].text[start:end] == condition.value_text` 가 깨질 수 있고, **깨진 채로도 추출은
성공한 것처럼 보인다** — 화면(S-01·S-05)에서 하이라이트가 밀릴 때 드러난다.
우리가 계산하면 항등식이 구성상 성립한다.

`value_text` 도 모델의 인용이 아니라 **원문에서 잘라낸 문자열**을 쓴다. PDF 는 문장 중간에
개행을 넣으므로 둘이 다를 수 있고, 계약이 요구하는 것은 원문 쪽이다(P6 — 수치는 원문 인용만).

## 실패를 은폐하지 않는다 (E-EXT-03)

템플릿에 있는데 문서에서 못 찾은 항목은 빼지 않고 `status="extraction_failed"` 로 낸다.
빠뜨리면 S-01 의 추출 실패 큐에 아무것도 안 뜨고, 재현율이 왜 낮은지도 알 수 없다.
스팬 해소 실패·거짓양성 가능(loose)·모호한 스팬도 `ExtractionWarning` 으로 노출한다.

## 청킹

계약 샘플 기준 문서 전체가 ELS 13k자 · 변액 20k자다. 한 번에 넣으면 모델이 문서 전체를
보므로 조각 좌표 환산 문제가 아예 없고 정확도도 낫다. 문서가 커지면 청킹이 필요해지므로
`MAX_DOCUMENT_CHARS` 를 넘으면 경고 대신 **거부**한다 — 조용히 잘라내면 뒷부분 항목이
전부 미검출로 잡히고 그 원인이 보이지 않는다.
"""
from __future__ import annotations

from pathlib import Path

import re

from . import numerics, parsing, templates, textsim
from .llm_client import LlmClient, LlmError, client as default_client
from .schemas import (
    Condition,
    ExtractedCandidate,
    ExtractionDraft,
    ExtractionWarning,
    ExtractResponse,
    RiskItem,
    SourceSpan,
)

class DocumentTooLarge(ValueError):
    """문서가 한 번에 넣을 수 있는 한도를 넘었다.

    LlmError 로 던지면 라우트가 502 로 매핑하고, Spring 쪽에서 "ai-service 장애" 로
    오진된다(PR #60 리뷰). 문서가 큰 것은 상류 LLM 장애가 아니라 입력 문제다.
    """


PROMPT_PATH = Path(__file__).resolve().parent / "prompts" / "F-EXT-002_v1.md"
PROMPT_VERSION = "F-EXT-002_v1"

#: 이 길이를 넘으면 청킹 설계가 필요하다. 조용히 잘라내지 않고 거부한다.
MAX_DOCUMENT_CHARS = 60_000

#: 템플릿에 importance 가 미부여일 때 쓰는 값(이슈 #26). RiskItem.importance 가 계약상
#: required 라 비워둘 수 없다. 기획서가 이 항목들을 "필수 이해항목"이라 부르므로 보수적으로
#: required 를 쓰고, 자리표시자임을 경고로 노출한다.
IMPORTANCE_FALLBACK = "required"


def extract(
    product_id: str,
    product_type: str,
    parsed_document: dict,
    llm: LlmClient | None = None,
) -> ExtractResponse:
    """파싱된 문서 → 필수 이해항목."""
    template = templates.get(product_type)
    doc = parsed_document

    total = sum(len(p["text"]) for p in doc["pages"])
    if total > MAX_DOCUMENT_CHARS:
        raise DocumentTooLarge(
            f"문서가 {total:,}자로 한 번에 넣을 수 있는 한도({MAX_DOCUMENT_CHARS:,})를 넘는다. "
            "청킹 설계가 필요하다 — 조용히 잘라내면 뒷부분 항목이 전부 미검출로 잡힌다."
        )

    draft = (llm or default_client()).complete_json(
        prompt=build_prompt(template, doc),
        model_cls=ExtractionDraft,
        schema_name="ExtractionDraft",
        system=load_system_prompt(),
        # 공시 상품문서다. 기획서 7-3: "상품설명서(공시 자료이므로 개인정보가 아니다)".
        # 발행사 민원부서 번호 같은 법인 연락처가 인쇄돼 있어 넓은 휴리스틱이 정상 문서를
        # 막는다 — 실측으로 `02-785-7424` 가 ACCOUNT 에 걸려 추출이 멈췄다.
        pii_scope="public_document",
    )

    known = {i.item_id: i for i in template.items}
    warnings: list[ExtractionWarning] = []
    resolved: dict[str, RiskItem] = {}

    for candidate in draft.candidates:
        if candidate.item_id not in known:
            warnings.append(ExtractionWarning(
                code="UNKNOWN_ITEM_ID", item_id=candidate.item_id,
                message=f"템플릿에 없는 항목 — 추출 범위 밖이라 버린다",
            ))
            continue
        if candidate.item_id in resolved:
            continue                      # 규칙 4 — 항목당 하나
        item = _to_risk_item(candidate, known[candidate.item_id], product_id, doc, warnings)
        if item is not None:
            resolved[candidate.item_id] = item

    # E-EXT-03 — 못 찾은 항목을 빼지 않고 실패로 낸다
    for template_item in template.items:
        if template_item.item_id in resolved:
            continue
        warnings.append(ExtractionWarning(
            code="ITEM_NOT_FOUND", item_id=template_item.item_id,
            message=f"문서에서 찾지 못했다 ({template_item.name})",
        ))
        resolved[template_item.item_id] = _failed_item(template_item, product_id, warnings)

    ordered = [resolved[i.item_id] for i in template.items]
    return ExtractResponse(items=ordered, warnings=warnings)


# ── 후처리 ────────────────────────────────────────────────────────────────────
def _to_risk_item(
    candidate: ExtractedCandidate,
    template_item: templates.TemplateItem,
    product_id: str,
    doc: dict,
    warnings: list[ExtractionWarning],
) -> RiskItem | None:
    """인용을 원문 스팬으로 해소한다. 못 하면 None — 호출자가 실패 항목으로 만든다."""
    span = _resolve(candidate, doc, warnings)
    if span is None:
        span = _rescue(candidate, doc, template_item, warnings)
    if span is None:
        warnings.append(ExtractionWarning(
            code="SPAN_UNRESOLVED", item_id=candidate.item_id,
            message=f"인용을 원문에서 찾지 못했다: {candidate.quote[:40]!r}",
        ))
        return None

    if span["match"] == "loose":
        warnings.append(ExtractionWarning(
            code="LOOSE_MATCH", item_id=candidate.item_id,
            message="낱자 사이 개행까지 허용해 찾았다 — 거짓 양성 가능, 사람 확인 필요",
        ))

    occurrences = parsing.find_occurrences(doc, span["source_span"]["page"], candidate.quote)
    if len(occurrences) > 1:
        warnings.append(ExtractionWarning(
            code="AMBIGUOUS_SPAN", item_id=candidate.item_id,
            message=f"같은 문면이 페이지에 {len(occurrences)}회 — 어느 것인지 확정 불가",
        ))

    return RiskItem(
        item_id=candidate.item_id,
        product_id=product_id,
        name=template_item.name,
        importance=_importance(template_item, warnings),
        status="extracted",
        condition=Condition(
            value_text=span["value_text"],          # 모델 인용이 아니라 원문
            source_span=SourceSpan(**span["source_span"]),
        ),
    )


def _resolve(candidate: ExtractedCandidate, doc: dict, warnings: list[ExtractionWarning]):
    """모델이 지목한 페이지를 먼저 보고, 없으면 나머지 페이지를 훑는다.

    페이지를 틀리는 것은 인용을 틀리는 것과 다르다 — 문면이 원문에 있으면 항목은 실재하고
    위치만 잘못 짚은 것이다. 그 경우를 실패로 처리하면 실재하는 항목이 미검출이 된다.
    다만 조용히 넘기지 않고 PAGE_CORRECTED 로 남긴다.
    """
    pages = [p["page"] for p in doc["pages"]]
    if candidate.page in pages:
        span = parsing.resolve_span(doc, candidate.page, candidate.quote)
        if span is not None:
            return span

    for page in pages:
        if page == candidate.page:
            continue
        span = parsing.resolve_span(doc, page, candidate.quote)
        if span is not None:
            warnings.append(ExtractionWarning(
                code="PAGE_CORRECTED", item_id=candidate.item_id,
                message=f"모델이 p{candidate.page} 라고 했으나 p{page} 에서 찾았다",
            ))
            return span
    return None


# ── 인용 좁히기 구제 (P6 조건부) ──────────────────────────────────────────────
#: 이보다 짧아지면 어느 조건인지 특정되지 않는다.
MIN_NARROWED_QUOTE_CHARS = 12

#: 좁히기 시도 상한. `loose` 전략은 낱자마다 `\s*` 를 끼운 정규식이라 페이지 전체 검색이
#: 싸지 않다. 실패한 항목에만 도는 경로지만 상한을 둔다 — 긴 것부터 보므로 답은 앞에서 나온다.
MAX_RESCUE_ATTEMPTS = 160

_WS_SPLIT = re.compile(r"\s+")


def _narrowed_candidates(quote: str) -> list[str]:
    """좁힌 인용 후보. **긴 것부터** — 조건 문면을 최대한 남긴다.

    문장 경계로만 자르면 안 된다. 표가 섞인 구간의 열 누출은 **문장부호를 데리고 오지
    않는다** — 실측 원문이 이렇게 생겼다.

        …최초기준가격의 85%인 / 조기상환지급일에 자동조기상환되며, 상환금액은 (연 11.00%)
        환 다음과 같습니다.

    `(연 11.00%)` 와 `환` 이 옆 열에서 끼어든 조각이고 그 앞에 마침표가 없다. 그래서
    **어절 단위 연속 구간**을 본다. 앞에 끼는 경우도 뒤에 끼는 경우도 있어 양쪽을 다 만든다.
    """
    tokens = _WS_SPLIT.split(quote.strip())
    if len(tokens) < 2:
        return []
    out: list[str] = []
    for size in range(len(tokens) - 1, 0, -1):
        for i in range(len(tokens) - size + 1):
            window = " ".join(tokens[i:i + size])
            if len(window) >= MIN_NARROWED_QUOTE_CHARS:
                out.append(window)
            if len(out) >= MAX_RESCUE_ATTEMPTS:
                return out
    return out


#: 좁힌 창이 항목의 것이라고 보려면 `cue` 와 이만큼 겹쳐야 한다.
#:
#: **왜 `cue` 인가** — 좁히기 후보 중 무엇이 진짜 조건인지 판별할 신뢰할 만한 신호가
#: `cue` 뿐이다. 그것이 그 항목이 무엇인지를 정의하는 유일한 문면이고, `cue` 에는 숫자를
#: 금지해 뒀으므로(회차마다 달라진다) 수치에 휘둘리지 않는다. 표 조각은 cue 어휘와 무관해서
#: 낮게 나온다.
#:
#: PR #118 리뷰(정세현)의 지적으로 넣었다. 그전 규칙("수치가 하나라도 남으면 통과 + 첫 성공
#: 즉시 반환")은 **"누출 열이 진짜 조건문보다 짧다" 는 암묵 가정**에만 의존했고, 역방향
#: 픽스처로 실제로 깨지는 것을 확인했다(아래 실측).
#:
#:     항목                            진짜 조건   누출 조각
#:     ELS-ELDERLY-COOLING              0.560      0.040
#:     ELS-EARLY-REDEMPTION-CONDITION   0.633      0.200
#:
#: 0.35 는 두 군 사이다. 낮은 쪽(0.200)보다 위, 높은 쪽(0.560)보다 아래.
CUE_CONTAINMENT_MIN = 0.35

#: **`cue` 판별이 통하지 않는 항목이 있다** — 표 셀이 그렇다. 결정로그 10.45(정세현 전수
#: 실측)에서 나왔다. 계약 정답 23건 중 7건이 임계 아래이고 둘은 **0.000** 이다.
#:
#:     VAR-EARLY-SURRENDER-RATIO  0.000
#:       cue         경과기간별 해약환급금 예시표의 초기 시점 환급률 수치
#:       value_text  3개월 900,000 526,240 58.4
#:
#: 표 셀은 **순수 수치**라 자연어 cue 와 어휘가 하나도 안 겹친다. 임계값을 낮추는 것으로는
#: 해결되지 않는다(0.000 은 어떤 임계값도 못 살린다). 그리고 그 7건이 전부 표·수치 항목인데
#: **표 셀이야말로 열 누출이 나는 곳**이라, 임계가 가장 센 곳이 구제가 가장 필요한 곳이다.
#:
#: 그래서 항목 성격에 따라 기준을 **자동으로 바꾼다.** 후보 어느 것도 cue 와 겹치지 않으면
#: (`best_cue < CUE_CONTAINMENT_MIN`) cue 판별이 이 항목에 작동하지 않는다고 보고, 대신
#: **원 인용의 수치와 가장 많이 겹치는 창**을 고른다 — 표 셀에서는 수치가 곧 그 조건의
#: 정체이기 때문이다. 정세현이 `#118` 리뷰에서 처음 제시한 선택지(`kept ∩ want` 최대)가
#: 여기서 제 자리를 찾는다. 자연어 항목에서 그걸 안 쓰는 이유는 그대로다 — 누출 열이
#: 가짜 수치를 데려오므로 자연어 조건에서는 cue 가 더 정확하다.
#: 정규화 후 숫자 비율이 이 값 이상이면 **표 셀**로 본다 — cue 판별이 원리적으로 통하지
#: 않는 구간이다. 계약 정답 전수 실측으로 갈랐다.
#:
#:     표 셀(cue 0.000)        0.889 · 0.955
#:     cue 통과군 최대          0.242
#:
#: 0.5 는 그 사이이고 여유가 넓다. **cue 미달 자연어 후보는 계속 거부한다** — 그쪽 보호를
#: 유지해야 `#118` 리뷰가 막은 결함(누출 열을 조건으로 잡는 것)이 다시 열리지 않는다.
#:
#: ## 무엇에 재는가 — 창이 아니라 **원 인용**이다 (PR #152 리뷰, 정세현)
#:
#: 처음에는 이 비율을 창(`narrowed`)마다 쟀다. 그러면 자연어 항목에서 우회로가 열린다.
#: 창은 원 인용의 부분열이므로, 인용에 표 열이 누출돼 들어오면 **그 누출 조각만으로 된 창**
#: 이 숫자비율 1.000 으로 게이트를 통과한다. 실측 재현:
#:
#:     항목        ELS-NO-LISTING (cue 0.182 — 자연어 미달 5건 중 하나)
#:     quote       '본 증권은 상장하지 않을 예정이므로' + '× [100%+ 5.50%]'(p7 누출)
#:
#:     창 단위 판정   p7 표 셀 스팬을 p14 자연어 항목의 조건으로 반환.
#:                   경고는 "수치는 전부 남았다" — `want` 가 누출 조각에서 나와 겹침 2/2 다
#:     인용 단위 판정 원 인용 숫자비율 0.286 → 자연어 → cue 보호 유지 → 안전한 실패
#:
#: 위 두 군을 인용 전체로 재도 그대로 갈린다(0.889·0.955 vs 0.286). 그리고
#: `containment(cue, 창) ≤ containment(cue, 인용)` 이 항상 성립하므로(창이 인용의 연속
#: 부분열이라 bigram 이 부분집합) 판별을 인용 단위로 내려도 **통과할 수 있었던 창을 잃지
#: 않는다.** 표 셀 2건의 구제는 유지되고 자연어 항목의 cue 보호도 유지된다.
TABULAR_DIGIT_RATIO = 0.5


def _digit_ratio(text: str) -> float:
    normalized = textsim.normalize(text)
    if not normalized:
        return 0.0
    return sum(ch.isdigit() for ch in normalized) / len(normalized)


def _rescue(candidate: ExtractedCandidate, doc: dict, template_item: templates.TemplateItem,
            warnings: list[ExtractionWarning]):
    """인용이 안 풀릴 때 어절 경계로 좁혀 다시 해소한다. 결정론이다 — LLM 을 다시 부르지 않는다.

    ## 후보를 고르는 규칙 두 개

    통과 조건이 둘이고, **둘 다 없으면 엉뚱한 표 셀이 조건으로 들어온다.**

    1. **`cue` 와 겹쳐야 한다** (`CUE_CONTAINMENT_MIN`). 누출 조각도 원문에 실재하므로
       "원문에서 찾았다" 만으로는 진짜 조건과 구별되지 않는다 — 원 인용이 해소에 실패한 것은
       두 열이 이어붙은 문자열이 원문에 없어서지 각 조각이 없어서가 아니다.
    2. **수치가 하나도 안 남는 좁히기는 거부한다** (P6). 막아야 하는 것은
       `condition.value_text` 에 숫자가 없는 RiskItem 이다. 추출은 성공으로 보이는데
       채점 프롬프트의 `[상품 조건 원문]` 에 수치가 없어 "수치로 언급" 루브릭이 검증 불가가
       되고, 재설명은 원문에 없는 숫자를 전부 환각 처리한다. 조용히 등급을 망친다.

    "수치 **전부** 보존" 은 쓸 수 없다 — 누출 열이 가짜 수치를 데려오므로 정답을 탈락시킨다
    (`11.00%` 는 옆 열 값이고 조건의 수치는 `85%` 다).

    ## 첫 성공이 아니라 cue 가 가장 높은 창을 고른다

    길이 내림차순으로 첫 성공을 취하면 **누출이 진짜보다 길 때 누출을 고른다.** 역방향
    픽스처로 재현했다 — 각주(45자)가 조건(23자)보다 길어 먼저 잡히고, 정답 스팬 대신
    각주 스팬이 나왔다. 통과 후보를 모아 `cue` 포함도 최댓값을 고르고, 동점이면 긴 쪽이다.

    수치가 일부만 남으면 받되 어느 수치가 떨어졌는지 경고에 적는다(`사람 확인 필요:`).
    """
    want = set(numerics.numbers(candidate.quote))
    cue = template_item.cue

    # **표 셀 여부는 항목 단위로 한 번 판단한다** (PR #152 리뷰, 정세현).
    # 창(`narrowed`)에 걸면 안 된다 — 창은 원 인용의 부분열이므로 자연어 항목의 인용에 표
    # 열이 누출되면 **그 누출 조각만으로 된 창**이 숫자비율 1.000 으로 통과한다. 그러면
    # `#118` 리뷰가 막은 결함이 그대로 다시 열린다(`test_natural_language_item_...` 이 재현).
    #
    # 인용 단위로 재야 하는 이유는 성질에도 있다. 표 셀인지는 *창의 성질*이 아니라 그 인용이
    # 통째로 표에서 왔는가의 문제다. 그리고 `containment(cue, 창) ≤ containment(cue, 인용)`
    # 이 항상 성립하므로(창이 인용의 연속 부분열 → bigram 부분집합) **판별을 인용 단위로
    # 내려도 통과할 수 있었던 창을 잃지 않는다.**
    tabular = _digit_ratio(candidate.quote) >= TABULAR_DIGIT_RATIO

    accepted: list[tuple[float, int, str, dict]] = []
    refused_no_number: str | None = None
    refused_off_cue: str | None = None

    for narrowed in _narrowed_candidates(candidate.quote):
        probe = candidate.model_copy(update={"quote": narrowed})
        span = _resolve(probe, doc, [])       # 이 단계의 PAGE_CORRECTED 는 삼킨다
        if span is None:
            continue
        if want and not set(numerics.numbers(narrowed)):
            refused_no_number = refused_no_number or narrowed
            continue
        cue_score = textsim.containment(cue, narrowed)
        if cue_score < CUE_CONTAINMENT_MIN and not tabular:
            # 자연어인데 cue 와 안 겹친다 — 표 옆 열 조각일 수 있다. 거부한다.
            refused_off_cue = refused_off_cue or narrowed
            continue
        accepted.append((cue_score, len(narrowed), narrowed, span))

    if accepted:
        best_cue = max(a[0] for a in accepted)
        if best_cue >= CUE_CONTAINMENT_MIN:
            cue_score, _, narrowed, span = max(accepted, key=lambda a: (a[0], a[1]))
            basis = f"cue 포함도 {cue_score:.2f}"
        else:
            # 표 셀 인용만 여기 온다 — 자연어 인용은 위 필터가 cue 미달 창을 전부 걸렀으므로
            # `accepted` 가 비고 이 분기에 도달하지 않는다. 이제 주석과 코드가 같은 말을 한다.
            cue_score, _, narrowed, span = max(
                accepted,
                key=lambda a: (len(want & set(numerics.numbers(a[2]))), a[1]),
            )
            kept_n = len(want & set(numerics.numbers(narrowed)))
            basis = (f"cue 최댓값 {best_cue:.2f} < {CUE_CONTAINMENT_MIN} · 인용 숫자비율 "
                     f"{_digit_ratio(candidate.quote):.2f} — 표 셀로 보고 수치 겹침 "
                     f"{kept_n}/{len(want)}개로 골랐다")
        dropped = sorted(want - set(numerics.numbers(narrowed)))
        detail = "수치는 전부 남았다" if not dropped else f"사람 확인 필요: 빠진 수치 {dropped}"
        warnings.append(ExtractionWarning(
            code="QUOTE_NARROWED", item_id=candidate.item_id,
            message=(f"인용을 {len(candidate.quote)}자 → {len(narrowed)}자로 좁혀 해소했다 "
                     f"({basis}, 후보 {len(accepted)}건). {detail}. "
                     f"남은 수치 {sorted(set(numerics.numbers(narrowed))) or '없음'}"),
        ))
        return span

    if refused_off_cue is not None:
        warnings.append(ExtractionWarning(
            code="NARROWING_REFUSED", item_id=candidate.item_id,
            message=(f"좁힌 인용({len(refused_off_cue)}자)은 원문에서 찾았지만 cue 와 겹치지 "
                     f"않아 거부했다 — 표 옆 열 조각일 수 있다. 인용: {refused_off_cue[:50]!r}"),
        ))
    elif refused_no_number is not None:
        warnings.append(ExtractionWarning(
            code="NARROWING_REFUSED", item_id=candidate.item_id,
            message=(f"좁힌 인용({len(refused_no_number)}자)은 원문에서 찾았지만 수치가 하나도 "
                     f"남지 않아 거부했다 — 원 인용 수치 {sorted(want)}. 조건에서 수치가 "
                     f"사라지면 채점이 조용히 망가진다"),
        ))
    return None


def _failed_item(
    template_item: templates.TemplateItem, product_id: str,
    warnings: list[ExtractionWarning],
) -> RiskItem:
    """E-EXT-03 — 실패를 은폐하지 않는다. 조건이 비어도 스키마는 값을 요구하므로
    빈 스팬과 사유 문면을 넣고 status 로 구분한다."""
    return RiskItem(
        item_id=template_item.item_id,
        product_id=product_id,
        name=template_item.name,
        importance=_importance(template_item, warnings),
        status="extraction_failed",
        condition=Condition(
            value_text="(추출 실패 — 문서에서 해당 조건을 찾지 못했다)",
            source_span=SourceSpan(page=1, start=0, end=0),
        ),
    )


def _importance(template_item: templates.TemplateItem, warnings: list[ExtractionWarning]) -> str:
    if template_item.importance_assigned:
        return template_item.importance          # type: ignore[return-value]
    if not any(w.code == "IMPORTANCE_PLACEHOLDER" for w in warnings):
        warnings.append(ExtractionWarning(
            code="IMPORTANCE_PLACEHOLDER",
            message=f"템플릿 importance 미부여 — {IMPORTANCE_FALLBACK} 로 채웠다 (이슈 #26)",
        ))
    return IMPORTANCE_FALLBACK


# ── 프롬프트 ──────────────────────────────────────────────────────────────────
def _prompt_sections() -> tuple[str, str]:
    text = PROMPT_PATH.read_text(encoding="utf-8")
    system = text.split("## system", 1)[1].split("## user", 1)[0].strip()
    user = text.split("## user", 1)[1].strip()
    return system, user


def load_system_prompt() -> str:
    return _prompt_sections()[0]


def build_prompt(template: templates.ProductTemplate, doc: dict) -> str:
    _, user = _prompt_sections()
    template_lines = "\n".join(
        f"- {i.item_id} ({i.name}): {i.cue}"
        + (f" [{i.section_hint}]" if i.section_hint else "")
        for i in template.items
    )
    document_lines = "\n\n".join(
        f"=== p{p['page']} ===\n{p['text']}" for p in doc["pages"]
    )
    return user.format(template_lines=template_lines, document_lines=document_lines)
