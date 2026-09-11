#!/usr/bin/env bash
#
# patrol/scan.sh — 지금 내 손에 있는 것을 한 번에 뽑는다.
#
# 사람이 읽는 출력이다. 판단은 하지 않는다 — 무엇이 있는지만 늘어놓고,
# 우선순위와 처리는 SKILL.md 가 정한다.
#
#   ./scan.sh            전체
#   ./scan.sh mine       내 PR 만
#   ./scan.sh inbox      내가 답할 차례인 것만
set -euo pipefail

REPO="${REPO:-$(gh repo view --json nameWithOwner -q .nameWithOwner)}"
ME="${ME:-$(gh api user -q .login)}"
WHAT="${1:-all}"

hr() { printf '\n\033[1m== %s ==\033[0m\n' "$1"; }

# PR 한 건에서 마지막으로 말한 사람과 그 시각. 코멘트와 리뷰를 같이 본다 —
# 리뷰만 보면 "코멘트로 답한 뒤 승인을 기다리는 중" 을 못 가른다.
last_speaker() {
  gh pr view "$1" --repo "$REPO" --json comments,reviews -q '
    [ (.comments[]? | {at: .createdAt, who: .author.login})
    , (.reviews[]?  | {at: .submittedAt, who: .author.login}) ]
    | sort_by(.at) | last | if . == null then "(발언 없음)" else "\(.who) \(.at[0:16])" end'
}

pr_rows() {
  gh pr list --repo "$REPO" --state open --limit 60 \
    --json number,title,author,isDraft,reviewDecision,labels,statusCheckRollup,reviewRequests \
    -q "$1"
}

if [ "$WHAT" = all ] || [ "$WHAT" = mine ] || [ "$WHAT" = inbox ]; then
  hr "내 PR — 마지막으로 말한 사람이 남이면 내 차례다"
  # 체크는 빨강과 진행중을 가른다 — 뭉치면 "아직 안 돌았다" 가 "깨졌다" 로 읽힌다.
  pr_rows ".[] | select(.author.login == \"$ME\" and .isDraft == false)
           | \"\(.number)\t\(if (.reviewDecision // \"\") == \"\" then \"리뷰없음\" else .reviewDecision end)\t\(
               [.statusCheckRollup[]? | select(.name != null) | select((.conclusion // \"\") | IN(\"SUCCESS\", \"NEUTRAL\", \"SKIPPED\") | not)]
               | map(if (.status // \"\") == \"COMPLETED\" then \"빨강\" else \"진행중\" end)
               | if length == 0 then \"초록\" else (group_by(.) | map(\"\(length)\(.[0])\") | join(\" \")) end
             )\t\(.title[0:60])\"" \
  | while IFS=$'\t' read -r n decision checks title; do
      printf '#%-5s %-18s %-10s %s\n        마지막 발언: %s\n' \
        "$n" "$decision" "$checks" "$title" "$(last_speaker "$n")"
    done
fi

if [ "$WHAT" = all ] || [ "$WHAT" = inbox ]; then
  hr "내게 리뷰 요청된 PR"
  gh pr list --repo "$REPO" --search "review-requested:@me state:open" \
    --json number,title,author,reviewDecision \
    -q '.[] | "#\(.number)\t\(.author.login)\t\(.title[0:70])"' || echo "(없다)"

  hr "나를 멘션한 열린 항목 — 최근순 15"
  gh api "search/issues?q=repo:$REPO+mentions:$ME+state:open&sort=updated&per_page=15" \
    -q '.items[] | "#\(.number)\t\(if .pull_request then "PR " else "이슈" end)\t\(.updated_at[0:16])\t\(.title[0:60])"'
fi

if [ "$WHAT" = all ]; then
  hr "내가 맡은 열린 이슈"
  gh issue list --repo "$REPO" --assignee "$ME" --state open --limit 40 \
    --json number,title,labels,updatedAt \
    -q '.[] | "#\(.number)\t\(.updatedAt[0:10])\t[\([.labels[].name]|join(","))]\t\(.title[0:60])"'

  hr "임자 없는 열린 이슈 — 내 영역이면 가져올 후보다"
  gh issue list --repo "$REPO" --state open --limit 40 \
    --json number,title,assignees,labels,updatedAt \
    -q '.[] | select(.assignees | length == 0)
         | "#\(.number)\t\(.updatedAt[0:10])\t[\([.labels[].name]|join(","))]\t\(.title[0:60])"'

  hr "남의 PR 중 초록인 것 — 머지하지 않는다. 알려만 준다"
  pr_rows '.[] | select(.author.login != "'"$ME"'" and .isDraft == false and .reviewDecision == "APPROVED")
           | "#\(.number)\t\(.author.login)\t\(.title[0:60])"'
fi

printf '\n'
