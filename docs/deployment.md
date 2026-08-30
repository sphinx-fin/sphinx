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
```

네 값 모두 `scripts/deploy_ec2.sh` 가 읽어 **환경변수로만** 넘긴다. `internal-token` 은
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

# ❗아래 둘은 **실패해야 정상이다**
curl --max-time 3 http://<EC2 퍼블릭 IP>:8100/healthz   # ai-service 직접 — 막혀야 한다
curl --max-time 3 http://<EC2 퍼블릭 IP>:8000/products  # server 직접 — 막혀야 한다
```

마지막 두 줄을 배포 때마다 실제로 돌린다. 노출은 설정이 한 줄 바뀌면 조용히 되살아나는
종류라, "안 열려 있음"을 확인하는 명령이 있어야 한다.
