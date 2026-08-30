# 기본 VPC 를 쓴다. 공모전 MVP 에 VPC 를 직접 짜는 것은 값이 안 나온다 — 격리는
# docker compose 의 edge/internal 네트워크가 이미 하고 있고(docs/deployment.md §1),
# 여기서 필요한 것은 "80 말고는 안 열린다" 하나다.
data "aws_vpc" "default" {
  default = true
}

data "aws_subnets" "default" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default.id]
  }
}

# ── 보안그룹 — docs/deployment.md §3 ────────────────────────────────────────
#
# ❗`description` 은 **ASCII 만 받는다.** 한글을 넣으면 CreateSecurityGroup 이
# `InvalidParameterValue: Character sets beyond ASCII are not supported` 로 죽는다.
# 설명은 주석에 한글로, description 필드는 영어로 둔다.
#
# ❗8000(server)·8100(ai-service) 규칙은 **없다. 빠뜨린 게 아니라 없는 게 맞다.**
# 여기에 규칙을 추가하면 이슈 #41 ①③ 이 되살아난다. :8100 이 퍼블릭에 뜨는 순간
# /internal/* 이 무인증이라 PII 마스킹을 건너뛰고 LLM 에 직접 프롬프트를 넣을 수 있고,
# CLAUDE.md 의 P3("고객 텍스트가 ai-service 로 나가는 유일한 경로")가 거짓이 된다.
# 화면은 web 컨테이너의 nginx 가 /api 로 프록시한다.
resource "aws_security_group" "app" {
  name        = local.name
  description = "SphinX ${local.env} - only :80 is reachable from outside"
  vpc_id      = data.aws_vpc.default.id

  tags = { Name = local.name }
}

resource "aws_vpc_security_group_ingress_rule" "http" {
  for_each = toset(local.cfg.http_cidrs)

  security_group_id = aws_security_group.app.id
  description       = "web UI (nginx)"
  cidr_ipv4         = each.value
  from_port         = 80
  to_port           = 80
  ip_protocol       = "tcp"
}

# ❗80 을 닫고 443 만 여는 것이 아니다 — **둘 다 필요하다.**
#
# Let's Encrypt HTTP-01 은 `http://<도메인>/.well-known/acme-challenge/…` 를 :80 으로 읽는다.
# 80 을 닫으면 첫 발급도 90일 뒤 갱신도 실패하고, 갱신 실패는 **만료되는 날까지 조용하다.**
# 평상시 브라우저 트래픽은 nginx 가 :80 에서 https 로 튕기므로(web/nginx.conf) 80 이 열려
# 있다고 평문으로 서비스되는 것은 아니다.
resource "aws_vpc_security_group_ingress_rule" "https" {
  for_each = toset(local.cfg.http_cidrs)

  security_group_id = aws_security_group.app.id
  description       = "web UI (nginx, TLS)"
  cidr_ipv4         = each.value
  from_port         = 443
  to_port           = 443
  ip_protocol       = "tcp"
}

# ssh_cidrs 기본값이 빈 목록이라 보통은 규칙이 **하나도 안 생긴다.** 평상시 접속은
# `aws ssm start-session` 이다. variables.tf 의 ssh_cidrs 주석 참조.
resource "aws_vpc_security_group_ingress_rule" "ssh" {
  for_each = toset(var.ssh_cidrs)

  security_group_id = aws_security_group.app.id
  description       = "break-glass SSH"
  cidr_ipv4         = each.value
  from_port         = 22
  to_port           = 22
  ip_protocol       = "tcp"
}

# ── 아웃바운드 ──────────────────────────────────────────────────────────────
#
# 문서 §3 은 "아웃바운드 443"만 적었는데, 그대로 하면 **부팅이 안 된다.** 보안그룹은
# VPC DNS 리졸버로 가는 트래픽에도 적용돼서 53 을 막으면 이름 해석부터 죽고, 그러면
# dnf·docker pull·git clone·SSM 연결이 전부 실패한다. 123(NTP)은 시계가 틀어지면
# SigV4 서명이 깨져 SSM 호출이 거부되기 때문에 같이 연다.
#
# 즉 아래 셋은 문서를 어기는 게 아니라 문서의 "443" 이 성립하기 위한 전제다.
resource "aws_vpc_security_group_egress_rule" "https" {
  security_group_id = aws_security_group.app.id
  description       = "LLM API, SSM, image registry, artifacts"
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = 443
  to_port           = 443
  ip_protocol       = "tcp"
}

resource "aws_vpc_security_group_egress_rule" "dns_udp" {
  security_group_id = aws_security_group.app.id
  description       = "DNS"
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = 53
  to_port           = 53
  ip_protocol       = "udp"
}

resource "aws_vpc_security_group_egress_rule" "dns_tcp" {
  security_group_id = aws_security_group.app.id
  description       = "DNS (TCP fallback)"
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = 53
  to_port           = 53
  ip_protocol       = "tcp"
}

resource "aws_vpc_security_group_egress_rule" "ntp" {
  security_group_id = aws_security_group.app.id
  description       = "NTP (clock skew breaks SigV4)"
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = 123
  to_port           = 123
  ip_protocol       = "udp"
}
