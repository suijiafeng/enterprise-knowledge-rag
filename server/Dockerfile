# 知库服务端镜像：Maven 构建 → 只带 JRE 的运行镜像，以非 root 用户运行。
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package && cp target/*.jar /app.jar

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 zhiku
COPY --from=build /app.jar /opt/zhiku/app.jar
USER zhiku
# 容器内监听所有地址（application.yml 默认只听 127.0.0.1）；端口映射由 compose 限定在本机。
ENV SERVER_ADDRESS=0.0.0.0 JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=10 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080 && printf "GET /api/health HTTP/1.0\r\nHost: localhost\r\n\r\n" >&3 && head -1 <&3 | grep -q " 200 "'
ENTRYPOINT ["java", "-jar", "/opt/zhiku/app.jar"]
