# 多阶段构建：Maven 构建 → JRE 运行
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml ./
COPY mvnw ./
COPY .mvn ./.mvn
RUN ./mvnw -q -B -Dmaven.repo.local=/root/.m2 dependency:go-offline || true
COPY src ./src
RUN ./mvnw -q -B package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
# 统一北京时区：服务端趋势图桶标签、日志时间戳、ZoneId.systemDefault 均为 Asia/Shanghai
ENV TZ=Asia/Shanghai
RUN addgroup --system app && adduser --system --ingroup app app
COPY --from=build /build/target/trace-log-service.jar app.jar
USER app
EXPOSE 8080
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
