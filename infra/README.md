# infra/ — OpenTofu

`docs/deployment.md` 가 서술한 것을 코드로 옮긴 것이다. **문서가 근거고 여기는 실물이다** —
둘이 어긋나면 문서를 먼저 읽고 고칠 쪽을 정한다.

소유: 오준서 (인프라 R). 이슈 [#41](../../../issues/41) · 결정로그 10.3.

---

## 1. IaC 와 CD 는 다른 층이다

섞기 쉬운데, 도는 시점도 도는 이유도 다르다.

```
IaC  (tofu apply · 손으로)    EC2·SG·IAM 이 존재한다        거의 안 돈다
CD   (main 푸시 · 자동)       그 EC2 위 코드가 최신이다      머지마다 돈다
```

`main` 이 바뀌어도 **인프라는 안 바뀐다.** 인스턴스 타입이나 SG 규칙을 고칠 때만
`tofu apply` 를 사람이 부른다.

## 2. 환경은 워크스페이스 이름이다

```bash
source ../aws-env.sh            # 이 레포 전용 AWS 프로필
tofu -chdir=infra workspace select alpha
tofu -chdir=infra plan
```

tfvars 파일을 두지 않는다. 워크스페이스는 `prod` 로 잡아 놓고 `alpha.tfvars` 를 넘기는
사고가 **구조적으로 불가능**해야 하기 때문이다. 환경별 차이는 `locals.tf` 의 `env_cfg`
한 곳에만 있다. `default` 워크스페이스에서 apply 하면 lookup 이 키를 못 찾아 죽는다 —
그것도 의도다.

| | alpha | prod |
|---|---|---|
| SSM 접두어 | `/sphinx/alpha` | `/sphinx/prod` |
| 배포 트리거 | `main` 푸시 (자동) | `demo-*` 태그 |
| 80번 소스 | 전체 | 전체 (심사 IP 알면 좁힌다) |

## 3. 왜 `alpha` **브랜치**는 없는가

환경을 나누는 것과 브랜치를 하나 더 두는 것은 다른 얘기다. 이 레포에서 후자의 비용이 크다.

1. **머지가 두 번이 된다.** `feat/*` → `alpha` → `main`. 4명이 3주 타임박스에서
   `pr-review-guard` 승인 게이트까지 통과시키는 구조라 두 번째 머지가 그대로 지연이다.
2. **기존 워크플로가 `main` 을 전제로 짜여 있다.** `pr-reviewer-label.yml` 과
   `pr-review-guard.yml` 은 한 쌍으로 동작하고, 한쪽만 고치면 *가드는 머지를 막는데 라벨은
   비어 있는* 상태가 된다(PR #90 · #91 에서 실제로 두 번 났다). 타깃 브랜치를 늘리면 그 두
   파일을 같이 손봐야 한다.

태그가 "검증된 커밋을 승격한다"는 성질을 머지 없이 준다.

```
feat/*  ──PR──▶  main  ──자동──▶  alpha
                   │
                   └─ git tag demo-0903 && git push --tags ──▶  prod
```

## 4. 부트스트랩 (계정당 한 번)

```bash
source ../aws-env.sh && ./infra/bootstrap.sh
```

state 버킷 · 아티팩트 버킷 · GitHub OIDC 공급자를 만든다. IaC 안에 못 두는 것들이다 —
state 버킷은 "state 를 넣을 곳을 state 로 만드는" 닭-달걀이고, OIDC 공급자는 계정당
하나뿐이라 alpha·prod 워크스페이스가 각자 만들려 들면 두 번째가 `EntityAlreadyExists`
로 죽는다. 아티팩트 버킷은 두 환경이 같이 쓴다(접두어로 가른다).

## 5. 코드는 GitHub 이 아니라 S3 로 박스에 간다

`docs/deployment.md` §5 는 박스에서 `git clone` 하는 그림인데, **이 레포는 private 이라
그러려면 박스에 GitHub 자격증명(deploy key 나 PAT)을 둬야 한다.** 그리고 조직 레포에
deploy key 를 붙이려면 repo admin 이 필요한데 인프라 담당은 push 까지만 갖고 있다.

그래서 방향을 뒤집었다. CD 가 `git archive` 로 **커밋 스냅샷**을 만들어 S3 에 올리고,
박스는 그것만 받아 푼다.

```
GitHub Actions ──git archive──▶ s3://sphinx-artifacts-<계정>/<env>/<sha>.tar.gz
                                          │
                        SSM Run Command ──▶ 박스가 받아 /opt/sphinx 에 풀고
                                            scripts/deploy_ec2.sh 를 돈다
```

결과가 더 낫다.

- **박스에 GitHub 자격증명이 하나도 없다.** 유출될 것이 애초에 없다.
- **부팅이 GitHub 가용성에 안 걸린다.** `user_data` 는 docker 만 깔고 끝난다.
- **무엇이 배포됐는지가 객체 키에 남는다** — `<env>/<sha>.tar.gz`.
- `git archive` 는 추적 중인 파일만 담아서 `clone` 과 결과가 같다. `data/` ·
  `contracts/` 가 통째로 오므로 §6 의 bind mount 가 그대로 성립한다.

받기에 실패하면 새 디렉토리에서 멈추고 **돌고 있던 배포를 안 지운다**(`set -e`).
스냅샷은 14일 뒤 만료된다 — 그 뒤로는 커밋 SHA 로 다시 만들면 된다.

박스에서 손으로 다시 띄울 일이 있으면 마지막 배포본이 `/opt/sphinx` 에 그대로 있다:

```bash
cd /opt/sphinx && SSM_PREFIX=/sphinx/alpha ./scripts/deploy_ec2.sh
```

## 6. 비밀은 state 에 안 들어간다

**SSM 파라미터를 tofu 로 만들지 않는다.** state 는 값을 평문으로 들고 있어서, `.env` 를
EC2 에 안 두려고 SSM 을 쓴 `docs/deployment.md` §4 의 취지가 state 파일로 그대로 샌다.
IaC 는 그 경로에 대한 **접근 권한만** 만든다.

값은 CLI 로 한 번 넣는다(`<env>` 를 바꿔 prod 도 같은 방식):

```bash
source ../aws-env.sh
for K in llm-api-key api-user api-password; do
  read -rs -p "$K: " V && echo
  aws ssm put-parameter --name "/sphinx/alpha/$K" --type SecureString --value "$V" --overwrite
done
```

세 값 모두 `scripts/deploy_ec2.sh` 가 읽어 compose 환경변수로만 넘긴다. `api-user` ·
`api-password` 는 nginx 의 htpasswd 와 server 의 prod 인증이 **같은 값**을 쓰므로
출처가 SSM 하나로 유지된다(#162).

## 7. 접속 — 22번은 안 연다

`ssh_cidrs` 기본값이 빈 목록이라 **22번 규칙이 하나도 안 생긴다.** 인스턴스에 SSM
에이전트가 붙어 있어서 인바운드 없이 셸을 얻는다.

```bash
aws ssm start-session --target "$(tofu -chdir=infra output -raw instance_id)"
```

`docs/deployment.md` §3 은 "22 는 내 IP 만"이라고 적었는데, 열지 않은 포트가 잘못 열릴 일이
제일 적다. SSM 에이전트가 죽어 못 들어가는 상황에만 `ssh_cidrs` 에 내 IP/32 와 `key_name`
을 주고, 쓰고 나면 다시 비운다.

## 8. 손대면 안 되는 것

- **`network.tf` 에 8000·8100 규칙을 추가하지 않는다.** 빠뜨린 게 아니다. :8100 이 퍼블릭에
  뜨면 `/internal/*` 이 무인증이라 PII 마스킹을 건너뛰고 LLM 에 직접 프롬프트를 넣을 수 있고,
  CLAUDE.md 의 P3 가 그 순간 거짓이 된다. 이슈 #41 ①③.
- **`aws_instance.app` 의 `ignore_changes = [ami]` 를 지우지 않는다.** AWS 가 새 AL2023 AMI 를
  내는 순간, 관계없는 `apply` 한 번에 데모 박스가 재생성된다.
- **인스턴스 타입은 free-tier 대상 목록 안에서만 고른다.** 이 계정은 AWS Free Plan 이라
  목록 밖 타입은 `RunInstances` 가 거부한다(`InvalidParameterCombination`). t3.medium 은
  목록에 **없다.** 확인은
  `aws ec2 describe-instance-types --filters Name=free-tier-eligible,Values=true`.
  그 안에서도 2GB 이하로 낮추지 않는다 — `deploy_ec2.sh` 가 박스 위에서
  `docker compose up --build` 를 해서 Gradle·npm·pip 빌드가 한 곳에 몰린다.

## 9. 알려진 한계

- **CD 가 `ci.yml` 을 기다리지 않는다.** 같은 푸시에서 테스트와 배포가 나란히 돈다.
  alpha 는 깨진 머지를 먼저 보라고 있는 곳이라 일부러 뒀다. 기다리게 하려면 `deploy.yml`
  의 `on:` 을 `workflow_run` 으로 바꾼다.
- **인스턴스 위에서 빌드한다.** 배포마다 10분 안팎이 든다. 줄이려면 GitHub Actions 에서
  이미지를 만들어 ECR 에 올리고 박스는 pull 만 하게 바꾼다 — 지금 범위 밖이다.
- **`user_data` 수정은 떠 있는 박스에 반영되지 않는다.** 반영하려면 `tofu taint` 로 일부러
  다시 만든다(재빌드 10분).
