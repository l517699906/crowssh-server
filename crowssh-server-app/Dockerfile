# Java 17 运行时镜像
ARG JAVA_BASE_IMAGE=docker.m.daocloud.io/library/eclipse-temurin:17-jre-jammy
FROM ${JAVA_BASE_IMAGE}

LABEL org.opencontainers.image.authors="llf"

ENV PARAMS=""
ENV JAVA_OPTS=""

ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

WORKDIR /app
COPY target/ai-agent-scaffold-app.jar /app/crowssh-server.jar

EXPOSE 8091

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/crowssh-server.jar $PARAMS"]
