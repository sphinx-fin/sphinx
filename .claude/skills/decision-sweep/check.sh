#!/usr/bin/env bash
# 결정 로그·ADR 형식 검증. 행을 넣은 뒤 돌린다.
#
# 잡는 것: 표 칸 수 어긋남 · 원문자 · 깨진 ADR 링크 · ADR 번호 구멍 · 기존 ADR 수정
set -uo pipefail
cd "$(git rev-parse --show-toplevel)"
fail=0

python3 - <<'PY' || fail=1
import re, glob, os, sys

CIRCLED = '①②③④⑤⑥⑦⑧⑨⑩⑪⑫'
bad = 0

# ── 1. 표 칸 수 ─────────────────────────────────────────────────────────
# 헤더는 구분선(|---|) 바로 앞줄이다. 절마다 칸 수가 다르므로 헤더로 고정하지 않는다.
# 이스케이프된 \| 는 셀 구분이 아니라 본문이므로 세지 않는다.
def cells(line):
    return len(re.findall(r'(?<!\\)\|', line))

for f in ['docs/decision-log.md']:
    lines = open(f, encoding='utf-8').read().split('\n')
    hdr, rows, off = None, 0, []
    for n, l in enumerate(lines, 1):
        if re.match(r'^\|[\s:|-]+\|\s*$', l):
            hdr = cells(lines[n-2]); continue
        if l.startswith('|') and hdr:
            rows += 1
            if cells(l) != hdr:
                off.append(f'{f}:{n}  칸 {cells(l)} ≠ 헤더 {hdr}  {l[:50]}')
        elif not l.startswith('|'):
            hdr = None
    print(f'표 칸 수    {rows}행 검사', '— 이상 없음' if not off else '')
    for o in off: print('  !!', o); bad = 1

# ── 2. 원문자 ───────────────────────────────────────────────────────────
# 팀원 환경에서 렌더되지 않는다. 출처 열의 항목 지시도 (4번) 처럼 적는다.
hits = []
for f in ['docs/decision-log.md', 'CLAUDE.md'] + sorted(glob.glob('docs/adr/*.md')):
    if not os.path.exists(f): continue
    for n, l in enumerate(open(f, encoding='utf-8'), 1):
        if any(c in l for c in CIRCLED):
            hits.append(f'{f}:{n}')
print(f'원문자      {len(hits)}행', '— 없음' if not hits else '')
for h in hits[:20]: print('  !!', h)
if len(hits) > 20: print(f'  !! ... 외 {len(hits)-20}행')
if hits: bad = 1

# ── 3. ADR 링크 ─────────────────────────────────────────────────────────
broken = []
for m in re.finditer(r'\]\((adr/[^)#]+\.md)', open('docs/decision-log.md', encoding='utf-8').read()):
    if not os.path.exists(os.path.join('docs', m.group(1))):
        broken.append(m.group(1))
print(f'ADR 링크    {len(broken)} 깨짐', '— 이상 없음' if not broken else '')
for b in broken: print('  !!', b); bad = 1

# ── 4. ADR 연번 ─────────────────────────────────────────────────────────
nums = sorted(int(re.match(r'(\d+)', os.path.basename(p)).group(1))
              for p in glob.glob('docs/adr/*.md')
              if re.match(r'\d+', os.path.basename(p)))
holes = [i for i in range(1, max(nums) + 1) if i not in nums] if nums else []
print(f'ADR 연번    001~{max(nums):03d}' if nums else 'ADR 없음',
      '— 구멍 없음' if not holes else f'— 구멍 {holes}')
if holes: bad = 1

# ── 5. 미결 담당자 ──────────────────────────────────────────────────────
# 이름 표는 .github/workflows/pr-reviewer-label.yml 이 정본이다(CLAUDE.md).
# 여기서 하드코딩하면 또 두 벌이 되고, 팀원이 바뀔 때 한쪽만 고쳐진다.
wf = '.github/workflows/pr-reviewer-label.yml'
names = set(re.findall(r'\)\s*echo "([가-힣]{2,4})"', open(wf, encoding='utf-8').read())) \
        if os.path.exists(wf) else set()

if not names:
    print('미결 담당자  건너뜀 — 이름 표를 못 찾았다:', wf)
else:
    rows, typos, unassigned = 0, [], []
    section = False
    for n, l in enumerate(open('docs/decision-log.md', encoding='utf-8'), 1):
        if l.startswith('## 10.'): section = True; continue
        if l.startswith('## ') and section: break
        if not (section and l.startswith('| 10.')): continue
        c = l.split(' | ')
        if len(c) < 4: continue
        rows += 1
        owner = c[2]
        found = [t for t in re.findall(r'[가-힣]{3}', owner) if t in names]
        # 오타는 "유효한 이름과 한 글자만 다른 것" 으로 잡는다. 그냥 3음절 한국어
        # 낱말까지 잡으면 노이즈가 나서 아무도 안 쓰게 된다.
        for t in re.findall(r'[가-힣]{3}', owner):
            if t in names: continue
            if any(sum(a != b for a, b in zip(t, v)) == 1 for v in names if len(v) == 3):
                typos.append(f'docs/decision-log.md:{n}  {c[0].strip()}  "{t}" — 오타인가?')
        if not found and not any(f':{n}  ' in t for t in typos):
            unassigned.append(f'docs/decision-log.md:{n}  {c[0].strip()}  담당 "{owner}"')

    print(f'미결 담당자  {rows}행 검사', '— 이상 없음' if not typos else '')
    for t in typos: print('  !!', t)
    if typos: bad = 1
    if unassigned:
        print(f'미결 무주    ? {len(unassigned)}행 — 담당에 이름이 없다. 안 움직여도 이상해 보이지 않는다')
        for u in unassigned: print('    ', u)
        if os.environ.get('SWEEP_STRICT') == '1': bad = 1
    else:
        print('미결 무주    없음')

# ── 6. 지난 기한 ────────────────────────────────────────────────────────
import datetime
today = datetime.date.today()
past, section = [], False
for n, l in enumerate(open('docs/decision-log.md', encoding='utf-8'), 1):
    if l.startswith('## 10.'): section = True; continue
    if l.startswith('## ') and section: break
    if not (section and l.startswith('| 10.')): continue
    c = l.split(' | ')
    if len(c) < 5: continue
    for m in re.finditer(r'(?<![\d/])(\d{1,2})/(\d{1,2})(?![\d/])', c[3]):
        try: d = datetime.date(today.year, int(m.group(1)), int(m.group(2)))
        except ValueError: continue
        if d < today:
            past.append(f'docs/decision-log.md:{n}  {c[0].strip()}  기한 {m.group(0)} 지남')
if past:
    print(f'지난 기한    ? {len(past)}행 — 미룬 것인가 잊은 것인가')
    for p_ in past: print('    ', p_)
    if os.environ.get('SWEEP_STRICT') == '1': bad = 1
else:
    print('지난 기한    없음')

sys.exit(bad)
PY

# ── 5. 기존 ADR 을 고쳤는가 ─────────────────────────────────────────────
# ADR 은 append-only 다. 결정이 바뀌면 새 ADR 을 추가하고 기존 문서는 상태만 갱신한다.
# base 와 "작업 트리"를 비교한다. ...HEAD 로 하면 커밋 전 변경을 놓쳐서, 고친 직후에
# "수정 없음" 이 나온다 — 실측했다. 커밋 전에 물어봐야 의미가 있는 검사다.
base="${1:-origin/main}"
touched=$(git diff --name-only "$base" -- docs/adr/ 2>/dev/null | while read -r f; do
            git cat-file -e "$base:$f" 2>/dev/null && echo "$f"; done)
# 이건 판정이 아니라 질문이다 — 표기 수정처럼 결정이 안 바뀌는 변경도 있다.
# 실패로 두면 정당한 변경이 영원히 못 지나가고, 그러면 아무도 이 스크립트를 안 쓴다.
# 게이트로 쓰려면 SWEEP_STRICT=1.
if [[ -n "$touched" ]]; then
  echo "기존 ADR     ? 수정됨 — 결정 문면이 바뀌었나? 바뀌었으면 새 ADR 로 간다"
  printf '     %s\n' $touched
  echo "             (표기·오타 수정이면 그대로 진행하고 PR 에 diff 를 보인다)"
  [[ "${SWEEP_STRICT:-}" == "1" ]] && fail=1
else
  echo "기존 ADR     수정 없음"
fi

if [[ $fail -eq 0 ]]; then
  echo; echo "통과"
else
  echo; echo "!! 위 !! 항목을 고친다"; exit 1
fi
