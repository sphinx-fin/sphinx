# SphinX 인프라 — OpenTofu
# 소유: 오준서 (인프라 R). 근거: docs/deployment.md · 이슈 #41.
#
# 환경은 **워크스페이스 이름이 곧 환경**이다(locals.tf 참조). tfvars 파일을 따로 두지
# 않는 이유는 하나다 — 워크스페이스는 prod 로 잡아두고 alpha.tfvars 를 넘기는 사고가
# 구조적으로 불가능해야 하기 때문이다. 환경별 차이는 locals.env_cfg 한 곳에만 있다.
#
#   tofu workspace select alpha && tofu plan
#
terraform {
  required_version = ">= 1.10"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }

  # state 버킷은 IaC 밖에서 한 번 만든다(닭-달걀). 버저닝·암호화·퍼블릭차단 걸어뒀다.
  # use_lockfile 은 OpenTofu 1.10+ 의 S3 네이티브 잠금이다 — DynamoDB 테이블이 필요 없다.
  #
  # ❗**버킷 이름의 숫자는 계정 ID 다** — 다른 리소스가 쓰는 `data.aws_caller_identity`
  # 와 달리 backend 블록은 변수·표현식을 못 받아서 여기만 리터럴이다. 계정을 옮기면
  # 이 줄을 같이 고쳐야 하고, 안 고치면 **새 계정 자격증명으로 옛 계정 state 를 읽으려
  # 들어** AccessDenied 로 죽는다(그게 낫다 — 조용히 갈리는 것보다).
  #
  # 2026-09-03: 422836430893 → 196337091527 로 옮겼다. 옛 계정의 Free Plan 크레딧이
  # $10.48 까지 떨어져 리허설(9/3~9/6)·데모 기간을 못 버틴다. 새 계정은 $100 이 남아
  # 있고 2027-03-03 까지다. state 는 옮기지 않았다 — 리소스가 다른 계정에 새로 생기므로
  # 옛 state 를 들고 와도 전부 orphan 이고, 새 워크스페이스에서 처음부터 만드는 것이
  # 맞다. 옛 계정 리소스는 그쪽 state 에 그대로 남아 있어 정리도 그쪽에서 한다.
  backend "s3" {
    bucket               = "sphinx-tfstate-196337091527"
    key                  = "sphinx.tfstate"
    workspace_key_prefix = "env"
    region               = "ap-northeast-2"
    encrypt              = true
    use_lockfile         = true
  }
}

provider "aws" {
  region = var.region

  # 태그를 provider 에 걸어 두면 리소스마다 안 적어도 된다. 공모전 계정이라 나중에
  # "이건 뭐지" 가 되는 것을 막는 게 목적이다.
  default_tags {
    tags = {
      Project   = "sphinx"
      Env       = local.env
      ManagedBy = "opentofu"
    }
  }
}
