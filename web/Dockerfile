# web (Vite 정적 산출물 + nginx) 이미지. 소유: 오준서
#
# 배포에는 `vite dev` 가 뜨지 않는다. `vite build` 산출물(dist/)을 nginx 가 서빙하고,
# `/api` 를 server 로 넘기는 일은 vite.config.ts 의 프록시가 아니라 nginx.conf 가 한다
# (vite.config.ts 주석 · 결정로그 10.6).

# ── 빌드 ────────────────────────────────────────────────────────────────────
FROM node:20-alpine AS build
WORKDIR /src

# 락파일을 먼저 넣어 의존성 레이어를 소스와 분리한다.
COPY package.json package-lock.json ./
# npm ci 다. install 은 락을 갱신해 버려서 "이미지에서만 다른 버전"이 생긴다.
RUN npm ci

COPY . .
# `npm run build` 가 `tsc --noEmit && vite build` 다 — 타입 검사가 빌드의 일부라서
# 타입이 깨진 채로는 이미지가 만들어지지 않는다.
RUN npm run build

# ── 실행 ────────────────────────────────────────────────────────────────────
FROM nginx:1.27-alpine

COPY --from=build /src/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80
