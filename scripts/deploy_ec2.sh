#!/usr/bin/env bash
# SphinX 배포 — EC2 에서 SSM Parameter Store 의 비밀을 받아 compose 를 띄운다.
# 소유: 오준서 (인프라 R). 이슈 #41 ④ · 결정로그 10.3.
#
# ── 왜 스크립트인가 ─────────────────────────────────────────────────────────
#
# `.env` 평문 파일을 EC2 에 두지 않기 위해서다. compose 의 `env_file:` 을 쓰면 키가 디스크에
# 남고, 그 인스턴스에 들어올 수 있는 사람 전부가 읽는다. 여기서는 SSM 에서 받아 **이 프로세스의
# 환경변수로만** 넘기므로 파일로 떨어지지 않는다.
#
# 키를 이미지에 굽지도 않는다 — 이미지 레이어는 지워도 히스토리에 남는다.
#
#   사용:  ./scripts/deploy_ec2.sh            # 받아서 띄운다
#          ./scripts/deploy_ec2.sh --check    # 값을 받아 확인만 하고 띄우지 않는다
#
# EC2 인스턴스 역할에 해당 파라미터의 ssm:GetParameter + kms:Decrypt 가 있어야 한다.
# 자세한 것은 docs/deployment.md.

set -euo pipefail

# 리전은 서울로 고정한다. 기획서 416·422행이 "국내 처리와 온프레미스 배포 옵션" ·
# "개인신용정보 처리는 국내 시스템으로 제한"을 논지로 쓰고 있어서, 데모를 us-east-1 에
# 올리면 우리 문서와 어긋난다. 리전 선택 비용은 0 이고 심사에서 물었을 때 답이 갈린다.
AWS_REGION="${AWS_REGION:-ap-northeast-2}"
SSM_PREFIX="${SSM_PREFIX:-/sphinx/prod}"

CHECK_ONLY=0
[ "${1:-}" = "--check" ] && CHECK_ONLY=1

# 아래 `cd` 는 --check 를 지난 뒤에 온다. 명부는 --check 에서도 읽으므로 여기서 절대경로를 잡는다.
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

command -v aws >/dev/null || { echo "aws CLI 가 없다. EC2 에 설치하거나 AWS_REGION 을 확인한다." >&2; exit 1; }
command -v docker >/dev/null || { echo "docker 가 없다." >&2; exit 1; }

# SecureString 을 복호화해 받는다. 값이 없으면 여기서 죽는다 — 빈 값으로 넘겨서 컨테이너가
# "떠 있지만 안 되는" 상태가 되는 것이 제일 나쁘다.
get() {
  local name="$1" out
  if ! out=$(aws ssm get-parameter \
              --region "$AWS_REGION" \
              --name "$SSM_PREFIX/$name" \
              --with-decryption \
              --query 'Parameter.Value' \
              --output text 2>/dev/null); then
    echo "SSM 에서 $SSM_PREFIX/$name 을 못 읽었다. 파라미터가 있는지, 인스턴스 역할에" >&2
    echo "ssm:GetParameter 와 kms:Decrypt 가 있는지 확인한다 (docs/deployment.md)." >&2
    exit 1
  fi
  if [ -z "$out" ] || [ "$out" = "None" ]; then
    echo "SSM 파라미터 $SSM_PREFIX/$name 이 비어 있다." >&2
    exit 1
  fi
  printf '%s' "$out"
}

echo "리전 $AWS_REGION · 접두어 $SSM_PREFIX 에서 비밀을 받는다"

export LLM_API_KEY;       LLM_API_KEY=$(get llm-api-key)
export SPHINX_API_USER;   SPHINX_API_USER=$(get api-user)
export SPHINX_API_PASSWORD; SPHINX_API_PASSWORD=$(get api-password)

# `/internal/*` 공유 시크릿 (이슈 #41 3항 · 결정 10.4). **server 와 ai-service 가 같은 값을
# 받는다** — 여기서 한 번 읽어 compose 가 양쪽에 같은 변수를 넘기므로 갈릴 수가 없다.
# `api-user`/`api-password` 를 nginx 와 server 가 나눠 쓰는 것과 같은 구조다(#162).
#
# 값이 없으면 위 get() 이 죽는다. 그게 맞다 — 양쪽 코드가 *"토큰이 비면 인증을 끈다"* 로
# 대칭이라, 빠뜨리면 **배포는 성공하고 시크릿 방어선만 조용히 꺼진 채로 뜬다.**
export SPHINX_INTERNAL_TOKEN; SPHINX_INTERNAL_TOKEN=$(get internal-token)

# ── nginx htpasswd 에 넣을 계정 목록 (이슈 #213 · #195 후속) ─────────────────
#
# `auth_basic` 이 server 블록에 걸려 있어 **htpasswd 에 없는 id 는 Spring 까지 오지도
# 못한다.** 한 계정만 만들던 때는 `compl-01` 이 로그인 창에서 401 로 끝났고, 그래서
# 역할 분리 시연(ADR-001 · 기획 7-4)이 배포에서 성립하지 않았다.
#
# ❗**명부가 근거고 htpasswd 가 따라간다.** 목록을 compose 나 SSM 에 따로 두면 계정 명부가
# 두 벌이 되고, 갈리는 날 화면은 열리는데 API 가 401 이다. 그래서 SSM 이 아니라 **레포의
# 명부에서 뽑는다** — `SecurityConfig.prodUsers` 가 등록하는 것과 같은 파일이라 갈릴 수 없다.
# (비밀번호는 여기 없다. 전 계정 공통이고 SSM 의 api-password 하나다.)
ROSTER="$REPO_ROOT/server/src/main/resources/demo_accounts.yaml"
[ -f "$ROSTER" ] || { echo "명부가 없다: $ROSTER (레포를 통째로 받았는지 확인한다)" >&2; exit 1; }

# `accounts:` 아래만 본다. 위쪽 `branches:` 와 주석에도 id 처럼 생긴 문자열이 있다.
accounts_block() {
  awk '/^accounts:/ { inblock = 1; next }
       /^[^[:space:]#]/ { inblock = 0 }
       inblock' "$ROSTER"
}

# 항목 형식: `  - { id: seller-01, role: SELLER, branch: BR-001, name: 김창구 }`
# `id:` 를 줄 어디에서든 받는다 — 키 순서가 바뀌거나 블록 스타일(`- id: …`)이 돼도 산다.
roster_ids=$(accounts_block | sed -n 's/^.*[^A-Za-z0-9_-]id:[[:space:]]*\([A-Za-z0-9_-]*\).*/\1/p')

# ❗**파싱이 어긋나면 조용히 계정이 줄어든다** — 이 이슈가 고치는 그 상태로 되돌아간다.
# 그래서 **목록 항목 수**(`- ` 로 시작하는 줄)와 **뽑아낸 id 수**를 대조한다. 둘이 같은
# 정규식에서 나오면 안 된다 — 형식이 바뀔 때 함께 줄어들어 가드가 눈을 감는다(실측으로
# 확인했다: `- {` 만 세던 첫 판은 블록 스타일에서 4건이 조용히 사라졌는데 통과했다).
entries=$(accounts_block | grep -c '^[[:space:]]*-[[:space:]]' || true)
found=$(printf '%s\n' "$roster_ids" | grep -c . || true)
if [ "$entries" -eq 0 ] || [ "$entries" != "$found" ]; then
  echo "명부에서 계정 id 를 못 뽑았다 (항목 ${entries}건 · 추출 ${found}건): $ROSTER" >&2
  echo "형식이 바뀌었으면 이 스크립트의 추출도 같이 고친다 — 안 고치면 nginx 가 아는 계정이" >&2
  echo "줄고 COMPL·MGR 로그인이 401 로 막힌다 (이슈 #213)." >&2
  exit 1
fi

# `SPHINX_API_USER` 가 명부에 없으면 Spring 이 기동을 거부한다(SecurityConfig.prodUsers).
# 배포를 다 돌리고 server 가 안 뜨는 것보다 여기서 죽는 편이 빠르고, 원인도 한 줄로 나온다.
case "
$roster_ids
" in *"
$SPHINX_API_USER
"*) ;; *)
  echo "SSM 의 api-user($SPHINX_API_USER)가 명부에 없다: $ROSTER" >&2
  echo "server 가 같은 이유로 기동을 거부한다 (결정 10.5)." >&2
  exit 1 ;;
esac

export SPHINX_API_USERS; SPHINX_API_USERS=$(printf '%s\n' "$roster_ids" | paste -sd, -)

# 선택값. 없으면 코드 기본값을 쓴다(config.py 의 DEFAULT_MODEL 등).
export LLM_API_BASE="${LLM_API_BASE:-}"
export LLM_MODEL="${LLM_MODEL:-}"

# 값 자체는 절대 찍지 않는다. 길이만 보여 "받긴 받았다"를 확인한다.
echo "  LLM_API_KEY           ${#LLM_API_KEY}자"
echo "  SPHINX_API_USER       ${#SPHINX_API_USER}자"
echo "  SPHINX_API_PASSWORD   ${#SPHINX_API_PASSWORD}자"
echo "  SPHINX_INTERNAL_TOKEN ${#SPHINX_INTERNAL_TOKEN}자"
# id 는 값이 아니라 명부다(레포에 있다). 길이가 아니라 **그대로** 찍는 이유가 그것이고,
# 데모 중 "이 계정으로 왜 안 되지" 를 이 한 줄로 가른다.
echo "  SPHINX_API_USERS      $SPHINX_API_USERS"

if [ "$CHECK_ONLY" = 1 ]; then
  echo "--check 라 여기서 멈춘다."
  exit 0
fi

cd "$(dirname "$0")/.."

# data/ 가 있어야 한다. 읽기 전용 볼륨의 원본이고, 없으면 docker 가 빈 디렉토리를 만들어
# 마운트해서 **오해 라이브러리 없이 컨테이너가 뜬다** — ai-service 는 로딩 시점에 죽으니
# 드러나지만, 원인이 "git clone 이 덜 됐다"라는 것은 로그만 봐서는 안 보인다.
for d in data/timeseries data/misconception_library contracts/samples; do
  [ -d "$d" ] || { echo "$d 가 없다. 레포를 통째로 clone 했는지 확인한다." >&2; exit 1; }
done

# ── F-DSH-003 합성 세션 ──────────────────────────────────────────────────────
#
# 산출물(`data/synth_sessions/sessions.json`)은 **추적하지 않는다** — `.gitignore` 가 첫
# 커밋부터 그렇게 정해 뒀다. `data/timeseries` 를 `fetch_timeseries.py` 로 받아오는 것과
# 같은 모양이라, 생성물은 배포 때 만든다.
#
# 없으면 대시보드가 빈 표가 된다(이슈 #179) — 집계가 세는 세션이 없어서 모든 칸이
# MIN_CELL_SAMPLE(30) 아래로 떨어진다. 생성은 seed 고정이라 매번 같은 값이 나온다.
echo "합성 세션 생성 (F-DSH-003)"
python3 scripts/gen_synth_sessions.py

echo "compose 기동"
docker compose up -d --build

echo
echo "상태:"
docker compose ps
echo
echo "확인:"
# `#162` 로 nginx 가 사이트 전체에 auth_basic 을 건 뒤로 자격증명 없는 GET / 는 401 이다.
# 예전 문구(`curl -fsS`)는 -f 때문에 비영점으로 죽었고, 붙여 넣은 사람이 **정상 동작을
# 배포 실패로 읽는다** — 그 시점이 리허설 직전이다.
#
# -u 로 자격증명을 넣는 쪽은 안 쓴다. 셸 히스토리와 ps 에 남아 `#162` 가 값을 파일·환경변수
# 로만 다루기로 한 것과 어긋난다. 그리고 상태코드를 그냥 찍는 편이 **더 많이 잡는다** —
# 401 이 나오는 것 자체가 성공 신호라, 200 이 나오면 auth_basic 이 빠진 것이다.
echo "  curl -sS -o /dev/null -w '%{http_code}\n' http://localhost/   # 401 이 정상이다(인증이 걸렸다는 뜻)"
echo "  docker compose logs -f server          # 기동 로그"
echo

# ❗**자격증명 확인은 안내하지 않고 여기서 한다** (PR #173 리뷰, 오준서).
#
# 위 57~58 의 export 는 이 스크립트 프로세스와 그 자식(docker compose)에만 산다. 오퍼레이터
# 셸은 스크립트의 **부모**라 값이 안 내려간다 — `-u "$SPHINX_API_USER:..."` 를 안내 문구로
# 찍으면 붙여 넣는 순간 `-u ":"` 가 되고, curl 이 빈 자격증명으로 Authorization 을 실제로
# 보내므로 **401 이 나면서 안내에는 "# 200" 이라고 적혀 있다.** `#170` 과 같은 종류이고,
# 이번엔 배포 실패가 아니라 "SSM 비밀번호가 틀렸나" 로 읽힌다.
#
# 값을 가진 것은 이 스크립트뿐이므로 확인도 여기서 하고 결과만 찍는다. `-K -` 로 stdin 에
# 넘기는 이유는 `-u` 가 argv 라 `ps` 에 보이기 때문이다 — 히스토리·`ps` 둘 다 안 남는다.
# curl config 의 따옴표 안에서는 `\` 와 `"` 가 이스케이프 문자다. 그대로 넣으면 파싱이
# 끊겨 **자격증명이 맞는데도 401** 이 나고, 이 스크립트는 "SSM 값과 htpasswd 가 다르다" 라고
# 찍는다 — 이 PR 이 고치려는 것과 정확히 같은 모양(정상을 다른 원인으로 읽게 만든다)이
# 한 겹 아래에서 반복된다(PR #173 리뷰, 강희진 실측). 따옴표를 빼는 것도 답이 아니다 —
# 그러면 공백에서 깨진다.
#
# 백슬래시를 **먼저** 치환한다. 순서를 바꾸면 방금 이스케이프한 백슬래시를 다시 이스케이프한다.
auth_user=${SPHINX_API_USER//\\/\\\\};     auth_user=${auth_user//\"/\\\"}
auth_pass=${SPHINX_API_PASSWORD//\\/\\\\}; auth_pass=${auth_pass//\"/\\\"}
auth_code=$(printf 'user = "%s:%s"\n' "$auth_user" "$auth_pass" |
            curl -sS -K - -o /dev/null -w '%{http_code}' http://localhost/ || true)
case "$auth_code" in
  200)      echo "자격증명 확인: 200 — 통과" ;;
  401)      echo "자격증명 확인: 401 — SSM 값과 nginx htpasswd 가 다르다. 둘 다 SSM 에서 나오는지 본다" ;;
  ''|000)   echo "자격증명 확인: 요청 자체가 안 갔다 — nginx 가 떴는지 본다 (docker compose ps)" ;;
  *)        echo "자격증명 확인: $auth_code — 예상 밖이다. server 로그를 본다" ;;
esac
echo
echo "❗보안그룹 인바운드는 80 만 연다. 8000·8100 을 열면 #41 의 1·3항(permitAll · ai-service 무인증)이 되살아난다."
