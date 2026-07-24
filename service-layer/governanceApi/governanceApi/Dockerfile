FROM eclipse-temurin:21-jdk-alpine AS builder

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
COPY .mvn .mvn
COPY mvnw .
COPY src src

RUN chmod +x mvnw && ./mvnw clean package -DskipTests -B -Dmaven.artifact.threads=1

FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache python3 py3-pip py3-cryptography
RUN pip3 install --break-system-packages esp-idf-nvs-partition-gen 2>/dev/null \
    || pip3 install --break-system-packages "esptool>=4.7"

RUN addgroup -S app && adduser -S app -G app
USER app
WORKDIR /app

COPY --from=builder --chown=app:app /build/target/*.jar app.jar

ENTRYPOINT ["sh", "-c", "java $JAVA_TOOL_OPTIONS -jar /app/app.jar"]
