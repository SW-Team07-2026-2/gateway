# syntax=docker/dockerfile:1
# 빌드: docker buildx build --platform linux/arm64 -t <ecr>/indig-gateway:$(git rev-parse --short HEAD) --push .
#       태그는 커밋 SHA — latest 는 ArgoCD 가 변경을 감지하지 못합니다
FROM --platform=$BUILDPLATFORM gradle:8.10-jdk21 AS build
WORKDIR /app
COPY settings.gradle build.gradle gradle.properties* ./
COPY gradle ./gradle
RUN gradle dependencies --no-daemon || true          # 의존성 레이어 캐시
COPY src ./src
RUN gradle bootJar --no-daemon -x test

# 🔴 노드가 t4g (Graviton) — amd64 이미지는 exec format error 로 CrashLoop
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
USER app
EXPOSE 8080 8090
ENTRYPOINT ["java", \
  "-XX:MaxRAMPercentage=70", \
  "-XX:+UseSerialGC", \
  "-XX:MaxMetaspaceSize=128m", \
  "-Dfile.encoding=UTF-8", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
