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

# root 로 돌리지 않는다. 마운트가 전부 :ro 라 쓰기 권한이 필요 없다.
RUN useradd --system --uid 10001 --create-home sphinx
USER sphinx

COPY --from=build /src/build/libs/*.jar /app/app.jar

EXPOSE 8000

# 컨테이너 메모리 한도를 JVM 이 따라가게 둔다(기본값이지만 명시한다 — EC2 인스턴스가 작아
# 힙이 호스트 전체 기준으로 잡히면 OOM 킬이 난다).
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
