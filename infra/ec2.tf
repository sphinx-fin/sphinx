# AL2023 최신 AMI. AWS 가 공개 SSM 파라미터로 알려준다.
data "aws_ssm_parameter" "al2023" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64"
}

resource "aws_instance" "app" {
  ami           = data.aws_ssm_parameter.al2023.value
  instance_type = local.cfg.instance_type
  subnet_id     = sort(data.aws_subnets.default.ids)[0]

  vpc_security_group_ids = [aws_security_group.app.id]
  iam_instance_profile   = aws_iam_instance_profile.instance.name

  # ssh_cidrs 가 비어 있으면(기본) 키페어 없이 뜬다. 접속은 SSM Session Manager.
  key_name = var.key_name

  root_block_device {
    # 기본 8GB 로는 안 된다. Gradle 캐시 + node_modules + 이미지 레이어가 한 박스에
    # 쌓여서 빌드 중에 디스크가 찬다.
    volume_size = local.cfg.root_gb
    volume_type = "gp3"
    encrypted   = true
  }

  metadata_options {
    # IMDSv2 강제. 컨테이너가 뚫렸을 때 SSRF 로 인스턴스 자격증명을 긁어가는 경로를 막는다.
    http_tokens   = "required"
    http_endpoint = "enabled"
  }

  user_data = templatefile("${path.module}/templates/user_data.sh.tftpl", {
    app_dir = "/opt/sphinx"
  })

  # user_data_replace_on_change 를 켜지 않는다(기본 false). 켜면 부트스트랩 스크립트를
  # 한 줄 고칠 때마다 인스턴스가 통째로 갈리고 그때마다 재빌드에 10분 넘게 든다.
  # 대신 **user_data 수정은 이미 떠 있는 박스에 반영되지 않는다** — 반영하려면
  # tofu taint 로 일부러 다시 만든다.

  lifecycle {
    # AWS 가 새 AL2023 AMI 를 내면 data 값이 바뀐다. 이걸 안 무시하면 **관계없는
    # apply 한 번에 데모 박스가 재생성된다.** AMI 갱신은 일부러 taint 해서 한다.
    ignore_changes = [ami]
  }

  tags = { Name = local.name }
}

# 인스턴스를 stop/start 하면 퍼블릭 IP 가 바뀐다. 심사·팀에 넘긴 주소가 하루 만에
# 죽는 것을 막는다. 붙어 있는 동안은 무료다.
resource "aws_eip" "app" {
  domain = "vpc"
  tags   = { Name = local.name }
}

resource "aws_eip_association" "app" {
  instance_id   = aws_instance.app.id
  allocation_id = aws_eip.app.id
}
