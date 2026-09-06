#!/bin/sh
# blue/green 업스트림 기본값을 최초 1회만 써 넣는다. 소유: 오준서
#
# `/etc/nginx/upstream` 은 named volume(`nginx-upstream`, docker-compose.edge.yml)
# 이다 — 컨테이너가 재생성돼도(예: 프론트 코드 변경 배포) "지금 라이브인 색"이
# 살아남아야 하기 때문이다. 파일 하나만 골라 볼륨을 마운트하는 방법도 시도했지만
# (`/etc/nginx/conf.d/00-upstream.conf` 에 파일 마운트) 컨테이너 런타임이 "디렉토리를
# 파일에 마운트하려 한다"며 기동을 거부했다 — named volume 은 항상 디렉토리로
# 취급된다. 그래서 디렉토리(`/etc/nginx/upstream/`)를 통째로 마운트하고, 그 안의
# 파일 하나(`active.conf`)를 이 스크립트가 **없을 때만** 채운다.
#
# ❗**"없을 때만" 이 핵심이다.** 이미 있으면(배포가 컷오버로 써 둔 값) 손대지 않는다 —
# 여기서 매번 blue 로 덮어쓰면 컨테이너가 재생성될 때마다 라이브 색이 blue 로
# 리셋되고, 그건 green 이 라이브인 상태에서 프론트만 배포해도 API 가 죽는다는 뜻이다.
#
# ❗**정적 `upstream {}` 블록이 아니라 `set` 한 줄이다.** 처음엔
# `upstream app_backend { server server-blue:8000; }` 로 썼는데, 정적 upstream 의
# 서버 이름은 nginx 기동·reload 시점에 DNS 를 한 번 풀고 실패하면(`emerg: host not
# found in upstream`) **nginx 가 아예 안 뜬다** — blue 스택이 아직 없는 최초 배포에서
# 실측(크래시루프)으로 잡았다. `set $sphinx_backend "…";` + `resolver`(web/app.conf)
# 조합은 DNS 조회를 요청마다 하므로, 대상이 없어도 nginx 는 뜨고 그 요청만 502 다.
set -eu

UPSTREAM_CONF=/etc/nginx/upstream/active.conf

if [ ! -f "$UPSTREAM_CONF" ]; then
    printf 'set $sphinx_backend http://server-blue:8000;\n' > "$UPSTREAM_CONF"
    echo "업스트림 기본값 생성 — server-blue (최초 기동)"
else
    echo "업스트림 기존 값 유지 — $(cat "$UPSTREAM_CONF")"
fi
