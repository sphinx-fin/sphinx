#!/usr/bin/env bash
# SphinX 배포 — EC2 에서 SSM Parameter Store 의 비밀을 받아 blue/green 으로 띄운다.
# 소유: 오준서 (인프라 R). 이슈 #41 ④ · 결정로그 10.3 · 7.39("무중단 배포" 리라이트).
#
# ── 왜 스크립트인가 ─────────────────────────────────────────────────────────
#
# `.env` 평문 파일을 EC2 에 두지 않기 위해서다. compose 의 `env_file:` 을 쓰면 키가 디스크에
# 남고, 그 인스턴스에 들어올 수 있는 사람 전부가 읽는다. 여기서는 SSM 에서 받아 **이 프로세스의
# 환경변수로만** 넘기므로 파일로 떨어지지 않는다.
#
# 키를 이미지에 굽지도 않는다 — 이미지 레이어는 지워도 히스토리에 남는다.
#
#   사용:  ./scripts/deploy_ec2.sh            # 받아서 blue/green 으로 띄운다
#          ./scripts/deploy_ec2.sh --check    # 값을 받아 확인만 하고 띄우지 않는다
#          SPHINX_PUBLIC_HOST=… ./scripts/deploy_ec2.sh --cert   # 인증서 첫 발급
#
# EC2 인스턴스 역할에 해당 파라미터의 ssm:GetParameter + kms:Decrypt 가 있어야 한다.
# 자세한 것은 docs/deployment.md.
#
# ── 왜 세 개의 compose 파일인가 ──────────────────────────────────────────────
#
#   docker-compose.data.yml   mysql          프로젝트 sphinx-data — 상시 유지
#   docker-compose.edge.yml   web·certbot    프로젝트 sphinx-edge — 상시 유지
#   docker-compose.yml        ai-service·server  프로젝트 sphinx-blue/sphinx-green
#                             — 배포마다 번갈아 뜨고 옛 색은 이 스크립트가 내린다
#
# 예전엔 이 다섯 서비스가 프로젝트 하나(`sphinx`)였고, 배포마다
# `docker compose up -d --build --force-recreate` 가 전부 같이 재생성됐다(이슈
# #240 이 이 플래그를 넣은 이유는 아래 함수 근처에 남겨 뒀다). 그런데 `web`·`mysql`
# 은 리포 트리를 bind mount 하지 않아 재생성될 이유가 없었는데도 매번 같이
# 내려갔다 올라와서, 그 사이 사이트 전체(80/443)가 끊겼다. 지금은 `web`(유일한
# 외부 진입점)이 배포와 무관하게 계속 떠 있고, 새 색이 healthy 해진 뒤에야
# `docker exec` 로 nginx 업스트림을 바꾸고 `nginx -s reload`(무중단)한다.

set -euo pipefail

# 리전은 서울로 고정한다. 기획서 416·422행이 "국내 처리와 온프레미스 배포 옵션" ·
# "개인신용정보 처리는 국내 시스템으로 제한"을 논지로 쓰고 있어서, 데모를 us-east-1 에
# 올리면 우리 문서와 어긋난다. 리전 선택 비용은 0 이고 심사에서 물었을 때 답이 갈린다.
AWS_REGION="${AWS_REGION:-ap-northeast-2}"
SSM_PREFIX="${SSM_PREFIX:-/sphinx/prod}"

# 배포가 최종적으로 자리 잡는 고정 경로. `rm -rf "$CANONICAL" && mv "$REPO_ROOT" "$CANONICAL"`
# 을 맨 끝에서 한 번만 한다 — 아래 색 전환이 다 끝나고 옛 색을 내린 **다음**이라 안전하다.
CANONICAL=/opt/sphinx

CHECK_ONLY=0
CERT_ONLY=0
case "${1:-}" in
  --check) CHECK_ONLY=1 ;;
  # ── ❗왜 인증서 발급이 여기 있나 ─────────────────────────────────────────
  #
  # `docker compose run --rm certbot …` 을 손으로 치면 **안 된다.** compose 는 명령이
  # 무엇이든 파일 전체를 먼저 해석하고, 이 파일의 비밀들은 `${VAR:?}` 라 값이 없으면
  # 거기서 죽는다. 값을 가진 것은 이 스크립트뿐이므로(SSM 에서 받는다) 발급도 여기서 한다.
  # `#173` 이후 자격증명 확인을 여기서 하기로 한 것과 같은 이유다.
  --cert)  CERT_ONLY=1 ;;
esac

# 아래 `cd` 는 --check 를 지난 뒤에 온다. 명부는 --check 에서도 읽으므로 여기서 절대경로를 잡는다.
#
# ❗**이 값이 `$CANONICAL` 과 같은지 다른지가 "이번이 새 트리 배포인지"를 가른다.**
# CI(`.github/workflows/deploy.yml`)는 S3 스냅샷을 `mktemp -d -p /opt` 로 만든 새 디렉토리에
# 풀고 **그 안에서** 이 스크립트를 부르므로 `REPO_ROOT` 는 `/opt/tmp.XXXXXXXXXX` 같은 값이다
# (아직 `$CANONICAL` 로 옮기지 않았다 — 그게 이 스크립트가 마지막에 할 일이다). 반대로
# 오퍼레이터가 `--cert` 나 유지보수 목적으로 이미 배포된 `/opt/sphinx` 안에서 손으로 부르면
# 둘이 같다 — 그때는 트리를 옮길 대상이 없으므로 마지막 swap 을 건너뛴다.
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

# ── DB 비밀번호 (MySQL) ──────────────────────────────────────────────────────
#
# **mysql 컨테이너와 server(blue·green 둘 다)가 같은 값을 받는다** — 여기서 한 번 읽어
# compose 가 넘기므로 갈릴 수가 없다. internal-token 과 같은 구조다.
#
# 갈리면 증상이 고약하다: mysql 은 멀쩡히 healthy 로 뜨고 server 만
# `Access denied for user 'sphinx'` 로 죽는데, 그건 배포 로그 깊은 곳에만 있다.
#
# ❗**이 값은 볼륨과 묶여 있다.** MySQL 은 계정을 데이터 디렉토리 **첫 초기화 때** 만들고
# 그 뒤로는 MYSQL_PASSWORD 를 쳐다보지 않는다. 즉 SSM 값을 나중에 바꾸면 mysql 은 옛
# 비밀번호를 계속 쓰고 server 만 새 값으로 붙으러 가서 **Access denied 로 기동을 못 한다.**
# 바꾸려면 DB 안에서 같이 바꿔야 한다(볼륨을 지우면 기록이 다 날아간다):
#
#   docker compose -f docker-compose.data.yml -p sphinx-data exec mysql mysql -uroot -p -e \
#     "ALTER USER 'sphinx'@'%' IDENTIFIED BY '<새 값>'"
export SPHINX_DB_PASSWORD; SPHINX_DB_PASSWORD=$(get db-password)

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
echo "  SPHINX_DB_PASSWORD    ${#SPHINX_DB_PASSWORD}자"
# id 는 값이 아니라 명부다(레포에 있다). 길이가 아니라 **그대로** 찍는 이유가 그것이고,
# 데모 중 "이 계정으로 왜 안 되지" 를 이 한 줄로 가른다.
echo "  SPHINX_API_USERS      $SPHINX_API_USERS"

if [ "$CHECK_ONLY" = 1 ]; then
  echo "--check 라 여기서 멈춘다."
  exit 0
fi

cd "$REPO_ROOT"

# edge 프로젝트(web·certbot) 호출을 짧게 쓰기 위한 함수. 파일 경로가 항상 절대경로라
# 어디서 부르든(REPO_ROOT 든 CANONICAL 이든) 같은 프로젝트를 가리킨다.
compose_edge() { docker compose -f "$REPO_ROOT/docker-compose.edge.yml" -p sphinx-edge "$@"; }
compose_data() { docker compose -f "$REPO_ROOT/docker-compose.data.yml" -p sphinx-data "$@"; }
# app 스택(ai-service·server)은 색깔마다 별도 compose 프로젝트다. `STACK` 은 그 색깔의
# server 가 `edge` 네트워크에 등록할 별명(`server-$color`)을 정하는 데 쓴다
# (docker-compose.yml 의 `aliases:` 참조) — blue/green 이 같은 별명을 쓰면 Docker DNS 가
# 라운드로빈해서 요청이 옛/새 버전에 섞인다.
compose_app() { local color="$1"; shift; STACK="$color" docker compose -f "$REPO_ROOT/docker-compose.yml" -p "sphinx-$color" "$@"; }

# ── --cert : Let's Encrypt 첫 발급 (한 번) ──────────────────────────────────
#
# ❗**배포 때마다 자동으로 하지 않는다.** Let's Encrypt 는 실패에도 rate limit(같은 도메인
# 1시간 5회)을 매긴다. 배포마다 시도하면 DNS 나 :80 이 잠깐 어긋난 날 **한도를 태워서
# 정작 필요한 순간에 못 받는다.** 갱신은 그 위험이 없어서(이미 받은 인증서가 있고 만료
# 30일 전에만 움직인다) certbot 컨테이너가 12시간마다 알아서 돌린다.
#
# ❗**`--no-deps` 가 예전엔 필수였다 — 지금은 필요 없다.** `web` 이 `server` 를
# `depends_on` 하던 시절엔 그것 없이 재생성하면 `server`(다른 이유로 아직 안 건드릴
# 스택)까지 딸려 재생성됐다(결정 10.58 · 이슈 #262). `web` 은 이제 별도 프로젝트
# (`sphinx-edge`)에 있고 `server` 를 참조하지 않으므로 그 사고 경로 자체가 없다.
if [ "$CERT_ONLY" = 1 ]; then
  : "${SPHINX_PUBLIC_HOST:?--cert 에는 도메인이 필요하다: SPHINX_PUBLIC_HOST=… $0 --cert}"

  # web 이 :80 으로 챌린지를 내보내야 발급이 된다. 안 떠 있으면 먼저 띄운다 —
  # `--cert` 를 배포 직후가 아니라 나중에 부르는 경우가 있다.
  compose_edge up -d --build web

  echo "인증서 발급 — $SPHINX_PUBLIC_HOST"
  # ❗`--entrypoint certbot` 이 있어야 한다. **`docker compose run` 은 `command` 만 덮고
  # `entrypoint` 는 안 덮는다** — 이 서비스의 entrypoint 는 갱신 루프를 돌리려고
  # `/bin/sh` 로 잡혀 있어서, 빼면 컨테이너가 받는 argv 가
  # `/bin/sh certonly --webroot …` 가 되고 sh 가 `certonly` 를 스크립트 파일 이름으로
  # 읽는다(`certonly: No such file or directory` · exit 127). certbot 이 아예 안 불리는데
  # 실패 모양은 "certbot 이 뭔가 실패했다" 로 읽혀 rate limit 을 의심하며 재시도하게 된다
  # (PR #221 리뷰, 정세현 실측). 갱신 루프(`command` 쪽)는 `sh -c '… certbot renew …'` 라 멀쩡하다.
  #
  # LETSENCRYPT_EMAIL 이 비면 등록 없이 받는다. 만료 알림을 못 받는다는 뜻이라
  # 대회가 끝나고도 쓸 도메인이면 채우는 편이 낫다.
  compose_edge run --rm --entrypoint certbot certbot certonly --webroot -w /var/www/certbot \
    -d "$SPHINX_PUBLIC_HOST" --agree-tos -n \
    ${LETSENCRYPT_EMAIL:+--email "$LETSENCRYPT_EMAIL"} \
    ${LETSENCRYPT_EMAIL:---register-unsafely-without-email}

  # 20-tls.sh 는 **기동 때** 인증서 유무를 본다. 재기동해야 443 이 선다.
  echo "web 재기동 — 443 을 세운다"
  compose_edge up -d --force-recreate web
  compose_edge logs --tail 20 web | grep -E "TLS|모드" || true
  exit 0
fi

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

# ❗**만든 다음에 켠다.** `SyntheticSessionLoader` 는 켜졌는데 파일이 없으면 예외를 던져
# server 가 기동을 못 한다 — 그래서 compose 기본값은 `false` 이고, 파일을 방금 만든
# 여기서만 켠다(`SPHINX_API_USERS` 와 같은 모양: 근거를 가진 쪽이 값을 넘긴다).
# 안 켜면 산출물이 있어도 서버가 안 읽어 대시보드가 전부 "가려짐" 으로 뜬다(이슈 #179).
export SPHINX_DEMO_SYNTHETIC_SESSIONS=true

# ── 디스크 — 박스 위에서 빌드하므로 여기가 차면 배포가 아니라 **런타임이** 먼저 상한다 ──
#
# 루트 30GB(infra/locals.tf)인데 배포마다 `--build` 가 돌아 이미지와 빌드 캐시가 쌓인다.
# blue/green 겹침 구간엔 트리도 잠깐 두 벌(옛 색의 `$CANONICAL` · 새 색의 `$REPO_ROOT`)
# 이지만, `data/`·`contracts/` 는 수백 KB 대라(결정로그 7.13) 이 배로 늘어도 무시할 양이다
# — 디스크를 실제로 압박하는 것은 이미지 레이어·빌드 캐시다.
echo "디스크:"
df -h / | tail -1
docker system df 2>/dev/null | sed 's/^/  /' || true

# `df -P` 는 POSIX 다. `df --output=pcent` 는 GNU 전용이라 busybox·macOS 에서 **옵션 에러로
# 죽는다** — `set -euo pipefail` 아래서는 그게 스크립트 전체를 끝낸다(실측). 배포 대상은
# 리눅스지만, 손으로 돌려 보는 자리가 늘 리눅스라는 보장이 없어 이식되는 쪽을 쓴다.
avail_pct=$(df -P / 2>/dev/null | awk 'NR==2 { gsub(/%/,"",$5); print $5 }' || true)
if [ -n "${avail_pct:-}" ] && [ "$avail_pct" -ge 90 ]; then
  echo "::warning::루트 사용률 ${avail_pct}% — 90% 를 넘었다. 아래 정리로도 안 내려가면 #240 을 본다." >&2
fi

# ❗**비정상 컨테이너가 있으면 지우기 전에 로그를 남긴다 (이슈 #240 의 교훈)** — 지금은
# data·edge 두 프로젝트에만 적용한다. app 스택(blue/green)은 배포마다 새 프로젝트로 뜨고
# 실패하면 스스로 내리므로(아래) "지난 배포가 남긴 상한 컨테이너" 라는 문제 자체가 없다.
log_unhealthy() {
  local project="$1" cid info name state health
  for cid in $(docker ps -aq --filter "label=com.docker.compose.project=$project" 2>/dev/null); do
    info=$(docker inspect --format \
      '{{.Name}} {{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' \
      "$cid" 2>/dev/null) || continue
    name=${info%% *};  name=${name#/}
    rest=${info#* };   state=${rest%% *};  health=${rest##* }
    if [ "$health" = unhealthy ] || [ "$state" = restarting ]; then
      echo "::warning::$project 의 $name 이 비정상이다($state/$health) — 마지막 로그 40줄"
      docker logs --tail 40 "$cid" 2>&1 | sed 's/^/    /' || true
    fi
  done
}
log_unhealthy sphinx-data
log_unhealthy sphinx-edge

# ── 옛 단일 프로젝트(`sphinx`) 철거 — 스택을 셋으로 가르기 전의 잔재다 ────────────
#
# 머리말이 말한 "예전엔 다섯 서비스가 프로젝트 하나(`sphinx`)였다" 의 뒤처리다. 새 배치의
# 파일들은 그 시절 **볼륨 이름**을 그대로 물려받게 써 뒀지만(`name: sphinx_letsencrypt` 등),
# 그 시절 **컨테이너**가 박스에 살아 있는 경우는 아무도 안 내렸다. 그러면 새 스택이 못 뜬다
# — 겹치는 것이 둘이다:
#
#   ① 옛 `sphinx-web-1` 이 0.0.0.0:80·443 을 쥐고 있다. edge 의 `web` 이 같은 포트를
#      요구하므로 `Bind for 0.0.0.0:443 failed: port is already allocated` 로 죽는다.
#      PR #513 머지 직후 alpha 배포가 정확히 이걸로 실패했다(런 34035273642).
#   ② 옛 `sphinx-mysql-1` 이 `sphinx_mysql-data` 를 물고 있다. data 스택의 mysql 이
#      **같은 볼륨**을 마운트하므로 mysqld 둘이 한 데이터 디렉토리를 잡는다.
#
# ❗**컨테이너만 지운다 — 볼륨은 손대지 않는다.** 인증서(`sphinx_letsencrypt`)와
# DB(`sphinx_mysql-data`)가 그 안에 있고, 새 스택이 이름으로 그대로 물려받는 것이 이
# 이관의 전제다. `docker rm` 은 named volume 을 지우지 않는다 — 여기에 `down -v` 를 쓰면
# 인증서와 DB 가 같이 날아간다(재발급은 Let's Encrypt 발급 한도가 걸린다).
# ❗업로드 원본(`sphinx_uploads`)도 같은 전제에 얹혀 있다(이슈 #521). 그쪽은 **복구 수단이
# 아예 없다** — 인증서는 재발급되고 DB 는 스냅샷이 있지만, 사람이 올린 문서는 아무도 다시
# 만들어 주지 않는다. `external: true` 라 `down -v` 로도 지워지지 않지만, `docker volume rm`
# 은 막지 못한다.
#
# 라벨 필터는 **정확히 일치**라 `sphinx-edge`·`sphinx-data`·`sphinx-blue` 는 안 걸린다.
# 이관이 끝난 박스에서는 걸리는 컨테이너가 0개라 이 블록은 아무 일도 안 한다 — 그게 정상
# 상태다. 지우려면 alpha·prod **두 박스가 모두** 새 배치로 한 번씩 돈 것을 확인하고 지운다.
#
# 이 한 번의 배포에만 **짧은 단절이 있다.** 80/443 을 쥔 쪽이 바뀌는 순간이라 피할 수 없다
# — 옛 web 을 내려야 새 web 이 그 포트를 잡는다. 그 창을 줄이려고 edge 이미지를 **먼저**
# 빌드해 둔다(빌드는 포트를 안 쓴다). 두 번째 배포부터는 여기 걸리는 것이 없어 평소의
# 무중단 경로 그대로다.
legacy_cids=$(docker ps -aq --filter "label=com.docker.compose.project=sphinx" 2>/dev/null || true)
if [ -n "$legacy_cids" ]; then
  echo "::warning::옛 단일 프로젝트(sphinx) 컨테이너가 있다 — 내리고 새 배치로 이관한다. 이 배포에만 짧은 단절이 있다."
  docker ps -a --filter "label=com.docker.compose.project=sphinx" \
    --format '  {{.Names}}  {{.Status}}  {{.Ports}}' || true
  echo "edge 이미지 선빌드 — 단절 창을 빌드 시간만큼 줄인다"
  compose_edge build
  echo "옛 컨테이너 제거 (볼륨은 그대로 둔다)"
  # shellcheck disable=SC2086  # 공백으로 갈린 ID 목록이라 쪼개지는 것이 맞다
  docker rm -f $legacy_cids
  # 컨테이너가 없으면 옛 네트워크는 빈 껍데기다. 아직 붙은 것이 있으면 실패하는데, 그때는
  # 지워지지 않는 쪽이 맞으므로 무시한다.
  docker network rm sphinx_default >/dev/null 2>&1 || true

  # ❗**data 스택의 mysql 을 한 번 깨운다.** 실패한 이관 배포가 이미 이 컨테이너를 만들어
  # 놨다면, 옛 mysql 이 볼륨을 쥐고 있는 동안 `[InnoDB] Unable to lock ./ibdata1 error: 11`
  # 로 재시작 루프를 돌고 있다(alpha 실측: 2시간에 68회). 락은 방금 풀렸지만 Docker 의
  # 재시작 백오프가 최대 1분까지 커진 뒤라, 그냥 두면 **새 색의 server 가 먼저 떠서 Flyway
  # 가 DB 를 못 찾고 죽는다** — 이관이 포트에서 한 번, DB 에서 또 한 번 실패하는 모양이 된다.
  # 아직 그 컨테이너가 없는 박스(=처음부터 새 배치)에서는 실패하고, 그때는 바로 아래
  # `compose_data up -d` 가 만든다.
  compose_data restart mysql >/dev/null 2>&1 || true
fi

# ── 업로드 원본 볼륨 ─────────────────────────────────────────────────────────
#
# 업로드된 상품문서가 사는 곳(F-EXT-001 · 이슈 #521). **여기서 만든다** — 앱 스택은
# `external: true` 로 참조만 하고, 없으면 `external volume "sphinx_uploads" not found`
# 로 기동이 즉시 실패한다. 그래서 반드시 `compose_app` 보다 앞이어야 한다.
#
# `docker-compose.yml` 의 volumes 절에 적어 두는 것으로는 안 된다 — **참조하는 서비스가
# 없는 top-level 볼륨은 Compose 가 만들지 않는다**(실측). data 스택의 mysql 이 이 볼륨을
# 쓸 이유도 없으므로 거기 얹어 만들 자리가 없다.
#
# 멱등이다. 이미 있으면 아무 일도 안 하고, 그 안의 파일도 건드리지 않는다.
docker volume create sphinx_uploads >/dev/null

# ── data·edge 스택 — 상시 유지, 설정이 안 바뀌면 compose 가 아무것도 안 건드린다 ──
echo "data 스택(mysql) 기동"
compose_data up -d
echo "edge 스택(web·certbot) 기동"
compose_edge up -d --build

# ── 지금 라이브인 색 판정 ─────────────────────────────────────────────────────
#
# 별도 상태 파일을 안 둔다 — nginx 자신이 물고 있는 업스트림 설정이 유일한 근거다
# (web/docker-entrypoint.d/05-upstream-seed.sh 참조). 최초 배포라 그 값이 가리키는 색의 컨테이너가
# 아직 없으면(이미지 기본값 = blue), 전환 없이 그 색으로 그냥 띄운다.
edge_web_cid() { compose_edge ps -q web; }

current_color() {
  local cid upstream
  cid=$(edge_web_cid)
  upstream=$(docker exec "$cid" cat /etc/nginx/upstream/active.conf 2>/dev/null || true)
  case "$upstream" in
    *server-green*) echo green ;;
    *)              echo blue ;;   # 이미지 기본값과 같다(web/docker-entrypoint.d/05-upstream-seed.sh)
  esac
}

CUR="$(current_color)"
if [ -n "$(compose_app "$CUR" ps -q server 2>/dev/null || true)" ]; then
  OLDCOLOR="$CUR"
  case "$CUR" in blue) NEWCOLOR=green ;; *) NEWCOLOR=blue ;; esac
  echo "현재 라이브: $OLDCOLOR → 이번엔 $NEWCOLOR 로 띄운다"
else
  OLDCOLOR=""
  NEWCOLOR="$CUR"
  echo "라이브 스택이 없다(최초 배포 또는 이전 실패의 뒷정리 상태) — $NEWCOLOR 로 띄운다"
fi

echo "$NEWCOLOR 스택(ai-service·server) 기동"
compose_app "$NEWCOLOR" up -d --build

# ── healthy 대기 ─────────────────────────────────────────────────────────────
#
# `docker compose up` 의 기본 동작(`depends_on: condition: service_healthy`)이 여기선
# 안 통한다 — `server` 는 `mysql`(다른 프로젝트)에 그 조건을 못 건다. 그래서 이 스크립트가
# 직접 폴링한다. 타임아웃은 넉넉히 잡는다 — Spring Boot 부팅 + healthcheck start_period(40s).
wait_healthy() {
  local cid="$1" label="$2" timeout="${3:-300}" waited=0 status
  while :; do
    status=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$cid" 2>/dev/null || echo gone)
    case "$status" in
      healthy) echo "  $label healthy (${waited}s)"; return 0 ;;
      gone)    echo "::error::$label 컨테이너가 사라졌다" >&2; return 1 ;;
    esac
    if [ "$waited" -ge "$timeout" ]; then
      echo "::error::$label 이 ${timeout}s 안에 healthy 가 안 됐다(마지막 상태: $status)" >&2
      return 1
    fi
    sleep 5; waited=$((waited + 5))
  done
}

new_server_cid=$(compose_app "$NEWCOLOR" ps -q server)
if ! wait_healthy "$new_server_cid" "server($NEWCOLOR)" 300; then
  echo "::error::$NEWCOLOR 가 안 떴다 — 로그를 남기고 내린다." >&2
  [ -n "$OLDCOLOR" ] && echo "$OLDCOLOR 는 손대지 않았다 — 배포 실패가 곧 무중단 롤백이다." >&2
  echo "── docker compose ps ($NEWCOLOR) ──"
  compose_app "$NEWCOLOR" ps -a || true
  echo "── docker compose logs ($NEWCOLOR, 서비스별 100줄) ──"
  compose_app "$NEWCOLOR" logs --tail 100 --no-color 2>&1 | sed 's/^/    /' || true
  compose_app "$NEWCOLOR" down || true
  exit 1
fi

# ── 컷오버 — nginx 업스트림을 바꾸고 무중단 reload ──────────────────────────
#
# `nginx -t` 로 먼저 검증한다 — 이 파일은 우리가 직접 쓰는 것이라 문법이 깨질 일은 거의
# 없지만, 검증 없이 `-s reload` 만 하면 **문법이 깨진 순간 reload 가 조용히 무시되고 옛
# 워커가 계속 도는데 로그로만 알 수 있다.** `&&` 로 묶어 실패하면 여기서 바로 드러나게 한다.
echo "컷오버 — nginx 업스트림을 server-$NEWCOLOR 로"
web_cid=$(edge_web_cid)
# ❗값에 따옴표를 안 쓴다. nginx 문자열 값은 공백·세미콜론이 없으면 따옴표 없이도
# 유효하고, 따옴표를 넣으면 이 bash 문자열 → `sh -c` 문자열 → printf 형식 문자열의
# 세 겹 이스케이프를 거치며 `\"` 가 POSIX printf 에 정의되지 않은 이스케이프가 된다
# (구현마다 다르게 처리될 수 있다 — 실측하지 않고 넘어가지 않는다).
docker exec "$web_cid" sh -c \
  "printf 'set \$sphinx_backend http://server-${NEWCOLOR}:8000;\n' > /etc/nginx/upstream/active.conf && nginx -t && nginx -s reload"

# 짧은 드레인 대기 — cutover 순간의 in-flight 요청이 옛 색으로 끝날 시간을 준다.
# reload 자체는 즉시 무중단이지만, 옛 워커가 붙잡고 있던 연결이 실제로 끝나는 데는
# 약간의 시간이 걸린다. 그 창을 지나고서야 옛 색을 내린다.
sleep 8

if [ -n "$OLDCOLOR" ]; then
  echo "$OLDCOLOR 스택 내리기"
  compose_app "$OLDCOLOR" down
fi

# ── 트리 갈아끼우기 — 이제는 안전하다 ────────────────────────────────────────
#
# ❗**이슈 #240 의 원인은 "떠 있는 컨테이너가 물고 있는 디렉토리를 rm -rf 하는 것"이었다,
# rename 자체가 아니다.** 예전엔 `deploy.yml` 이 스크립트를 부르기도 **전에** 스왑을
# 해서, 그 순간 아직 살아 있던 컨테이너(다음 배포까지 재생성 안 되는 서비스들)가 지워진
# inode 를 물게 됐다. 지금은 옛 색($OLDCOLOR)을 방금 내렸고 새 색($NEWCOLOR)은 여전히
# `$REPO_ROOT` 를 bind mount 한 채 살아 있다 — `mv` 는 같은 파일시스템에서 rename 이라
# inode 가 안 바뀌므로, 이 mv 는 새 색의 mount 에 아무 영향이 없다. `$REPO_ROOT` 가 이미
# `$CANONICAL` 이면(오퍼레이터가 이미 배포된 트리에서 손으로 다시 돌린 경우) 옮길 게
# 없으므로 건너뛴다.
if [ "$REPO_ROOT" != "$CANONICAL" ]; then
  echo "트리 갈아끼우기 — $REPO_ROOT → $CANONICAL"
  rm -rf "$CANONICAL"
  mv "$REPO_ROOT" "$CANONICAL"
  REPO_ROOT="$CANONICAL"
fi

# ── 정리 — 디스크 참을 막는 쪽 ──────────────────────────────────────────────
#
# dangling 이미지는 방금 빌드가 밀어낸 옛 레이어라 참조가 없고, 빌드 캐시는 7일치만
# 남긴다(다음 빌드가 조금 느려지는 대신 30GB 가 안 찬다). 태그 붙은 이미지는 안 지운다 —
# 롤백이 그걸 쓴다.
echo "정리:"
docker image prune -f 2>/dev/null | tail -1 || true
docker builder prune -f --filter 'until=168h' 2>/dev/null | tail -1 || true
df -h / | tail -1

echo
echo "상태:"
compose_data ps
compose_edge ps
compose_app "$NEWCOLOR" ps
echo
echo "확인:"
# `#162` 로 nginx 가 사이트 전체에 auth_basic 을 건 뒤로 자격증명 없는 GET / 는 401 이다.
# 예전 문구(`curl -fsS`)는 -f 때문에 비영점으로 죽었고, 붙여 넣은 사람이 **정상 동작을
# 배포 실패로 읽는다** — 그 시점이 리허설 직전이다.
#
# -u 로 자격증명을 넣는 쪽은 안 쓴다. 셸 히스토리와 ps 에 남아 `#162` 가 값을 파일·환경변수
# 로만 다루기로 한 것과 어긋난다. 그리고 상태코드를 그냥 찍는 편이 **더 많이 잡는다** —
# 401 이 나오는 것 자체가 성공 신호라, 200 이 나오면 auth_basic 이 빠진 것이다.
#
# ❗**기대값이 모드마다 다르다.** 개방 모드(alpha)는 `auth_basic` 이 꺼져 있어 **200 이
# 정상**이고, 잠금은 401 이 정상이다. 한쪽 문구만 박아 두면 붙여 넣은 사람이 정상을
# 이상으로 읽는다 — `#170` 과 같은 모양이라, `deploy.yml` 의 노출 확인이 `EXPECT` 를
# 환경에서 받는 것과 같은 이유로 여기서도 모드를 보고 찍는다.
if [ "${SPHINX_DEMO_OPEN:-0}" = "1" ]; then
  echo "  curl -sS -o /dev/null -w '%{http_code}\n' http://localhost/   # 개방 모드라 200 이 정상이다(401 이면 잠긴 것)"
else
  echo "  curl -sS -o /dev/null -w '%{http_code}\n' http://localhost/   # 401 이 정상이다(인증이 걸렸다는 뜻)"
fi
echo "  docker compose -f docker-compose.yml -p sphinx-$NEWCOLOR logs -f server   # 기동 로그"
echo

# ❗**자격증명 확인은 안내하지 않고 여기서 한다** (PR #173 리뷰, 오준서).
#
# 위 export 는 이 스크립트 프로세스와 그 자식(docker compose)에만 산다. 오퍼레이터
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
#
# ❗**개방 모드에서는 이 검사가 아무것도 재지 않는다** — `auth_basic` 이 꺼져 있어 자격증명이
# 무엇이든 200 이다. 그래도 죽은 검사는 아니다: 개방 모드는 nginx 와 Spring 이 **같은**
# `SPHINX_API_PASSWORD` 를 쓰므로 둘이 갈릴 수가 없고, `prod` 는 잠금이라 거기서 산다.
# (다음 사람이 "왜 항상 통과하지" 를 파지 않도록 적어 둔다 — PR #221 리뷰, 정세현.)
auth_code=$(printf 'user = "%s:%s"\n' "$auth_user" "$auth_pass" |
            curl -sS -K - -o /dev/null -w '%{http_code}' http://localhost/ || true)
auth_note=""
[ "${SPHINX_DEMO_OPEN:-0}" = "1" ] && auth_note=" (개방 모드라 auth_basic 이 꺼져 있다 — 이 검사는 늘 200 이다)"
case "$auth_code" in
  200)      echo "자격증명 확인: 200 — 통과${auth_note}" ;;
  401)      echo "자격증명 확인: 401 — SSM 값과 nginx htpasswd 가 다르다. 둘 다 SSM 에서 나오는지 본다" ;;
  ''|000)   echo "자격증명 확인: 요청 자체가 안 갔다 — nginx 가 떴는지 본다 (docker compose -f docker-compose.edge.yml -p sphinx-edge ps)" ;;
  *)        echo "자격증명 확인: $auth_code — 예상 밖이다. server 로그를 본다" ;;
esac
echo
echo "❗보안그룹 인바운드는 80·443 만 연다(443 은 infra/network.tf). 8000·8100 을 열면 #41 의 1·3항(permitAll · ai-service 무인증)이 되살아난다."
