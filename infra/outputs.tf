output "env" {
  description = "이 state 가 관리하는 환경 (= 워크스페이스 이름)"
  value       = local.env
}

output "url" {
  description = "화면 주소"
  # 도메인이 있으면 https 다 — 인증서가 선 뒤로 :80 은 그쪽으로 튕기므로(web/nginx.conf)
  # IP 주소를 계속 찍으면 **리다이렉트를 한 번 거치는 주소**를 안내하게 된다.
  # 인증서 발급 전에는 이 주소가 아직 안 뜬다(docs/deployment.md §9.2).
  value = local.cfg.public_host != "" ? "https://${local.cfg.public_host}/" : "http://${aws_eip.app.public_ip}/"
}

output "instance_id" {
  value = aws_instance.app.id
}

output "session" {
  description = "셸 접속 (22번을 안 열고 들어간다)"
  value       = "aws ssm start-session --target ${aws_instance.app.id}"
}

output "deployer_role_arn" {
  description = "GitHub Actions 워크플로의 role-to-assume 에 넣는 값"
  value       = aws_iam_role.deployer.arn
}

output "ssm_prefix" {
  description = "deploy_ec2.sh 의 SSM_PREFIX"
  value       = local.ssm_prefix
}
