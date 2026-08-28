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
set -eu

: "${SPHINX_API_USER:?SPHINX_API_USER 가 없다 — SSM 에서 받아 compose 가 넣는다 (#41)}"
: "${SPHINX_API_PASSWORD:?SPHINX_API_PASSWORD 가 없다 — SSM 에서 받아 compose 가 넣는다 (#41)}"

# apr1 은 nginx 가 읽는 형식 중 alpine 에서 의존성 없이 만들 수 있는 것이다.
# 평문({PLAIN})도 nginx 가 읽지만 파일에 비밀번호가 그대로 남는다.
hash=$(openssl passwd -apr1 "$SPHINX_API_PASSWORD")
printf '%s:%s\n' "$SPHINX_API_USER" "$hash" > /etc/nginx/.htpasswd
chmod 640 /etc/nginx/.htpasswd
chown root:nginx /etc/nginx/.htpasswd

# 값은 안 찍는다. 길이만 남겨 "무엇이 들어갔는지" 대신 "들어갔는지"를 확인한다.
echo "htpasswd 생성: 사용자 ${SPHINX_API_USER} · 비밀번호 ${#SPHINX_API_PASSWORD}자"
