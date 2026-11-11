FROM maven:3.9-eclipse-temurin-21-alpine AS builder

ARG PROXY_ACTIVE=false
ARG PROXY_HOST=
ARG PROXY_PORT=
ARG PROXY_USER=
ARG PROXY_PASSWORD=

WORKDIR /build

COPY settings.xml /tmp/settings.xml
RUN if [ "$PROXY_ACTIVE" = "true" ]; then \
      sed -i \
        -e "s|\${env.PROXY_ACTIVE}|${PROXY_ACTIVE}|g" \
        -e "s|\${env.PROXY_HOST}|${PROXY_HOST}|g" \
        -e "s|\${env.PROXY_PORT}|${PROXY_PORT}|g" \
        -e "s|\${env.PROXY_USER}|${PROXY_USER}|g" \
        -e "s|\${env.PROXY_PASSWORD}|${PROXY_PASSWORD}|g" \
        /tmp/settings.xml && \
      mkdir -p /root/.m2 && \
      cp /tmp/settings.xml /root/.m2/settings.xml; \
    fi

COPY pom.xml .
COPY src src

# Maven repository kept between builds (BuildKit cache, not part of the image)
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn clean package -DskipTests -B -Dmaven.artifact.threads=1

FROM eclipse-temurin:21-jre-alpine

# HTTP_PROXY/HTTPS_PROXY are predefined build args: visible to RUN, not saved in the image
# Python venv isolado pros geradores do pacote de flash: NVS (ESP32) e LittleFS (ESP8266)
RUN apk add --no-cache python3 py3-pip py3-cryptography && \
    python3 -m venv --system-site-packages /opt/nvs-venv && \
    /opt/nvs-venv/bin/pip install --no-cache-dir esp-idf-nvs-partition-gen==0.3.0 littlefs-python==0.19.0

# Default do binário Python usado pelo FlashPackageService (sobrescrevível
# via env NVS_PYTHON_PATH). Aponta pro venv, não pro python3 do sistema.
ENV NVS_PYTHON_PATH=/opt/nvs-venv/bin/python3

RUN addgroup -S -g 10001 app && adduser -S -u 10001 -G app app
USER app
WORKDIR /app

COPY --from=builder /build/target/*.jar app.jar

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
