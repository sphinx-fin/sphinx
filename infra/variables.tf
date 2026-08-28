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
