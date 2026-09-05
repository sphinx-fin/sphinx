# 배포 — AWS EC2 + docker compose

소유: 오준서 (인프라 R). 근거: 이슈 [#41](../../../issues/41)(배포 전 보안) ·
[#37](../../../issues/37)(`data/` 전제) · `docs/decision-log.md` 10.3.

---

## 1. 무엇이 어디에 뜨는가

```
                인터넷
                   │  :80 만
                   ▼
        ┌──────────────────────┐
        │  web  (nginx + dist) │   edge 네트워크
        └──────────┬───────────┘
                   │  /api → server:8000
        ┌──────────▼───────────┐
        │  server (Spring)     │   edge + internal
        └──────────┬───────────┘
                   │  http://ai-service:8100
        ┌──────────▼───────────┐
        │  ai-service (FastAPI)│   internal 만
        └──────────────────────┘
```

**외부에 열리는 것은 `web:80` 하나뿐이다.** `server` 와 `ai-service` 는 compose 에
`ports:` 가 없어서 EC2 호스트에서도 인터넷에서도 안 보인다.

`ai-service` 를 안 여는 것이 #41 의 조치 중 **가장 싸고 효과가 크다.** `/internal/*` 이
지금 무인증이라, :8100 이 퍼블릭에 뜨면 PII 마스킹을 건너뛰고 LLM 에 직접 프롬프트를 넣을 수
있다 — CLAUDE.md 가 P3 로 못박은 *"고객 텍스트가 ai-service 로 나가는 유일한 경로"* 가 그
순간 거짓이 된다. 노출을 안 하면 `permitAll` 상태여도 외부에서 못 부른다.

네트워크를 둘로 가른 것은 그 위의 2차 방어다. `web` 컨테이너가 뚫려도 `ai-service` 와 공유하는
네트워크가 없어서 :8100 으로 직접 못 간다.

> `internal` 네트워크에 `internal: true` 를 **붙이지 않았다.** 그 플래그는 컨테이너 간 격리가
> 아니라 아웃바운드 차단이라, 붙이면 `ai-service` 가 LLM API 에 못 나가서 추출·채점·질문생성이
> 전부 죽는다. 원하는 격리는 네트워크 소속만으로 이미 성립한다.

---

## 2. 리전은 `ap-northeast-2`(서울)

기획서 416·422행이 *"국내 처리와 온프레미스 배포 옵션"* · *"개인신용정보 처리는 국내 시스템으로
제한"* 을 논지로 쓴다. 데모를 us-east-1 에 올리면 우리 문서와 어긋나고, 심사에서 물었을 때
답이 갈린다. 리전 선택 비용은 0 이다.

---

## 3. 보안그룹

| 방향 | 포트 | 소스 | 이유 |
|---|---|---|---|
| 인바운드 | 80 | 0.0.0.0/0 (또는 심사 IP) | 화면 |
| 인바운드 | 22 | **내 IP 만** | 배포 접속 |
| 인바운드 | ~~8000~~ | — | 열지 않는다. nginx 가 프록시한다 |
| 인바운드 | ~~8100~~ | — | **절대 열지 않는다** (#41 ③) |
| 아웃바운드 | 443 | 0.0.0.0/0 | LLM API · SSM |

`server:8000` 을 어쩔 수 없이 직접 열어야 하면 IP 제한이나 basic auth 를 먼저 건다. prod
프로파일은 이미 basic auth 를 요구하므로(`SecurityConfig`) 인증 자체는 서 있다.

---

## 4. 비밀은 SSM Parameter Store 에서

`.env` 평문 파일을 EC2 에 두지 않는다. compose 의 `env_file:` 을 쓰면 키가 디스크에 남고 그
인스턴스에 들어올 수 있는 사람 전부가 읽는다. 이미지에 굽지도 않는다 — 레이어는 지워도
히스토리에 남는다.

### 4.1 파라미터 등록 (한 번)

```bash
aws ssm put-parameter --region ap-northeast-2 \
  --name /sphinx/prod/llm-api-key  --type SecureString --value '…'
aws ssm put-parameter --region ap-northeast-2 \
  --name /sphinx/prod/api-user     --type SecureString --value 'sphinx'
aws ssm put-parameter --region ap-northeast-2 \
  --name /sphinx/prod/api-password --type SecureString --value '…'

# `/internal/*` 공유 시크릿 (이슈 #41 3항 · 결정 10.4). **사람이 고르지 않는다** —
# 외울 필요가 없는 값이고, 고르면 짧고 짐작 가능한 값이 된다.
aws ssm put-parameter --region ap-northeast-2 \
  --name /sphinx/prod/internal-token --type SecureString --value "$(openssl rand -hex 32)"

# ── DB 비밀번호 (MySQL). **mysql 컨테이너와 server 가 같은 값을 받는다** ────────
#
# ❗**이 값은 볼륨과 묶인다.** MySQL 은 계정을 데이터 디렉토리 첫 초기화 때 만들고 그 뒤로는
# MYSQL_PASSWORD 를 안 본다. 나중에 SSM 만 바꾸면 mysql 은 옛 값을 계속 쓰고 server 만 새
# 값으로 붙으러 가서 `Access denied` 로 기동을 못 한다. 바꾸려면 DB 안에서 같이 바꾼다:
#   docker compose exec mysql mysql -uroot -p -e "ALTER USER 'sphinx'@'%' IDENTIFIED BY '<새 값>'"
aws ssm put-parameter --region ap-northeast-2 \
  --name /sphinx/prod/db-password --type SecureString --value "$(openssl rand -hex 24)"
```

❗`api-user` 값은 **명부(`server/src/main/resources/demo_accounts.yaml`)의 id 중 하나**여야
한다. `SecurityConfig.prodUsers` 가 아니면 기동을 거부하고(결정 10.5), `deploy_ec2.sh` 도
같은 이유로 먼저 죽는다. 위 예시의 `sphinx` 는 명부에 없다 — `seller-01` 처럼 실제 id 를 넣는다.

**나머지 계정은 SSM 에 넣지 않는다.** nginx htpasswd 에 들어갈 id 목록은 `deploy_ec2.sh` 가
명부에서 뽑아 `SPHINX_API_USERS` 로 넘긴다(이슈 #213). 명부가 근거이고, SSM 에 목록을 또 두면
계정이 두 벌이 되어 갈리는 날 **화면은 열리는데 그 계정만 401** 이 된다. 비밀번호는
전 계정 공통이라 `api-password` 하나로 충분하다.

다섯 값 모두 `scripts/deploy_ec2.sh` 가 읽어 **환경변수로만** 넘긴다. `internal-token` 은
`server` 와 `ai-service` 가 **같은 값**을 받는다 — 다르면 `/internal/*` 이 전부 401 이라
인터뷰 경로가 통째로 죽으므로, 출처를 SSM 하나로 둔다(`api-user`/`api-password` 를 nginx 와
server 가 나눠 쓰는 것과 같은 구조 · #162).

❗**빠뜨리면 배포가 실패한다. 그게 의도다.** 양쪽 코드가 *"토큰이 비면 인증을 끈다"* 로
대칭이라, 이 두 겹이 없으면 **배포는 성공하고 시크릿 방어선만 조용히 꺼진 채로 뜬다.**

두 겹이 막는 입력이 다르다(#212 리뷰).

| 입력 | `${VAR:?}` | `SPHINX_REQUIRE_INTERNAL_AUTH=1` |
|---|---|---|
| 미설정 | 거부 | — |
| 빈 문자열 | 거부 | — |
| **공백만** (`" "`) | **통과** | 거부 (`.strip()` 뒤 빈 값 → 기동 중단) |

`:?` 가 빈 문자열도 막는다는 것은 실측이다(`TT= docker compose config` → 거부, `TT=" "` →
통과). 즉 `REQUIRE=1` 은 보험이 아니라 **`:?` 가 못 보는 자리**를 맡는다 — *"compose 가 이미
막는데 왜 둘이냐"* 로 하나를 떼면 공백 값이 그대로 무인증으로 뜬다.

### 4.2 인스턴스 역할

EC2 인스턴스 프로파일에 아래가 필요하다. **키를 인스턴스에 복사하지 않는 것이 요점이므로
액세스 키를 EC2 에 두는 방식으로 대체하지 않는다.**

```json
{
  "Version": "2012-10-17",
  "Statement": [
    { "Effect": "Allow",
      "Action": ["ssm:GetParameter"],
      "Resource": "arn:aws:ssm:ap-northeast-2:*:parameter/sphinx/prod/*" },
    { "Effect": "Allow",
      "Action": ["kms:Decrypt"],
      "Resource": "*" }
  ]
}
```

---

## 5. 띄우기

```bash
git clone <repo> && cd sphinx
./scripts/deploy_ec2.sh --check     # 비밀을 받을 수 있는지만 확인
./scripts/deploy_ec2.sh             # 받아서 compose up -d --build
```

스크립트가 값을 **환경변수로만** 넘긴다(파일로 안 떨어진다). 값 자체는 로그에 안 찍고 길이만
보여준다.

`docker compose up` 을 직접 부를 때도 세 값이 없으면 **기동을 거부한다** — compose 의
`${VAR:?}` 문법이다. 키 없이 떠서 데모 중에 LLM 호출만 실패하는 상태를 만들지 않기 위한 것이다.

---

## 6. `data/` 와 `contracts/` 는 볼륨이다 — 이미지에 굽지 않는다

`git clone` 하면 둘 다 통째로 온다(#30 이후 `documents/` 까지 추적). 그래서 bind mount 가
그대로 성립한다.

| 마운트 | 서비스 | 없으면 |
|---|---|---|
| `./data:/data:ro` | ai-service | 오해 라이브러리를 못 읽어 **로딩 시점에 죽는다** — 드러난다 |
| `./contracts:/contracts:ro` | ai-service | ❗**조용히 no-op** — 아래 |
| `./data/timeseries:/data/timeseries:ro` | server | 시뮬레이터 계산 불가 (경고 로그) |

`timeseries/` 를 이미지에 넣지 않는 이유는 18,089줄을 한 벌 더 두면 `data/timeseries/VERSION`
의 sha256 으로 고정한 원본과 조용히 갈라지고, 시뮬레이터 출력이 달라진 원인이 코드인지
데이터인지 구분할 수단이 없어지기 때문이다(P2).

### ❗`contracts/` 마운트를 빠뜨리면 조용하다

`ai-service/app/templates.py` 의 `assert_matches_contract()` 가
`contracts/samples/parsed_*.json` 을 읽어 상품유형 템플릿과 대조하는데, 파일이 없으면
`contract_item_ids()` 가 `None` 을 돌려주고 **예외도 로그도 없이 대조가 통째로 no-op** 이 된다
(#37 코멘트 ②). 템플릿이 계약과 어긋나 있어도 컨테이너에서는 통과한다.

마운트 지점이 `/contracts` 인 것은 우연이 아니다 — 앱이 `/app/app/` 에 있어서
`Path(__file__).resolve().parents[2]` 가 `/` 로 떨어진다.

---

## 7. 아직 안 닫힌 것 (9/3 리허설 전)

이 문서와 compose 는 #41 의 **인프라 몫**을 닫는다. 나머지는 소유자가 따로 있다.

| 항목 | 소유 | 상태 |
|---|---|---|
| `ai-service` 미노출 · SG · LLM 키 · 리전 | 오준서 | **이 PR 로 닫힘** |
| H2 콘솔 · `ddl-auto` · `permitAll` | 강희진 | `application-prod.yml` · `SecurityConfig` 로 **닫힘** |
| `/internal/*` 공유 시크릿 헤더 | 윤지석 | 코드 `#198`·`#200`, 배포 주입 `#212` 로 **닫힘** |
| `permitAll` 제거 시점의 `AccessPolicy` | 정세현 | F-CMN-002 일정과 맞춰야 함 |

`ai-service` 의 시크릿 헤더가 없어도 이 compose 에서는 외부에서 못 부른다. 다만 **compose
네트워크 설정 실수 한 번으로 전체가 열리는 구조**라 시크릿 방어선이 여전히 필요하다 —
그 실수 위에 남는 것이 그것뿐이다.

---

## 8. 확인

```bash
docker compose ps                       # 세 서비스가 healthy 인가

# 인증이 걸려 있는가. **401 이 정상이다** — 사이트 전체에 auth_basic 이 걸려 있다(#162).
# 200 이 나오면 auth_basic 이 빠진 것이라, 이 한 줄이 #41 1항의 회귀도 같이 잡는다.
# `-f` 를 쓰지 않는다 — 401 에 비영점으로 죽어서 정상 동작을 배포 실패로 읽게 된다(#170).
curl -sS -o /dev/null -w '%{http_code}\n' http://localhost/

# 자격증명이 통하는가. **값을 셸 변수로 먼저 채워야 한다** — `deploy_ec2.sh` 의 export 는
# 그 스크립트 프로세스에만 살고 이 셸로 안 내려온다. 변수 이름도 스크립트와 같게 쓴다.
# `-u` 대신 stdin(`-K -`)으로 넘기는 이유는 `-u` 가 argv 라 `ps` 에 보이기 때문이다.
SSM_PREFIX="${SSM_PREFIX:-/sphinx/prod}"          # deploy_ec2.sh 와 같은 기본값
SPHINX_API_USER=$(aws ssm get-parameter --name "$SSM_PREFIX/api-user" \
  --with-decryption --query Parameter.Value --output text)
SPHINX_API_PASSWORD=$(aws ssm get-parameter --name "$SSM_PREFIX/api-password" \
  --with-decryption --query Parameter.Value --output text)

# curl config 의 따옴표 안에서는 `\` 와 `"` 가 이스케이프 문자다. 그대로 넣으면 파싱이
# 끊겨 **값이 맞는데도 401** 이 나고, 비밀번호가 틀린 것으로 읽힌다. 백슬래시를 먼저 친다.
esc_user=${SPHINX_API_USER//\\/\\\\};     esc_user=${esc_user//\"/\\\"}
esc_pass=${SPHINX_API_PASSWORD//\\/\\\\}; esc_pass=${esc_pass//\"/\\\"}

printf 'user = "%s:%s"\n' "$esc_user" "$esc_pass" |
  curl -sS -K - -o /dev/null -w '%{http_code}\n' http://localhost/api/products   # 200

# 역할이 갈리는가 (#213 · ADR-001 시연). 같은 비밀번호로 **계정만 바꿔** 두 번 부른다.
# nginx 401 이 나오면 htpasswd 에 그 id 가 없는 것이고(= 이 확인의 요점), Spring 까지
# 갔는데 막힌 것이라면 403 이다. **401 과 403 을 구별해서 읽는다.**
for u in seller-01 compl-01; do
  printf 'user = "%s:%s"\n' "$u" "$esc_pass" |
    curl -sS -K - -o /dev/null -w "$u %{http_code}\n" http://localhost/api/dashboard/heatmap
done
# seller-01 403 · compl-01 200 이 정상이다. seller-01 이 401 이면 명부가 htpasswd 에 안 들어간 것.

# ❗아래 둘은 **실패해야 정상이다**
curl --max-time 3 http://<EC2 퍼블릭 IP>:8100/healthz   # ai-service 직접 — 막혀야 한다
curl --max-time 3 http://<EC2 퍼블릭 IP>:8000/products  # server 직접 — 막혀야 한다
```

마지막 두 줄을 배포 때마다 실제로 돌린다. 노출은 설정이 한 줄 바뀌면 조용히 되살아나는
종류라, "안 열려 있음"을 확인하는 명령이 있어야 한다.

---

## 9. 도메인과 https (alpha)

```
sphinxfin.duckdns.org    →  54.116.240.212 (alpha EIP)  DuckDNS A 레코드
인증서                    →  Let's Encrypt · HTTP-01 · certbot 컨테이너
```

### 9.1 왜 :80 을 계속 열어 두는가

HTTP-01 챌린지가 `http://<도메인>/.well-known/acme-challenge/…` 를 **:80 으로** 읽는다.
80 을 닫으면 첫 발급도 90일 뒤 갱신도 실패하고, **갱신 실패는 만료되는 날까지 조용하다.**
평상시 브라우저 트래픽은 nginx 가 :80 에서 https 로 튕기므로 평문으로 서비스되지는 않는다.

`web/nginx.conf` 에서 그 리다이렉트가 **챌린지 경로만 비켜 간다.** server 레벨 `if` 는
location 을 고르기 전에 돌아서 그냥 두면 챌린지까지 튕긴다 — 실측으로 잡았고, map 두 개로
갈라 뒀다.

### 9.2 첫 발급 (한 번)

인증서가 없으면 nginx 는 :443 을 **아예 안 세운다**(`20-tls.sh`). `ssl_certificate` 를
무조건 적어 두면 파일이 없는 첫 기동에서 설정 검사가 죽어 :80 까지 안 뜨고, 그러면
발급 자체가 불가능해진다. 그래서 배포 → 발급 → 재기동 순서다.

```bash
# 박스에서 (aws ssm start-session --target <instance-id>)
cd /opt/sphinx
SSM_PREFIX=/sphinx/alpha SPHINX_PUBLIC_HOST=sphinxfin.duckdns.org SPHINX_DEMO_OPEN=1 \
  ./scripts/deploy_ec2.sh --cert
```

발급하고 web 을 재기동하는 것까지 한 줄이다. 끝나면 `TLS 켜짐 — https://…` 이 찍힌다.
만료 알림을 받으려면 `LETSENCRYPT_EMAIL=…` 을 같이 넘긴다(비우면 등록 없이 받는다).

❗**`SPHINX_DEMO_OPEN=1` 을 빼지 않는다(alpha 한정).** `--cert` 는 web 을 두 번 재기동하는데
그때 넘어간 값으로 새로 뜬다 — 안 주면 개방 모드(§9.3)가 꺼진 채 돌아와 **인증서는 받았는데
전 화면이 401** 이 된다. prod 는 애초에 개방 모드가 아니므로 붙이지 않는다.

`--cert` 가 **web 만**(`--no-deps`) 건드리는 것도 같은 종류의 사고를 막는다. 이 분기는
`exit 0` 으로 끝나서 `SPHINX_DEMO_SYNTHETIC_SESSIONS` 를 켜는 줄(결정 10.58)까지 못 가는데,
compose 가 `depends_on` 을 따라 `server` 까지 재생성하면 server 가 기본값 `false` 로 돌아와
**합성 세션을 안 읽고 S-08 대시보드가 빈 표**가 된다. 화면도 로그도 정상이라 #179 와 똑같이
조용하다 — 2026-09-03 계정 이관 중 실제로 이 경로로 대시보드가 비었다.

❗**`docker compose run --rm certbot …` 을 손으로 치면 안 된다.** 두 가지가 걸린다.

1. compose 는 명령이 무엇이든 **파일 전체를 먼저 해석하고**, 이 파일의 비밀들은 `${VAR:?}`
   라 값이 없으면 거기서 죽는다. 값을 가진 것은 `deploy_ec2.sh` 뿐이라(SSM 에서 받는다)
   발급도 그 안에 있다.
2. 값이 있어도 그대로는 안 돈다. **`docker compose run` 은 `command` 만 덮고 `entrypoint`
   는 안 덮는데**, `certbot` 서비스의 entrypoint 는 갱신 루프 때문에 `/bin/sh` 다. 그래서
   argv 가 `/bin/sh certonly --webroot …` 가 되고 sh 가 `certonly` 를 스크립트 파일로 읽어
   `certonly: No such file or directory`(exit 127) 로 끝난다 — **certbot 이 안 불린 것인데
   certbot 이 실패한 것처럼 보인다.** 꼭 손으로 쳐야 하면 `--entrypoint certbot` 을 붙인다.

❗**발급을 배포 스크립트에 넣지 않았다.** Let's Encrypt 는 실패에도 rate limit(같은 도메인
1시간 5회)을 매긴다. 배포마다 자동으로 시도하면 DNS 나 :80 이 잠깐 어긋난 날 **한도를
태워서 정작 필요한 순간에 못 받는다.** 갱신은 그 위험이 없어서(이미 받은 인증서가 있고
만료 30일 전에만 움직인다) certbot 컨테이너가 12시간마다 돌린다.

갱신된 파일을 nginx 가 다시 읽는 것은 `30-cert-reload.sh` 가 맡는다 — certbot 의
`--deploy-hook` 은 자기 컨테이너 안에서 도므로 nginx 를 reload 하려면 도커 소켓을 붙여야
하고, 그건 컨테이너 하나에 호스트 전체 권한을 주는 것이라 안 한다.

### 9.3 alpha 는 **개방 모드**로 뜬다 (결정 10.57)

대회 데모라 심사위원이 로그인 없이 바로 보는 것이 요구사항이다. `SPHINX_DEMO_OPEN=1` 이면
nginx 가 `auth_basic` 을 끄고 **데모 계정으로 대신 로그인해서** `/api` 로 넘긴다.

```
/api/dashboard/…                      compl-01   집계는 COMPL(org)·MGR(branch) 뿐이다
/api/sessions/{sid}/override/approve  mgr-01     요청자 ≠ 승인자 (ADR-002)
그 밖의 /api/…                        seller-01  세션 생성·면담·판정·리포트·상품
```

**인증을 끄는 것이 아니다.** Spring 의 prod 체인은 그대로 `anyRequest().authenticated()` 이고
`@PreAuthorize` 도 그대로 돈다 — 진짜로 끄면(`permitAll`) 익명은 역할이 없어서 세션 생성부터
403 이라 **로그인 창만 사라지고 화면은 더 안 된다.**

한 계정으로는 전 화면이 안 열려서 경로별로 가른다. 대가는 **감사 로그의 "누가" 가 이 세
계정으로 굳는 것**이다 — nginx 가 고른 값이라 "그 사람이 했다" 는 뜻이 아니다.

❗**prod 에는 주지 않는다.** 워크플로가 환경으로 가른다(`deploy.yml` 의 `환경·커밋 결정`).
prod 배포의 노출 확인은 여전히 `401` 이 아니면 실패하므로 `#41` 1항 회귀 검사가 산다.

### 9.4 확인

```bash
curl -sS -o /dev/null -w '%{http_code}\n' http://sphinxfin.duckdns.org/     # 301 (https 로)
curl -sS -o /dev/null -w '%{http_code}\n' https://sphinxfin.duckdns.org/    # 200 (로그인 없음)
curl -sS https://sphinxfin.duckdns.org/api/dashboard/heatmap | head -c 200   # 200 · compl-01 로 나간다
```

`http://` 가 200 이면 인증서가 아직 없는 것이다(§9.2). `https://` 가 401 이면 개방 모드가
안 켜진 것이라 `docker compose logs web | grep 모드` 를 본다.

### 9.5 데모는 alpha **개방 모드로 간다** — 역할 차단은 따로 보여준다 (결정 10.70)

**정한 것: 심사위원 무로그인 관람이 우선이다.** alpha 는 개방 모드(§9.3)를 대회 끝까지
유지하고, **prod 는 세우지 않는다.**

#### ❗`demo-*` 태그를 밀지 않는다 — prod 는 스택이 없다

`deploy.yml` 에 prod 경로가 살아 있어서 태그 한 번이면 갈 것처럼 보이는데, **받아 줄 것이
아무것도 없다.** 2026-09-04 실측이다.

```
IAM   sphinx-alpha-deployer · sphinx-alpha-instance     prod 역할 없음 → OIDC 스텝에서 죽는다
EC2   i-010e881ef97178ce8  sphinx-alpha  running        prod 박스 없음
SSM   /sphinx/alpha/{llm-api-key,api-user,api-password,internal-token,db-password}
      /sphinx/prod/*                                    0건
```

`infra/locals.tf` 에 `prod` 워크스페이스가 **정의**돼 있을 뿐 한 번도 apply 하지 않았다.
세우려면 EC2·IAM·SSM 을 새로 만들고 그 위에서 배포·인증서·모드를 처음부터 다시 밟아야 하는데,
대회 일정 안에서 그럴 값이 없다고 봤다. 그래서 **alpha 가 데모 환경이다.**

#### 그 대신 치르는 대가 — 역할 차단이 화면에서 안 보인다

개방 모드와 ADR-001·기획 7-4 의 역할 차단 시연은 **서로 배타적**이다.

```
시연 1  SELLER 가 집계에 접근 → 막힌다      /api/dashboard/… 는 nginx 가 compl-01 을
                                             실어 주므로 누가 열어도 200 이다
시연 2  다른 SELLER 의 세션 → 막힌다        모든 방문자가 seller-01 이라
                                             "남의 세션" 이 존재하지 않는다
```

**alpha 를 잠가서 해결하지 않는다** — 그러면 무로그인 관람이 죽고, 그게 이번 선택의 전제다.
역할 차단은 아래 둘 중 하나로 보여준다.

#### ㉮ 기본 — **로컬 compose** 로 보여준다 (권장)

잠금이 기본값이라 아무것도 끌 것이 없다. 심사 화면(alpha)은 계속 열려 있다.

```bash
P=demo-local-only        # 로컬 전용. 아무 값이어도 되고 SSM 과 무관하다
export SPHINX_API_USER=seller-01
export SPHINX_API_PASSWORD=$P
export SPHINX_INTERNAL_TOKEN=$(openssl rand -hex 32)
export SPHINX_API_USERS=$(sed -n 's/^.*[^A-Za-z0-9_-]id:[[:space:]]*\([A-Za-z0-9_-]*\).*/\1/p' \
                            server/src/main/resources/demo_accounts.yaml | paste -sd, -)
docker compose up -d --build
```

`SPHINX_DEMO_OPEN` 을 안 주므로 **잠금**이다(기본값). `SPHINX_API_USERS` 를 넘기는 이유는
htpasswd 를 명부 전체로 만들기 위해서다 — 안 넘기면 계정이 하나만 생겨 이 시연 자체가
401 이다(`#213`).

> ❗위 `sed` 는 `deploy_ec2.sh` 의 명부 추출을 **옮겨 적은 것**이고, 그쪽에만 있는 가드
> (항목 수와 추출 수 대조)가 여기에는 없다. 명부 형식이 바뀌면 여기서는 **조용히 줄어든다** —
> 붙여 넣고 나서 `echo $SPHINX_API_USERS` 로 계정 수가 명부와 같은지 한 번 본다. 정본은
> 언제나 `demo_accounts.yaml` 과 `scripts/deploy_ec2.sh` 다.

#### ㉯ alpha 를 잠가야 한다면 — **심사 시간 밖에서만**

박스(`i-010e881ef97178ce8`)에서 배포와 **같은 명령**을 돌리되 `SPHINX_DEMO_OPEN` 만 비운다.
`deploy.yml` 이 alpha 에 `demo_open=1` 을 박아 두므로 **워크플로로는 못 끈다.**

```bash
cd /opt/sphinx
SSM_PREFIX=/sphinx/alpha \
SPHINX_PUBLIC_HOST=sphinxfin.duckdns.org \
SPHINX_DEMO_OPEN= \
  ./scripts/deploy_ec2.sh
```

되돌릴 때는 `SPHINX_DEMO_OPEN=1` 로 같은 명령을 돌린다.

**모드만 바꾸려고 `web` 컨테이너를 따로 다시 만드는 지름길을 표준 절차로 적지 않는다** —
세 값이 조용히 빠지고, 셋 다 화면이 멀쩡해 보이는 방향으로 나빠진다.

```
SPHINX_PUBLIC_HOST 누락   443 을 안 세운다 — https 가 죽는다 (20-tls.sh 는 기동 때 본다)
SPHINX_API_USERS   누락   htpasswd 에 계정이 하나만 남는다 — 시연 자체가 401 이다 (#213)
--no-deps          누락   server 까지 재생성돼 SPHINX_DEMO_SYNTHETIC_SESSIONS 가 기본값으로
                          돌아가고 S-08 이 빈 표가 된다 (결정 10.58 · #179 와 같은 조용함)
```

#### ❗잠가 둔 동안 `main` 에 머지하지 않는다

`main` 푸시는 alpha 자동 배포를 걸고, 그 배포는 `demo_open=1` 로 다시 뜬다. **잠가 둔 것이
머지 한 번으로 조용히 열린다** — 화면은 멀쩡히 뜨고 로그인 창만 사라지므로 알아채기 어렵다.
반대로, **개방으로 되돌리는 데에는 이게 쓸모가 있다**: 아무 커밋이나 밀면 원래 상태로 돌아온다.

#### 확인 — 어느 모드인지

```bash
curl -sS -o /dev/null -w '%{http_code}\n' https://sphinxfin.duckdns.org/api/products
# 개방 200  ·  잠금 401

docker compose logs --tail 20 web | grep 모드
```

데모 중에는 이 값이 **200 이어야 정상**이다(무로그인 관람). `401` 이 나오면 누가 잠갔거나
배포가 어긋난 것이다.

#### 시연 — 계정을 바꿔 가며 본다

비밀번호는 전 계정 공통이고 alpha 는 `/sphinx/alpha/api-password` 하나다. id 는 명부
(`server/src/main/resources/demo_accounts.yaml`)에 있다. `$B` 는 로컬이면
`http://localhost/api`, alpha 를 잠갔으면 `https://sphinxfin.duckdns.org/api` 다.

```bash
# 시연 1 — 집계는 COMPL·MGR 뿐이다 (rbac_policy.yaml · ADR-001)
curl -sS -u "seller-01:$P" -o /dev/null -w 'seller-01 → 집계 %{http_code}\n' "$B/dashboard/heatmap"   # 403
curl -sS -u "compl-01:$P"  -o /dev/null -w 'compl-01  → 집계 %{http_code}\n' "$B/dashboard/heatmap"   # 200
curl -sS -u "mgr-01:$P"    -o /dev/null -w 'mgr-01    → 집계 %{http_code}\n' "$B/dashboard/heatmap"   # 200 (자기 지점만)

# 시연 2 — own_session. seller-02 가 seller-01 의 세션을 연다
curl -sS -u "seller-02:$P" -o /dev/null -w 'seller-02 → 남의 세션 %{http_code}\n' "$B/sessions/<SID>"  # 403
```

기대값의 근거는 `rbac_policy.yaml` 하나다 — 여기 적은 숫자가 아니라 그 파일이 참이다.

```
aggregate:heatmap:read   COMPL(org) · MGR(branch)     ← SELLER 부재가 ADR-001 의 실물이다
session:read             SELLER(own_session) · MGR(branch) · COMPL(org)
```

❗**개방 모드에서는 이 명령이 전부 200 이다.** nginx 가 `Authorization` 을 경로별 데모
계정으로 갈아 끼우므로 `-u` 가 무시된다 — 잠긴 곳에서 돌려야 의미가 있다.

❗**시연 2 에는 주인이 seller-01 인 세션이 필요하다.** 귀속은 인증 주체에서만 오고
(`CurrentActor`), `SessionResponse` 에 `sellerId` 가 없어 **되읽어 확인할 방법이 없다** —
그래서 만들 때 정해야 한다.

```
개방 모드에서 만든 세션    nginx 가 /api/sessions 에 seller-01 을 실으므로 주인은 seller-01
                           (클라이언트가 -u 로 뭘 보내든 갈아 끼워진다)
잠금 + AUTH 없이 만든 세션  주인이 아예 없다. 나중에 붙일 수도 없다
잠금 + AUTH=seller-01      주인이 seller-01
```

가운데 줄을 피한다. 잠긴 환경에서 한 건 만들어 두는 것이 확실하다.

```bash
AUTH=seller-01:$P BASE=$B scripts/walk_demo_session.sh
```
