#!/bin/sh
# nginx 가 뜨기 전에 basic auth 자격증명 파일을 만든다. 소유: 오준서
#
# 공식 nginx 이미지의 엔트리포인트가 /docker-entrypoint.d/*.sh 를 실행한 뒤 nginx 를
# 띄운다. 그 엔트리포인트는 `set -e` 라 여기서 죽으면 컨테이너가 안 뜬다 — 의도한
# 것이다. 자격증명 없이 뜨면 화면은 열리는데 모든 API 가 401 이 되고, 그 사실을
# 데모 중에 알게 된다.
#
# ❗자격증명을 이미지에 굽지 않는다. 값은 SSM 에서 와서 환경변수로만 들어온다
# (scripts/deploy_ec2.sh · #41 ④). 그래서 빌드 때가 아니라 기동 때 만든다.
#
# ── 계정이 여럿인 이유 (이슈 #213 · #195 후속) ──────────────────────────────
#
# `auth_basic` 이 server 블록에 걸려 있어 **htpasswd 에 없는 id 는 Spring 까지 오지도
# 못한다.** 한 줄만 쓰던 때는 `SPHINX_API_USER=seller-01` 로 SELLER 차단(403)은 보였지만
# `compl-01` 은 로그인 창에서 401 로 끝났다 — Spring 은 명부(`demo_accounts.yaml`)의
# 7계정을 다 알고 있는데(`SecurityConfig.prodUsers`) 그 앞이 막혀서 **역할 분리 시연
# (ADR-001 · 기획 7-4)이 성립하지 않았다.**
#
# 비밀번호는 전 계정 공통이라(`prodUsers` 가 같은 해시를 쓴다) 해시는 한 번만 만든다.
# id 목록은 `SPHINX_API_USERS` 로 온다 — **명부에서 뽑아 넣는 쪽은 `scripts/deploy_ec2.sh`
# 다.** 명부는 server 리소스라 이 이미지 안에 없고, 목록을 여기에 적으면 계정 명부가
# 두 벌이 된다(그 둘이 갈리는 날 화면은 열리는데 API 가 401 이다).
set -eu

: "${SPHINX_API_USER:?SPHINX_API_USER 가 없다 — SSM 에서 받아 compose 가 넣는다 (#41)}"
: "${SPHINX_API_PASSWORD:?SPHINX_API_PASSWORD 가 없다 — SSM 에서 받아 compose 가 넣는다 (#41)}"

# 목록이 안 오면 예전과 같이 한 계정만 쓴다. 이 폴백이 있어야 `SPHINX_API_USERS` 를 아직
# 안 넘기는 경로(손으로 띄우는 compose 등)에서도 지금과 똑같이 동작한다 — 다만 그때는
# 역할 분리 시연이 안 된다는 것을 아래에서 경고로 남긴다.
users="${SPHINX_API_USERS:-}"

# apr1 은 nginx 가 읽는 형식 중 alpine 에서 의존성 없이 만들 수 있는 것이다.
# 평문({PLAIN})도 nginx 가 읽지만 파일에 비밀번호가 그대로 남는다.
hash=$(openssl passwd -apr1 "$SPHINX_API_PASSWORD")

# ❗`SPHINX_API_USER` 를 항상 먼저 넣는다. 목록 파싱이 어긋나 이 id 가 빠지는 날
# **아무도 못 들어오는** 배포가 뜬다 — 목록 쪽 실수가 기본 계정까지 끌고 가면 안 된다.
# (Spring 은 반대 방향을 이미 막는다: 이 id 가 명부에 없으면 기동을 거부한다.)
: > /etc/nginx/.htpasswd
written=""
for u in $SPHINX_API_USER $(echo "$users" | tr ',' ' '); do
    [ -n "$u" ] || continue
    # 같은 id 가 두 줄이면 nginx 는 먼저 만난 줄만 본다. 해시가 같아 동작은 같지만
    # 파일을 사람이 읽을 때 헷갈리므로 한 번만 쓴다.
    case " $written " in *" $u "*) continue ;; esac
    printf '%s:%s\n' "$u" "$hash" >> /etc/nginx/.htpasswd
    written="$written $u"
done

chmod 640 /etc/nginx/.htpasswd
chown root:nginx /etc/nginx/.htpasswd

# 비밀번호는 안 찍는다. 길이만 남겨 "무엇이 들어갔는지" 대신 "들어갔는지"를 확인한다.
# id 는 찍는다 — 명부(`demo_accounts.yaml`)가 레포에 있어 비밀이 아니고, 데모 중
# "이 계정으로 로그인이 왜 안 되지" 를 로그 한 줄로 가를 수 있어야 한다.
count=$(wc -l < /etc/nginx/.htpasswd | tr -d ' ')
echo "htpasswd 생성: ${count}계정 ·${written} · 비밀번호 ${#SPHINX_API_PASSWORD}자"

if [ -z "$users" ]; then
    echo "⚠ SPHINX_API_USERS 가 없어 ${SPHINX_API_USER} 하나만 만들었다." \
         "다른 역할은 nginx 에서 401 이라 역할 분리 시연(ADR-001)이 안 된다 —" \
         "배포라면 scripts/deploy_ec2.sh 로 띄운다 (#213)."
fi
