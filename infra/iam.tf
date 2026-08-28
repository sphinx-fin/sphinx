data "aws_caller_identity" "me" {}

# ── ① EC2 인스턴스 역할 — docs/deployment.md §4.2 ───────────────────────────
#
# 요점은 "키를 인스턴스에 복사하지 않는다"이다. 액세스 키를 EC2 에 두는 방식으로
# 대체하지 않는다.
resource "aws_iam_role" "instance" {
  name = "${local.name}-instance"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

# Session Manager(셸)와 Run Command(CD)가 여기서 나온다. 이게 붙어 있어서 22번을
# 안 열어도 되고, GitHub Actions 가 인바운드 없이 배포를 걸 수 있다.
resource "aws_iam_role_policy_attachment" "ssm_core" {
  role       = aws_iam_role.instance.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_role_policy" "instance_secrets" {
  name = "read-secrets"
  role = aws_iam_role.instance.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["ssm:GetParameter", "ssm:GetParameters"]
        Resource = "arn:aws:ssm:${var.region}:${data.aws_caller_identity.me.account_id}:parameter${local.ssm_prefix}/*"
      },
      {
        # CD 가 올려둔 커밋 스냅샷을 받아 온다. 이 환경의 접두어 아래만 읽는다.
        Effect   = "Allow"
        Action   = ["s3:GetObject"]
        Resource = "arn:aws:s3:::${local.artifacts_bucket}/${local.env}/*"
      },
      {
        # 문서 §4.2 는 Resource "*" 로 적었는데, ViaService 조건을 걸면 같은 값을
        # 주면서 "SSM 을 통한 복호화만" 으로 좁힐 수 있다. 이 키로 다른 서비스의
        # 암호문을 푸는 경로가 사라진다.
        Effect   = "Allow"
        Action   = ["kms:Decrypt"]
        Resource = "*"
        Condition = {
          StringEquals = { "kms:ViaService" = "ssm.${var.region}.amazonaws.com" }
        }
      },
    ]
  })
}

resource "aws_iam_instance_profile" "instance" {
  name = "${local.name}-instance"
  role = aws_iam_role.instance.name
}

# ── ② GitHub Actions 역할 (CD) ──────────────────────────────────────────────
#
# OIDC 라 GitHub Secrets 에 장기 액세스 키를 두지 않는다. 워크플로가 실행될 때마다
# 15분짜리 임시 자격증명을 받는다.
#
# OIDC 공급자는 **계정에 하나뿐인 부트스트랩 리소스**라 여기서 만들지 않는다
# (alpha·prod 워크스페이스가 각자 만들려 들면 두 번째가 EntityAlreadyExists 로 죽는다).
# infra/bootstrap.sh 가 만들고 여기서는 참조만 한다.
data "aws_iam_openid_connect_provider" "github" {
  url = "https://token.actions.githubusercontent.com"
}

resource "aws_iam_role" "deployer" {
  name = "${local.name}-deployer"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Federated = data.aws_iam_openid_connect_provider.github.arn }
      Action    = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = {
          "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com"
        }
        # ❗sub 를 안 좁히면 **아무 레포의 아무 브랜치**가 이 역할을 가져간다.
        # 환경별로 다른 ref 만 받는다 — locals.env_cfg 참조.
        StringLike = {
          "token.actions.githubusercontent.com:sub" = local.cfg.oidc_subs
        }
      }
    }]
  })
}

resource "aws_iam_role_policy" "deployer" {
  name = "deploy-via-ssm"
  role = aws_iam_role.deployer.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        # 이 인스턴스 하나에만, 셸 실행 문서로만. 계정의 다른 인스턴스에는 못 건다.
        Effect = "Allow"
        Action = ["ssm:SendCommand"]
        Resource = [
          "arn:aws:ec2:${var.region}:${data.aws_caller_identity.me.account_id}:instance/${aws_instance.app.id}",
          "arn:aws:ssm:${var.region}::document/AWS-RunShellScript",
        ]
      },
      {
        # 커밋 스냅샷을 올린다. 이 환경의 접두어 아래만, 쓰기만.
        Effect   = "Allow"
        Action   = ["s3:PutObject"]
        Resource = "arn:aws:s3:::${local.artifacts_bucket}/${local.env}/*"
      },
      {
        # 보낸 명령의 결과를 폴링해서 실패면 워크플로를 빨갛게 만든다.
        Effect = "Allow"
        Action = [
          # 태그로 인스턴스를 찾는다. Describe 계열은 리소스 단위 제한을 지원하지 않아
          # Resource "*" 가 강제된다 — 읽기 전용이라 받아들인다.
          "ec2:DescribeInstances",
          "ssm:GetCommandInvocation",
          "ssm:ListCommandInvocations",
          "ssm:ListCommands",
          "ssm:DescribeInstanceInformation",
        ]
        Resource = "*"
      },
    ]
  })
}
