variable "region" {
  description = "리전. 서울 고정이다 — docs/deployment.md §2(기획서 416·422행: 국내 처리)."
  type        = string
  default     = "ap-northeast-2"
}

variable "github_repo" {
  description = "OIDC 신뢰와 deploy key 의 대상 레포."
  type        = string
  default     = "sphinx-fin/sphinx"
}

variable "github_repo_ids" {
  description = <<-EOT
    같은 레포를 **숫자 ID 로 적은 표기**: `<org>@<org_id>/<repo>@<repo_id>`.

    GitHub 이 OIDC 토큰의 `sub` 를 이 표기로 발급한다(불변 subject). 이름은 바뀌어도
    ID 는 안 바뀌므로, 레포를 지우고 같은 이름을 남이 차지해도 신뢰가 넘어가지 않는다.

    값은 짐작하지 말고 레포가 알려주는 것을 그대로 쓴다:

      gh api /repos/sphinx-fin/sphinx/actions/oidc/customization/sub
      → {"sub_claim_prefix":"repo:sphinx-fin@319472519/sphinx@1342489616", ...}

    `repo:` 접두어는 locals 에서 붙이므로 여기에는 넣지 않는다.
  EOT
  type        = string
  default     = "sphinx-fin@319472519/sphinx@1342489616"
}

variable "ssh_cidrs" {
  description = <<-EOT
    22번을 열 CIDR. **기본은 빈 목록 = 22번을 아예 안 연다.**

    문서 §3 은 "22 는 내 IP 만"이라고 적었지만, 인스턴스에 SSM 에이전트가 붙어 있어서
    `aws ssm start-session` 으로 셸을 얻을 수 있다 — 인바운드를 하나도 안 열고. 열지
    않은 포트가 잘못 열릴 일이 제일 적다.

    SSM 에이전트가 죽어 못 들어가는 상황(break-glass)에만 여기에 내 IP/32 를 넣고
    key_name 을 같이 준다. 쓰고 나면 다시 비운다.
  EOT
  type        = list(string)
  default     = []
}

variable "key_name" {
  description = "break-glass 용 EC2 키페어 이름. ssh_cidrs 를 열 때만 의미가 있다."
  type        = string
  default     = null
}
