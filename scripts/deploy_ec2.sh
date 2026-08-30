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

# 선택값. 없으면 코드 기본값을 쓴다(config.py 의 DEFAULT_MODEL 등).
export LLM_API_BASE="${LLM_API_BASE:-}"
export LLM_MODEL="${LLM_MODEL:-}"

# 값 자체는 절대 찍지 않는다. 길이만 보여 "받긴 받았다"를 확인한다.
echo "  LLM_API_KEY         ${#LLM_API_KEY}자"
echo "  SPHINX_API_USER     ${#SPHINX_API_USER}자"
echo "  SPHINX_API_PASSWORD ${#SPHINX_API_PASSWORD}자"

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
