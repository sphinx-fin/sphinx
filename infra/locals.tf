locals {
  # 워크스페이스 이름이 곧 환경이다. `default` 워크스페이스에서 apply 하면 아래 lookup 이
  # 키를 못 찾아 죽는다 — 그게 의도다. 환경을 안 고르고 올라가는 경로를 없앤다.
  env = terraform.workspace

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
      http_cidrs = ["0.0.0.0/0"]

      # main 에 머지되면 자동으로 여기로 나간다.
      oidc_subs = ["repo:${var.github_repo}:ref:refs/heads/main"]
    }

    prod = {
      # alpha 와 같은 이유. 위 주석 참조.
      instance_type = "m7i-flex.large"
      root_gb       = 30

      # 심사 IP 를 알면 여기를 좁힌다. 문서 §3 의 "0.0.0.0/0 (또는 심사 IP)".
      http_cidrs = ["0.0.0.0/0"]

      # prod 는 브랜치로 안 나간다. alpha 에서 확인된 커밋에 태그를 달아야 간다.
      # 브랜치를 하나 더 만드는 대신 태그를 쓰는 이유는 infra/README.md 참조.
      oidc_subs = ["repo:${var.github_repo}:ref:refs/tags/demo-*"]
    }
  }

  cfg  = local.env_cfg[local.env]
  name = "sphinx-${local.env}"

  # deploy_ec2.sh 가 읽는 접두어와 같은 값이어야 한다(스크립트 기본값은 /sphinx/prod).
  ssm_prefix = "/sphinx/${local.env}"

  # CD 가 커밋 스냅샷을 올리고 인스턴스가 받아 가는 곳. bootstrap.sh 가 만든다.
  artifacts_bucket = "sphinx-artifacts-${data.aws_caller_identity.me.account_id}"
}
