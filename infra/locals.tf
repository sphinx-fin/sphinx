locals {
  # 워크스페이스 이름이 곧 환경이다. `default` 워크스페이스에서 apply 하면 아래 lookup 이
  # 키를 못 찾아 죽는다 — 그게 의도다. 환경을 안 고르고 올라가는 경로를 없앤다.
  env = terraform.workspace

  # ── oidc_subs 는 왜 한 환경에 두 줄인가 (이슈 #206) ────────────────────────
  #
  # GitHub 이 이 레포의 OIDC `sub` 를 **숫자 ID 표기로 발급**하고 있다. 신뢰 정책에
  # 이름 표기만 적혀 있어서 도입 이후 14회가 전부 같은 자리에서 죽었다:
  #
  #   ##[error]Could not assume role with OIDC:
  #            Not authorized to perform sts:AssumeRoleWithWebIdentity
  #
  # 로그만으로는 *역할이 없음* 과 *sub 불일치* 가 같은 문면이라 안 갈린다. 실제로
  # 온 값은 CloudTrail 에 남아 있었다(userIdentity.userName):
  #
  #   repo:sphinx-fin@319472519/sphinx@1342489616:ref:refs/heads/main
  #   ↑ 정책에 적혀 있던 것은 repo:sphinx-fin/sphinx:ref:refs/heads/main
  #
  #   aws cloudtrail lookup-events \
  #     --lookup-attributes AttributeKey=EventName,AttributeValue=AssumeRoleWithWebIdentity
  #
  # 이름 표기를 **남겨 두는** 이유는 전환 중이기 때문이다. 레포 설정은 아직
  # `use_immutable_subject:false` 인데 발급값은 ID 표기다 — GitHub 쪽 전환이라
  # 되돌아가면 이름 표기로 다시 온다. 그때 배포가 또 죽는 것보다 두 줄이 낫다.
  # 전환이 끝나면(설정이 immutable 로 굳으면) 이름 표기 줄을 지운다.
  #
  # ❗**와일드카드로 합치지 않는다.** `repo:sphinx-fin*/sphinx*:...` 는 남이 만든
  # `sphinx-finance/sphinxx` 도 통과시킨다. 두 값을 그대로 적는 편이 짧지 않아도 안전하다.

  env_cfg = {
    alpha = {
      # ❗**타입을 아무거나 못 고른다.** 이 계정은 AWS Free Plan 이라 free-tier 대상
      # 타입이 아니면 RunInstances 가 `InvalidParameterCombination` 으로 거부한다.
      # 목록은 `aws ec2 describe-instance-types --filters Name=free-tier-eligible,Values=true`
      # 로 확인한다 — 현재 t3/t4g.micro(1GB) · t3/t4g.small(2GB) · c7i-flex.large(4GB) ·
      # m7i-flex.large(8GB) 뿐이고, t3.medium 은 목록에 없어서 못 뜬다.
      #
      # 그 중 8GB 를 고른 이유는 deploy_ec2.sh 가 인스턴스 **위에서**
      # `docker compose up --build` 를 해서 Gradle·npm·pip 빌드가 한 박스에 몰리기
      # 때문이다. 2GB 이하는 OOM 으로 죽고 4GB 는 아슬아슬하다.
      #
      # t4g 계열을 고르면 AMI 도 arm64 로 같이 바꿔야 한다(ec2.tf 의 data 이름).
      instance_type = "m7i-flex.large"
      root_gb       = 30

      # alpha 는 팀이 보는 곳이다. 전체 공개로 두되 심사 대상이 아니므로 부담이 없다.
      # 443 도 같은 목록을 쓴다(network.tf) — https 는 여기서 발급받은 인증서로 선다.
      http_cidrs = ["0.0.0.0/0"]

      # 공개 도메인. DuckDNS A 레코드가 이 환경의 EIP 를 가리킨다(결정 10.57).
      # ❗**이 값은 출력용이다.** 실제로 nginx 가 인증서를 찾는 이름은 배포 워크플로가
      # `SPHINX_PUBLIC_HOST` 로 넘긴다(.github/workflows/deploy.yml). 두 곳이 갈리면
      # 화면 주소만 틀리게 찍히고 서비스는 멀쩡하므로, 여기를 근거로 삼지 않는다.
      public_host = "sphinx2026.duckdns.org"

      # main 에 머지되면 자동으로 여기로 나간다.
      oidc_subs = [
        "repo:${var.github_repo_ids}:ref:refs/heads/main",
        "repo:${var.github_repo}:ref:refs/heads/main",
      ]
    }

    prod = {
      # alpha 와 같은 이유. 위 주석 참조.
      instance_type = "m7i-flex.large"
      root_gb       = 30

      # 심사 IP 를 알면 여기를 좁힌다. 문서 §3 의 "0.0.0.0/0 (또는 심사 IP)".
      http_cidrs = ["0.0.0.0/0"]

      # prod 는 아직 도메인이 없다. 비면 아래 url 출력이 IP 를 그대로 쓴다.
      public_host = ""

      # prod 는 브랜치로 안 나간다. alpha 에서 확인된 커밋에 태그를 달아야 간다.
      # 브랜치를 하나 더 만드는 대신 태그를 쓰는 이유는 infra/README.md 참조.
      oidc_subs = [
        "repo:${var.github_repo_ids}:ref:refs/tags/demo-*",
        "repo:${var.github_repo}:ref:refs/tags/demo-*",
      ]
    }
  }

  cfg  = local.env_cfg[local.env]
  name = "sphinx-${local.env}"

  # deploy_ec2.sh 가 읽는 접두어와 같은 값이어야 한다(스크립트 기본값은 /sphinx/prod).
  ssm_prefix = "/sphinx/${local.env}"

  # CD 가 커밋 스냅샷을 올리고 인스턴스가 받아 가는 곳. bootstrap.sh 가 만든다.
  artifacts_bucket = "sphinx-artifacts-${data.aws_caller_identity.me.account_id}"
}
