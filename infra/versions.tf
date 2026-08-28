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
  backend "s3" {
    bucket               = "sphinx-tfstate-422836430893"
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
