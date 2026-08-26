#!/usr/bin/env bash
# 결정 로그 훑기 — 워터마크 시각 이후에 "말이 오간 것"을 전부 덤프한다.
#
# 왜 스크립트인가: gh 한 번으로는 안 나온다. 본문 · 리뷰 본문 · 이슈 코멘트 ·
# 인라인 리뷰 코멘트가 각각 다른 곳에 있어서, 손으로 하면 매번 한 종류를 빠뜨린다.
#
# 사용법
#   ./sweep.sh                      워터마크를 docs/decision-log.md 에서 읽는다
#   ./sweep.sh 2026-08-26T07:00:00Z 시각을 직접 준다
set -euo pipefail

REPO="${SWEEP_REPO:-sphinx-fin/sphinx}"
LOG="${SWEEP_LOG:-docs/decision-log.md}"

# ── 워터마크 ────────────────────────────────────────────────────────────
if [[ $# -ge 1 ]]; then
  SINCE="$1"
else
  [[ -f "$LOG" ]] || { echo "!! $LOG 가 없다. 레포 루트에서 돌려라." >&2; exit 1; }
  SINCE=$(grep -oE '`[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z`' "$LOG" \
            | tr -d '`' | sort | tail -1 || true)
  [[ -n "$SINCE" ]] || {
    echo "!! $LOG 에서 워터마크를 못 찾았다." >&2
    echo "   '어디까지 훑었나' 표가 아직 없으면(PR #74 미머지) 시각을 인자로 줘라." >&2
    exit 1; }
fi

# 새 워터마크는 "지금"이 아니라 검색 시각에서 5분 뺀 값이다.
# 검색 색인에 지연이 있고, 워터마크는 "이 시각까지 다 읽었다"는 주장이라
# 앞당겨 적으면 다음 훑기가 그 구간을 건너뛴다. 겹쳐 읽는 건 공짜다.
read -r NEXT NEXT_DAY < <(python3 -c "
import datetime
n=datetime.datetime.now(datetime.timezone.utc)-datetime.timedelta(minutes=5)
print(n.strftime('%Y-%m-%dT%H:%M:%SZ'), n.strftime('%Y-%m-%d'))")

echo "═══ 훑기 범위 ═══"
echo "  이전 워터마크 : $SINCE"
echo "  다음 워터마크 : $NEXT   ← 다 읽었으면 이 값을 표에 적는다 (검색 시각 −5분)"
echo

# ── 대상 목록 ───────────────────────────────────────────────────────────
prs=$(gh search prs --repo "$REPO" --updated ">=$SINCE" --limit 100 \
        --json number,title,updatedAt,createdAt,author,state \
        -q '.[] | [.number, .createdAt, .updatedAt, .state, .author.login, .title] | @tsv' | sort -t$'\t' -k3)
iss=$(gh search issues --repo "$REPO" --updated ">=$SINCE" --limit 100 \
        --json number,title,updatedAt,createdAt,author,state \
        -q '.[] | [.number, .createdAt, .updatedAt, .state, .author.login, .title] | @tsv' | sort -t$'\t' -k3)

echo "═══ 색인 — PR ═══"
[[ -n "$prs" ]] && printf '%s\n' "$prs" | awk -F'\t' '{printf "  #%-4s %s  %-8s %-16s %s\n", $1, $3, $4, $5, $6}' || echo "  (없음)"
echo
echo "═══ 색인 — 이슈 ═══"
[[ -n "$iss" ]] && printf '%s\n' "$iss" | awk -F'\t' '{printf "  #%-4s %s  %-8s %-16s %s\n", $1, $3, $4, $5, $6}' || echo "  (없음)"
echo
echo "!! 번호가 낮아도 다시 열어본다. updatedAt 이 새로우면 새 말이 오간 것이다."
echo

# ── 본문·코멘트 ─────────────────────────────────────────────────────────
dump() {   # $1=kind(pr|issue) $2=number $3=createdAt $4=title
  local kind=$1 n=$2 created=$3 title=$4
  echo
  echo "────────────────────────────────────────────────────────────────"
  echo "▌#$n  $title"
  echo "────────────────────────────────────────────────────────────────"

  # 본문은 이번 창에서 새로 생긴 것만. 이미 훑은 항목은 코멘트만 새 것이다.
  if [[ "$created" > "$SINCE" ]]; then
    echo "── 본문 (새로 생김) ──"
    gh "$kind" view "$n" --repo "$REPO" --json body -q '.body' 2>/dev/null || true
    echo
  else
    echo "── 본문: 이전 훑기에서 읽음 (생성 $created) — 아래 새 코멘트만 본다 ──"
  fi

  if [[ "$kind" == "pr" ]]; then
    echo "── 리뷰 (본문 포함 · 승인 리뷰에도 결정이 들어 있다) ──"
    gh pr view "$n" --repo "$REPO" --json reviews \
      -q ".reviews[] | select(.submittedAt > \"$SINCE\") | \"[\(.submittedAt)] \(.author.login) \(.state)\n\(.body)\n\"" 2>/dev/null || true

    echo "── 인라인 리뷰 코멘트 ──"
    gh api "repos/$REPO/pulls/$n/comments?per_page=100" \
      -q ".[] | select(.created_at > \"$SINCE\") | \"[\(.created_at)] \(.user.login) \(.path):\(.line // .original_line)\n\(.body)\n\"" 2>/dev/null || true
  fi

  echo "── 코멘트 ──"
  gh "$kind" view "$n" --repo "$REPO" --json comments \
    -q ".comments[] | select(.createdAt > \"$SINCE\") | \"[\(.createdAt)] \(.author.login)\n\(.body)\n\"" 2>/dev/null || true
}

[[ -n "$prs" ]] && while IFS=$'\t' read -r n created _upd _st _au title; do
  dump pr "$n" "$created" "$title"
done <<< "$prs"

[[ -n "$iss" ]] && while IFS=$'\t' read -r n created _upd _st _au title; do
  dump issue "$n" "$created" "$title"
done <<< "$iss"

echo
echo "═══ 다 읽었으면 워터마크에 이 행을 넣는다 ═══"
echo "| $NEXT_DAY | \`$NEXT\` — <반영한 PR·이슈> | <이 훑기를 담는 PR> |"
