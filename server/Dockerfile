# server (Spring Boot) 이미지. 초안 작성: 오준서 (인프라 R) — **검토: 강희진**
#
# 이슈 #37 이 "각자 Dockerfile 을 만들고 오준서가 compose 로 묶는다"로 정했는데, 9/3 리허설
# 전에 compose 를 세우려면 세 이미지가 다 있어야 해서 초안까지 냈다. 이 파일은 server/ 소유자
# 것이므로 머지 전에 봐 주기 바란다 — 특히 JVM 옵션과 힙 설정은 내 판단 밖이다.
#
# data/ 를 이미지에 넣지 않는다. 읽기 전용 볼륨으로 마운트한다(#37 · compose 참조).

# ── 빌드 ────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src

# 래퍼와 빌드 스크립트를 먼저 넣어 의존성 해석 레이어를 소스와 분리한다 — 소스만 바뀌면
# 여기까지는 캐시가 산다.
COPY gradlew ./
COPY gradle gradle
COPY settings.gradle build.gradle ./
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies --quiet || true

COPY src src
# 테스트는 CI 가 돌린다(.github/workflows/ci.yml). 여기서 또 돌리면 이미지 빌드가 몇 분씩
# 길어지는데, 그 시간에 잡히는 것은 CI 가 이미 잡은 것뿐이다.
# ❗ 다만 이건 "CI 가 돈다"는 전제 위에 서 있다 — CI 를 끄면 검증 없이 배포되는 경로가 된다.
RUN ./gradlew --no-daemon bootJar -x test

# ── 실행 ────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre
WORKDIR /app

# compose 의 healthcheck 가 curl 을 쓴다. temurin 런타임 이미지에는 안 들어 있다.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# root 로 돌리지 않는다.
#
# ❗**gid 를 명시한다.** `useradd --system` 만 쓰면 그룹 id 를 베이스 이미지가 그때 남아
# 있는 시스템 범위에서 고른다(`eclipse-temurin:17-jre` 에서는 999 였다). 그 값이 배포
# 스크립트의 `chown` 과 복구 명령에 적히므로, 베이스가 바뀌는 날 **문서가 조용히 틀린
# 그룹을 지정한다.** 여기서 못 박으면 적힌 값이 구조적으로 참이다(PR #532 리뷰 ⑩).
RUN groupadd --system --gid 10001 sphinx \
    && useradd --system --uid 10001 --gid 10001 --create-home sphinx

# ❗**`/data/uploads` 를 이미지에 굽지 않는다**(F-EXT-001 · 이슈 #521 · PR #532 리뷰 ⑪).
#
# 업로드 볼륨은 이 스택의 첫 rw 마운트라 쓰기 권한이 필요하고, `docker volume create` 는
# 볼륨 루트를 `root:root` 로 만든다. 그 소유권을 **이미지에 구워서** 해결할 수도 있었다 —
# Docker 가 빈 볼륨에 이미지의 소유권을 복사하니까. 그렇게 하지 않는 이유가 둘이다.
#
# ❗**1. copy-up 은 일회성이다.** 볼륨 루트가 비었을 때만 돈다. 무엇이든 한 번 들어간 뒤
#    root 소유로 굳으면 그 뒤로는 영원히 no-op 이고 **모든 업로드가 EACCES 500** 인데,
#    배포는 그것에 대해 아무 말도 안 한다. 그래서 소유권은 배포 스크립트가 **멱등하게**
#    보장한다(`deploy_ec2.sh` 의 `chown 10001:10001`) — 항상 옳고, 정확성이 데몬의
#    암묵적 부작용에서 벗어난다.
#
# ❗**2. 구우면 「볼륨 안 붙음」이 조용한 데이터 손실이 된다.** 디버그용 `docker run` ·
#    마운트를 뺀 compose 변형에서 업로드가 **성공해서 컨테이너 쓰기 레이어에 쌓이고 다음
#    재생성에 사라진다.** 굽지 않으면 uid 10001 이 `/data/uploads` 를 만들다 그 자리에서
#    시끄럽게 실패한다(실측: `mkdir: Permission denied`).
#
USER sphinx

COPY --from=build /src/build/libs/*.jar /app/app.jar

EXPOSE 8000

# 컨테이너 메모리 한도를 JVM 이 따라가게 둔다(기본값이지만 명시한다 — EC2 인스턴스가 작아
# 힙이 호스트 전체 기준으로 잡히면 OOM 킬이 난다).
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
