#!/bin/sh
# 화면을 잠글지 열지 — 기동 때 정한다. 소유: 오준서
#
# `snippets/app.conf` 는 두 모드에서 같은 문장으로 서고, 여기서 만드는 변수가 동작을
# 가른다. 설정 파일을 모드마다 따로 두면 **두 벌이 갈리고**, 어느 쪽이 배포에 올라갔는지가
# 파일을 봐서는 안 보인다.
#
#   $sphinx_auth_realm   "sphinx" → 로그인 요구  ·  off → auth_basic 끔
#   $sphinx_api_auth     $http_authorization    ·  경로에 맞는 데모 계정의 "Basic …"
#
# ── ❗개방 모드가 무엇인가 — 인증을 끄는 게 아니다 ──────────────────────────
#
# **nginx 가 대신 로그인해 준다.** Spring 의 prod 체인은 그대로 `anyRequest().authenticated()`
# 이고 `@PreAuthorize` 도 그대로 돈다. 그래서 화면이 실제로 **돌아간다.**
#
# 진짜로 인증만 끄면(`permitAll`) 익명은 역할이 없어서 `@PreAuthorize` 가 전부 거절한다 —
# 세션 생성도 면담도 403 이고, **로그인 창만 사라지고 앱은 더 안 되는 상태**가 된다.
# 그래서 그 길로 가지 않는다.
#
# ── ❗한 계정으로는 전 화면이 안 열린다 — 그래서 경로별로 가른다 ───────────
#
# `rbac_policy.yaml` 이 역할마다 다른 action 을 준다. 한 계정을 고정하면 그 계정의 권한
# 밖 화면이 403 이 되므로, **경로에 맞는 데모 계정**을 넣는다.
#
#   /api/dashboard/…                        COMPL  집계는 COMPL(org)·MGR(branch) 뿐이다
#   /api/signals/…                          COMPL  불공정영업 신호는 COMPL 뿐이다 (이슈 #263)
#   /api/sessions/{sid}/override/approve    MGR    요청자 ≠ 승인자 (ADR-002)
#   /api/products/documents · …/extract     ADMIN  등록·추출은 판매 라인에서 뗀다 (결정 10.36)
#   그 밖의 /api/…                          SELLER 세션 생성·면담·판정·리포트·상품 조회
#
# ❗**이 목록은 손으로 맞추지 않는다.** `DemoModeAccountMapTest` 가 rbac_policy.yaml 과
# 대조한다 — 실리는 계정의 역할이 그 경로 action 의 그랜트에 없으면 빨개진다. 그 대조가
# 없던 동안 `/api/signals`(#252)와 `/api/products/*`(#249)가 지도에 안 올라 alpha 에서만
# 403 이었다. 서버 테스트는 셋 다 초록이었다 — 이 층이 대조 밖이었다.
#
# mgr-01 과 seller-01 이 **같은 지점(BR-001)** 이라 `scope: branch` 가 실제로 성립한다 —
# 다른 지점이면 승인이 403 이다(`demo_accounts.yaml` 주석 참조).
#
# 대가는 하나 남는다: **감사 로그의 "누가" 가 이 세 계정으로 굳는다.** 브라우저가 아니라
# nginx 가 고른 값이라 "그 사람이 했다" 는 뜻이 아니다. `app.conf` 가 원래 금지해 둔
# 고정 헤더 주입이고, 개방 모드는 그 대가를 알고 치른다. 그래서 기본값은 **잠금**이고,
# 여는 것은 배포가 명시적으로 켠다(`SPHINX_DEMO_OPEN=1`). 조용히 열려 있으면 안 되므로
# 어느 계정으로 열렸는지 아래에서 매번 로그에 찍는다.
set -eu

MODE_CONF=/etc/nginx/conf.d/00-demo-mode.conf

# `map` 은 http 컨텍스트라야 한다. `conf.d/*.conf` 가 그 자리이고, 파일 이름이 `00-` 인
# 이유는 nginx 가 알파벳 순으로 읽어서 `default.conf` 보다 먼저 서기 때문이다.
# 잠금/개방을 고르는 소스 변수는 없으므로 `$host` 를 자리표시로 두고 `default` 만 준다.

if [ "${SPHINX_DEMO_OPEN:-0}" = "1" ]; then
    : "${SPHINX_API_PASSWORD:?개방 모드도 비밀번호가 필요하다 — Spring 은 그대로 인증한다}"

    seller="${SPHINX_DEMO_ACTOR:-seller-01}"
    compl="${SPHINX_DEMO_ACTOR_AGGREGATE:-compl-01}"
    mgr="${SPHINX_DEMO_ACTOR_APPROVER:-mgr-01}"
    admin="${SPHINX_DEMO_ACTOR_MANAGE:-admin-01}"

    # base64 는 76자마다 줄바꿈을 넣는다. 헤더 값에 개행이 들어가면 설정 파싱이 죽거나
    # 헤더가 잘리므로 지운다(busybox base64 는 `-w 0` 을 모른다).
    b64() { printf '%s:%s' "$1" "$SPHINX_API_PASSWORD" | base64 | tr -d '\n'; }

    cat > "$MODE_CONF" <<EOF
# 자동 생성 — docker-entrypoint.d/15-demo-mode.sh. 손으로 고치지 않는다.
# 개방 모드: auth_basic 을 끄고 경로에 맞는 데모 계정으로 nginx 가 대신 로그인한다.
map \$host \$sphinx_auth_realm { default off; }

# ❗**\$uri 로 고른다 — \$request_uri 가 아니다.** \$request_uri 는 정규화·디코딩 전
# 원문이라, 실제로 상류에 프록시되는 경로(\$uri)와 갈릴 수 있다. 갈리면 경로에 맞지 않는
# 계정이 실린다 — 실측: /api/%64ashboard/heatmap 은 상류에 /dashboard/heatmap 으로 가는데
# \$request_uri 로는 default 라 seller-01 이 실려 403 이고, /api/sessions/…/approve/../..
# 은 mgr-01 을 달고 임의 세션 경로로 간다(파일이 말하는 규칙과 도는 규칙이 갈린다).
# 앞쪽은 화면이 조용히 깨지고 뒤쪽은 선언과 어긋난다(PR #221 리뷰, 정세현 실측).
# 쿼리스트링은 계정 선택과 무관하므로 \$uri 로 잃는 것이 없다.
map \$uri \$sphinx_api_auth {
    default                                 "Basic $(b64 "$seller")";
    ~^/api/dashboard/                       "Basic $(b64 "$compl")";
    ~^/api/sessions/[^/]+/override/approve  "Basic $(b64 "$mgr")";
    ~^/api/signals/                         "Basic $(b64 "$compl")";
    ~^/api/products/(documents|[^/]+/extract)$  "Basic $(b64 "$admin")";
}
EOF
    chmod 640 "$MODE_CONF"

    echo "⚠ 개방 모드 — 로그인 없이 열린다. nginx 가 대신 로그인하므로 감사 로그의 '누가' 는"
    echo "  아래 계정으로 굳는다: 기본 ${seller} · 집계 ${compl} · 오버라이드 승인 ${mgr}"
    echo "  잠그려면 SPHINX_DEMO_OPEN 을 비우고 다시 띄운다."
else
    cat > "$MODE_CONF" <<'EOF'
# 자동 생성 — docker-entrypoint.d/15-demo-mode.sh. 손으로 고치지 않는다.
# 잠금 모드(기본): 브라우저가 보낸 자격증명을 그대로 넘긴다 — nginx 기본 동작과 같다.
map $host $sphinx_auth_realm { default "sphinx"; }
# 개방 모드와 소스 변수를 맞춰 둔다($uri). 여기서는 default 하나라 무엇으로 골라도
# 같지만, 두 모드가 다른 변수를 쓰면 개방 쪽만 고치는 날이 온다.
map $uri $sphinx_api_auth { default $http_authorization; }
EOF
    chmod 640 "$MODE_CONF"
    echo "잠금 모드 — auth_basic 이 걸린다(기본값)."
fi
