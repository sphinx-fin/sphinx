#!/usr/bin/env bash
# IaC 밖에서 **한 번** 만드는 것들. 둘 다 "그것을 만드는 도구가 그것을 필요로 하는"
# 닭-달걀이거나 계정에 하나뿐인 싱글턴이라 워크스페이스 안에 둘 수 없다.
#
#   ① state 버킷 — tofu 가 state 를 넣을 곳. tofu 로는 못 만든다.
#   ② GitHub OIDC 공급자 — 계정당 하나. alpha·prod 가 각자 만들려 들면 두 번째가
#      EntityAlreadyExists 로 죽는다. 만들어 두고 data 로 참조한다.
#
# 여러 번 돌려도 안전하다(이미 있으면 넘어간다).
#
#   source ../aws-env.sh && ./infra/bootstrap.sh

set -euo pipefail

REGION="${AWS_REGION:-ap-northeast-2}"
ACCOUNT="$(aws sts get-caller-identity --query Account --output text)"
BUCKET="sphinx-tfstate-${ACCOUNT}"

echo "계정 ${ACCOUNT} · 리전 ${REGION}"

# ── ① state 버킷 ────────────────────────────────────────────────────────────
if aws s3api head-bucket --bucket "$BUCKET" >/dev/null 2>&1; then
  echo "  버킷 ${BUCKET} 있음"
else
  echo "  버킷 ${BUCKET} 만든다"
  aws s3api create-bucket --bucket "$BUCKET" --region "$REGION" \
    --create-bucket-configuration "LocationConstraint=${REGION}" >/dev/null
fi
# 버저닝은 state 를 되돌릴 유일한 수단이다. 잠금 실패로 state 가 깨졌을 때 이것만 남는다.
aws s3api put-bucket-versioning --bucket "$BUCKET" \
  --versioning-configuration Status=Enabled
aws s3api put-public-access-block --bucket "$BUCKET" \
  --public-access-block-configuration \
  BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
aws s3api put-bucket-encryption --bucket "$BUCKET" \
  --server-side-encryption-configuration \
  '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"}}]}'

# ── ② 아티팩트 버킷 ─────────────────────────────────────────────────────────
#
# CD 가 `git archive` 로 만든 커밋 스냅샷을 여기 올리고, 인스턴스가 받아 간다.
# 왜 박스에서 git clone 을 안 하는지는 infra/README.md §5 참조.
ART="sphinx-artifacts-${ACCOUNT}"
if aws s3api head-bucket --bucket "$ART" >/dev/null 2>&1; then
  echo "  버킷 ${ART} 있음"
else
  echo "  버킷 ${ART} 만든다"
  aws s3api create-bucket --bucket "$ART" --region "$REGION" \
    --create-bucket-configuration "LocationConstraint=${REGION}" >/dev/null
fi
aws s3api put-public-access-block --bucket "$ART" \
  --public-access-block-configuration \
  BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
aws s3api put-bucket-encryption --bucket "$ART" \
  --server-side-encryption-configuration \
  '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"}}]}'
# 배포마다 스냅샷이 하나씩 쌓인다. 되돌릴 일이 있어도 2주면 충분하고, 그 뒤로는
# 커밋 SHA 로 다시 만들 수 있으므로 보관할 이유가 없다.
aws s3api put-bucket-lifecycle-configuration --bucket "$ART" \
  --lifecycle-configuration '{"Rules":[{"ID":"expire","Status":"Enabled","Filter":{"Prefix":""},"Expiration":{"Days":14}}]}'

# ── ③ GitHub OIDC 공급자 ────────────────────────────────────────────────────
OIDC_ARN="arn:aws:iam::${ACCOUNT}:oidc-provider/token.actions.githubusercontent.com"
if aws iam get-open-id-connect-provider --open-id-connect-provider-arn "$OIDC_ARN" >/dev/null 2>&1; then
  echo "  OIDC 공급자 있음"
else
  echo "  OIDC 공급자 만든다"
  # thumbprint 는 AWS 가 잘 알려진 IdP 에 대해 자체 신뢰 저장소로 검증하므로 값 자체는
  # 이제 형식 요건에 가깝다. GitHub 이 공표한 값을 넣어 둔다.
  aws iam create-open-id-connect-provider \
    --url "https://token.actions.githubusercontent.com" \
    --client-id-list "sts.amazonaws.com" \
    --thumbprint-list "6938fd4d98bab03faadb97b34396831e3780aea1" \
                      "1c58a3a8518e8759bf075b76b750d4f2df264fcd" >/dev/null
fi

echo
echo "완료. 다음:"
echo "  tofu -chdir=infra init"
echo "  tofu -chdir=infra workspace new alpha   # 처음 한 번"
echo "  tofu -chdir=infra plan"
