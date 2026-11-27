# syntax=docker/dockerfile:1
# 빌드: docker buildx build --platform linux/amd64,linux/arm64 -t <ecr>/indig-gateway:$(git rev-parse --short HEAD) --push .
# 태그는 커밋 SHA - latest 는 ArgoCD 가 변경을 감지하지 못합니다
FROM --platform=$BUILDPLATFORM gradle:8.10-jdk21 AS build
WORKDIR /app
COPY settings.gradle build.gradle gradle.properties* ./
COPY gradle ./gradle
RUN gradle dependencies --no-daemon || true          # 의존성 레이어 캐시
COPY src ./src
RUN gradle bootJar --no-daemon -x test

# amd64, arm64 둘 다 올립니다 (AWS 노드는 t4g, 온프레미스는 Intel PC) - 노드와 다른 아키텍처만 있으면 exec format error 로 CrashLoop
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
USER app
EXPOSE 8080 8090
# k6 3,000명 - SerialGC(GC 스레드 1개)는 멈출 때마다 Netty 이벤트 루프 전체가 같이 멈춥니다 (GC 91번, 합 10.7초, 최대 472ms, 상태 점검 시간 초과)
# -> ParallelGC: 멈춤은 그대로 전체지만 CPU 한도만큼 스레드로 나눠 짧게. CPU 한도 1코어(AWS)면 SerialGC 와 같습니다
# GC 는 JAVA_TOOL_OPTIONS 로 못 바꿉니다 - 여기 플래그가 뒤에 와서 두 GC 가 함께 켜져 기동 실패
ENTRYPOINT ["java", \
  "-XX:MaxRAMPercentage=70", \
  "-XX:+UseParallelGC", \
  "-XX:MaxMetaspaceSize=128m", \
  "-Dfile.encoding=UTF-8", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
