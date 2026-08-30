#!/bin/sh
# 443 을 세운다 — **인증서가 실제로 있을 때만.** 소유: 오준서
#
# ── 왜 조건부인가 — 첫 발급이 자기 자신을 막는다 ────────────────────────────
#
# Let's Encrypt HTTP-01 은 `http://<도메인>/.well-known/acme-challenge/…` 를 읽어서 발급한다.
# 그러려면 **:80 이 먼저 떠 있어야 한다.** 그런데 `ssl_certificate` 를 무조건 적어 두면
# 파일이 없는 첫 기동에서 nginx 가 설정 검사에서 죽고, 그러면 :80 도 안 뜬다 —
# 인증서를 받으려면 nginx 가 떠야 하는데 nginx 가 뜨려면 인증서가 필요한 상태가 된다.
#
# 그래서 인증서 유무를 **기동 때 보고** 443 블록을 만들지 말지 정한다. 없으면 :80 만 뜨고
# 챌린지 경로가 열려 있으니 발급이 된다. 발급 뒤 `docker compose restart web` 한 번이면
# 이 스크립트가 다시 돌아 443 이 선다.
#
# 자체서명 인증서를 미리 깔아 두는 방법도 있지만 안 쓴다 — 그러면 발급이 실패한 배포도
# **https 로는 떠 보여서** 브라우저 경고를 보기 전까지 성공처럼 읽힌다.
set -eu

TLS_CONF=/etc/nginx/conf.d/10-tls.conf
HOST="${SPHINX_PUBLIC_HOST:-}"
CERT_DIR="/etc/letsencrypt/live/$HOST"

# `$sphinx_tls` 는 :80 블록이 https 로 튕길지 정하는 값이다. 인증서가 없을 때 1 이 되면
# **아무 데도 못 가는 리다이렉트 고리**가 되므로 기본은 0 이고, 아래에서만 1 로 바꾼다.
if [ -n "$HOST" ] && [ -f "$CERT_DIR/fullchain.pem" ] && [ -f "$CERT_DIR/privkey.pem" ]; then
    cat > "$TLS_CONF" <<EOF
# 자동 생성 — docker-entrypoint.d/20-tls.sh. 손으로 고치지 않는다.
map \$host \$sphinx_tls { default 1; }

server {
    listen 443 ssl;
    http2 on;
    server_name $HOST;

    ssl_certificate     $CERT_DIR/fullchain.pem;
    ssl_certificate_key $CERT_DIR/privkey.pem;

    # 인증서 갱신은 certbot 컨테이너가 한다. nginx 는 갱신된 파일을 reload 때 다시 읽는다.
    ssl_session_cache   shared:SSL:10m;
    ssl_session_timeout 1d;
    ssl_protocols       TLSv1.2 TLSv1.3;

    # ❗HSTS 는 안 건다. 데모 중 인증서가 만료되거나 http 로 되돌려야 할 때, HSTS 를 한 번
    # 받은 브라우저는 **http 로 접속 자체를 거부한다** — 심사 자리에서 그 상태가 되면
    # 손쓸 방법이 없다. 대회가 끝나고 도메인이 굳으면 그때 건다.

    include /etc/nginx/snippets/app.conf;
}
EOF
    chmod 644 "$TLS_CONF"
    echo "TLS 켜짐 — https://$HOST (인증서: $CERT_DIR)"
else
    cat > "$TLS_CONF" <<'EOF'
# 자동 생성 — docker-entrypoint.d/20-tls.sh. 손으로 고치지 않는다.
# 인증서가 없어 443 을 세우지 않았다. :80 만 뜨고 ACME 챌린지 경로는 열려 있다.
map $host $sphinx_tls { default 0; }
EOF
    chmod 644 "$TLS_CONF"
    if [ -z "$HOST" ]; then
        echo "TLS 꺼짐 — SPHINX_PUBLIC_HOST 가 없다. :80 만 뜬다."
    else
        echo "TLS 꺼짐 — $CERT_DIR 에 인증서가 없다. :80 만 뜬다."
        echo "  발급: docker compose run --rm certbot  (docs/deployment.md §9)"
    fi
fi
