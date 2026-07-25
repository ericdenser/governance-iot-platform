FROM maven:3.9-eclipse-temurin-21-alpine AS builder

WORKDIR /build

COPY settings.xml /root/.m2/settings.xml

ARG PROXY_ACTIVE=false
ARG PROXY_HOST=
ARG PROXY_PORT=
ARG PROXY_USER=
ARG PROXY_PASSWORD=
ENV PROXY_ACTIVE=${PROXY_ACTIVE} \
    PROXY_HOST=${PROXY_HOST} \
    PROXY_PORT=${PROXY_PORT} \
    PROXY_USER=${PROXY_USER} \
    PROXY_PASSWORD=${PROXY_PASSWORD}

COPY pom.xml .
COPY src src

RUN mvn clean package -DskipTests -B -Dmaven.artifact.threads=1

FROM eclipse-temurin:21-jre-alpine

ARG HTTP_PROXY=
ARG HTTPS_PROXY=
ENV HTTP_PROXY=${HTTP_PROXY} HTTPS_PROXY=${HTTPS_PROXY} NO_PROXY=localhost,127.0.0.1

RUN apk add --no-cache python3 py3-pip py3-cryptography
RUN pip3 install --break-system-packages esp-idf-nvs-partition-gen 2>/dev/null \
    || pip3 install --break-system-packages "esptool>=4.7"

ENV HTTP_PROXY= HTTPS_PROXY=

RUN addgroup -S app && adduser -S app -G app
USER app
WORKDIR /app

COPY --from=builder --chown=app:app /build/target/*.jar app.jar

ENTRYPOINT ["sh", "-c", "java $JAVA_TOOL_OPTIONS -jar /app/app.jar"]
