output "env" {
  description = "이 state 가 관리하는 환경 (= 워크스페이스 이름)"
  value       = local.env
}

output "url" {
  description = "화면 주소"
  value       = "http://${aws_eip.app.public_ip}/"
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
