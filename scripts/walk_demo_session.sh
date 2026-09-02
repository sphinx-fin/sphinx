#!/usr/bin/env bash
# 실세션 하나를 S-01~S-07 로 끝까지 통과시킨다. 소유: 강희진
#
# ❗왜 필요한가 — 이슈 #278 ①
#
#   합성 세션(SyntheticSessionLoader)은 집계용이라 세션에 판정을 직접 넣고 불변 기록
#   (evidence/)을 안 쌓는다. ReportService.render() 는 그 스트림을 재생하므로,
#   대시보드에서 합성 세션을 눌러 교부 문서를 열면 **내용이 빈 PDF** 가 나온다.
#
#   교부 문서를 시연하려면 실제로 채점된 세션이 하나 있어야 하고, 그 세션은 면담 경로를
#   실제로 통과해야만 만들어진다. 이 스크립트가 그 한 세션을 만든다.
#
# ❗채점에는 폴백이 없다 (P1)
#
#   LLM 키가 없으면 질문은 템플릿 폴백으로 조용히 내려가지만(F-INT-002 설계) 채점은
#   502 AI_SERVICE_UNAVAILABLE 로 막힌다. 그건 정상이다 — 측정에 폴백을 두면 AI 가
#   재지 않은 값이 판정에 들어간다. 이 스크립트는 그 자리에서 죽지 않고 **무엇이
#   없어서 멈췄는지**를 말한다(exit 2).
#
# 사용법
#   scripts/walk_demo_session.sh
#   BASE=https://alpha.example ACTOR=seller-01 scripts/walk_demo_session.sh
#   scripts/walk_demo_session.sh --answers my_answers.json --product doc-els-kiwoom-4181
#
# 환경변수
#   BASE      서버 주소 (기본 http://localhost:8000)
#   ACTOR     행위자 ID (기본 seller-01) — 개방 모드에서 nginx 가 넣는 것과 같은 값
#   AUTH      Basic 자격 "user:pass". 주면 curl -u 로 붙인다. 로컬 permitAll 이면 불필요
#   PYTHON    pdfplumber 가 든 파이썬 (기본 python3). 7단계 내용 검사에만 쓴다
set -euo pipefail

BASE="${BASE:-http://localhost:8000}"
ACTOR="${ACTOR:-seller-01}"
PRODUCT=""
ANSWERS_FILE=""
OUT_DIR="${OUT_DIR:-$(mktemp -d)}"

while [ $# -gt 0 ]; do
    case "$1" in
        --answers) ANSWERS_FILE="$2"; shift 2 ;;
        --product) PRODUCT="$2"; shift 2 ;;
        --out)     OUT_DIR="$2"; shift 2 ;;
        -h|--help) sed -n '2,30p' "$0"; exit 0 ;;
        *) echo "모르는 인자: $1" >&2; exit 64 ;;
    esac
done

CURL=(curl -sS --max-time 60)
[ -n "${AUTH:-}" ] && CURL+=(-u "$AUTH")
JSON=(-H "Content-Type: application/json" -H "X-Actor-Id: $ACTOR")

step() { printf '\n\033[1m── %s\033[0m\n' "$*"; }
ok()   { printf '   ✅ %s\n' "$*"; }
bad()  { printf '   ❗%s\n' "$*" >&2; }

# ai-service 가 막혔을 때 **무엇이 없어서인지**를 가른다. 둘은 같은 502 로 나오는데
# 고치는 자리가 다르다 — 하나는 프로세스, 하나는 키다.
diagnose_502() {
    curl -sS --max-time 5 -o /dev/null "${AI_BASE:-http://localhost:8100}/docs" 2>/dev/null || {
        cat >&2 <<'DOWN'

   ai-service 가 응답하지 않는다. 서버는 그걸 502 로 옮길 뿐이다.
     cd ai-service && uvicorn app.main:app --port 8100
DOWN
        return
    }
    cat >&2 <<'NOKEY'

   ai-service 는 떠 있다. 그러면 남는 것은 LLM 키다.
   ai-service 로그에 `llm_error(LlmNotConfigured)` 가 있으면 키가 그 프로세스에 없다.

   ❗채점에는 폴백이 없다(P1) — 측정에 폴백을 두면 AI 가 재지 않은 값이 판정에 들어간다.
   질문은 템플릿으로 조용히 내려가지만 채점은 여기서 막힌다. 이슈 #278 ② 가 그것이다.
NOKEY
}

# 봉투를 벗긴다. success=false 면 error.code 를 들고 죽는다.
unwrap() {
    local body="$1" where="$2"
    if [ "$(jq -r '.success // "null"' <<<"$body")" != "true" ]; then
        bad "$where 실패: $(jq -c '.error // .' <<<"$body")"
        return 1
    fi
    jq -c '.data' <<<"$body"
}

step "0. 서버가 떠 있나"
if ! "${CURL[@]}" -o /dev/null -w '%{http_code}' "$BASE/products" | grep -q '^2'; then
    bad "$BASE 가 응답하지 않는다. server 가 :8000 에 떠 있는지 본다"
    exit 1
fi
ok "$BASE 응답"

step "1. 상품을 고른다"
if [ -z "$PRODUCT" ]; then
    PRODUCT=$(unwrap "$("${CURL[@]}" "${JSON[@]}" "$BASE/products")" "상품 목록" \
              | jq -r 'if type=="array" then .[0] else (.products[0] // .) end | .productId')
fi
[ -n "$PRODUCT" ] && [ "$PRODUCT" != "null" ] || { bad "상품을 못 찾았다"; exit 1; }
ok "productId=$PRODUCT"

step "2. 세션을 만든다 (S-01)"
SID=$(unwrap "$("${CURL[@]}" "${JSON[@]}" -X POST "$BASE/sessions" -d "$(jq -nc \
        --arg p "$PRODUCT" '{productId:$p, channel:"FACE_TO_FACE", ageBand:"60대",
                             experienceLevel:"NONE", amountBand:"50000000"}')")" \
     "세션 생성" | jq -r '.sessionId')
[ -n "$SID" ] && [ "$SID" != "null" ] || exit 1
ok "sessionId=$SID"

step "3. 질문과 답변을 끝까지 돈다 (S-04·S-05)"
FALLBACK="${DEFAULT_ANSWER:-제가 낸 돈에서 비용이 먼저 빠지고 남은 걸로 투자되니까, 은행 예금처럼 낸 만큼 그대로 쌓이는 게 아니고 원금보다 적게 돌려받을 수도 있다는 걸로 이해했습니다.}"
answer_for() {
    if [ -n "$ANSWERS_FILE" ]; then
        jq -r --arg k "$1" '.[$k] // .["*"] // empty' "$ANSWERS_FILE"
    fi
}

ASKED=0
while :; do
    QRESP=$("${CURL[@]}" "${JSON[@]}" -X POST "$BASE/sessions/$SID/questions/next")
    if [ "$(jq -r '.success' <<<"$QRESP")" != "true" ]; then
        bad "질문을 못 받았다: $(jq -c '.error' <<<"$QRESP")"
        # 질문에는 템플릿 폴백이 있다(F-INT-002). 그런데도 502 면 폴백 이전에 막힌 것이다.
        [ "$(jq -r '.error.code' <<<"$QRESP")" = "AI_SERVICE_UNAVAILABLE" ] && diagnose_502
        exit 2
    fi
    Q=$(jq -c '.data' <<<"$QRESP")
    [ "$(jq -r '.done' <<<"$Q")" = "true" ] && break

    ITEM=$(jq -r '.itemId' <<<"$Q")
    IDX=$(jq -r '"\(.index)/\(.total)"' <<<"$Q")
    printf '   %-8s %-34s %s\n' "$IDX" "$ITEM" "$(jq -r '.question' <<<"$Q" | cut -c1-40)…"

    TEXT="$(answer_for "$ITEM")"; [ -n "$TEXT" ] || TEXT="$FALLBACK"

    RESP=$("${CURL[@]}" "${JSON[@]}" -X POST "$BASE/sessions/$SID/answers" \
           -d "$(jq -nc --arg i "$ITEM" --arg t "$TEXT" '{itemId:$i, text:$t}')")
    if [ "$(jq -r '.success' <<<"$RESP")" != "true" ]; then
        CODE=$(jq -r '.error.code // "?"' <<<"$RESP")
        bad "채점이 막혔다 (itemId=$ITEM code=$CODE)"
        if [ "$CODE" = "AI_SERVICE_UNAVAILABLE" ] || [ "$CODE" = "MEASUREMENT_INVALID" ]; then
            diagnose_502
            exit 2
        fi
        exit 1
    fi
    printf '            → %s (신뢰도 %s)\n' \
        "$(jq -r '.data.grade // "?"' <<<"$RESP")" "$(jq -r '.data.confidence // "?"' <<<"$RESP")"
    ASKED=$((ASKED + 1))
done
ok "$ASKED 항목 채점 완료"

step "4. 판정한다 (S-05)"
GATE=$(unwrap "$("${CURL[@]}" "${JSON[@]}" -X POST "$BASE/sessions/$SID/judge")" "판정") || exit 1
# 판정 본문을 그대로 보인다 — 계약이 넓어지면(unmeasured·rulesVersion 등) 여기 같이 뜬다.
echo "   $(jq -c '.' <<<"$GATE")"
ok "signal=$(jq -r '.signal' <<<"$GATE")"

step "5. 리포트를 발행한다 (S-07)"
REPORT=$(unwrap "$("${CURL[@]}" "${JSON[@]}" -X POST "$BASE/sessions/$SID/report")" "리포트 발행") || exit 1
ok "contentHash=$(jq -r '.contentHash' <<<"$REPORT")"

step "6. PDF 를 받는다"
PDF="$OUT_DIR/report-$SID.pdf"
"${CURL[@]}" "${JSON[@]}" -o "$PDF" "$BASE/sessions/$SID/report/preview"
head -c 4 "$PDF" | grep -q '%PDF' || { bad "PDF 가 아니다: $(head -c 80 "$PDF")"; exit 1; }
ok "$PDF ($(wc -c <"$PDF" | tr -d ' ') bytes)"

step "7. ❗내용이 비지 않았는지 본다 — 이슈 #278 ① 이 잡으려는 것"
# ❗GET /sessions/{id}/report 로는 못 본다. 그건 메타(reportId·contentHash·URL)만 내고
# 본문은 PDF 안에만 있다. 여기서 종이를 직접 읽는 이유다.
PY_BIN="${PYTHON:-python3}"
if ! "$PY_BIN" -c 'import pdfplumber' 2>/dev/null; then
    bad "pdfplumber 가 없어 내용 검사를 건너뛴다 — pip install pdfplumber, 또는 PYTHON=<venv>/bin/python"
    bad "빈 리포트도 7KB 쯤 나오므로 크기만으로는 못 가른다"
else
    TEXT=$("$PY_BIN" - "$PDF" <<'PYEOF'
import sys, pdfplumber
with pdfplumber.open(sys.argv[1]) as doc:
    print("".join(p.extract_text() or "" for p in doc.pages))
PYEOF
)
    ITEMS=$(grep -cE '^· ' <<<"$TEXT" || true)
    GATES=$(grep -cE '(GREEN|YELLOW|RED)' <<<"$TEXT" || true)
    printf '   판정 이력 %s항목 · 게이트 변천 %s건\n' "$ITEMS" "$GATES"
    if [ "$ITEMS" -gt 0 ] && [ "$GATES" -gt 0 ]; then
        ok "내용이 있다 — 교부 문서 시연에 쓸 수 있다"
    else
        bad "리포트 내용이 비었다 — 이 세션은 교부 문서 시연에 못 쓴다"
        bad "합성 세션(synth-*)은 evidence 를 안 쌓아서 항상 이렇게 된다(#278 ①)."
        bad "이 스크립트가 만든 세션이어야 한다"
        exit 3
    fi
fi

printf '\n\033[1m데모에 쓸 세션\033[0m  %s\n' "$SID"
printf '  화면      %s/sessions/%s\n' "${WEB_BASE:-http://localhost:5173}" "$SID"
printf '  교부 문서  %s/sessions/%s/report/preview\n' "$BASE" "$SID"
