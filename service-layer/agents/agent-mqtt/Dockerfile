FROM maven:3.9-eclipse-temurin-21-alpine AS builder

ARG PROXY_ACTIVE=false
ARG PROXY_HOST=
ARG PROXY_PORT=
ARG PROXY_USER=
ARG PROXY_PASSWORD=

WORKDIR /build

COPY settings.xml /root/.m2/settings.xml
RUN sed -i \
    -e "s|\${env.PROXY_ACTIVE}|${PROXY_ACTIVE}|g" \
    -e "s|\${env.PROXY_HOST}|${PROXY_HOST}|g" \
    -e "s|\${env.PROXY_PORT}|${PROXY_PORT}|g" \
    -e "s|\${env.PROXY_USER}|${PROXY_USER}|g" \
    -e "s|\${env.PROXY_PASSWORD}|${PROXY_PASSWORD}|g" \
    /root/.m2/settings.xml

COPY pom.xml .
COPY src src

RUN mvn clean package -DskipTests -B -Dmaven.artifact.threads=1

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
USER app
WORKDIR /app
COPY --from=builder --chown=app:app /build/target/*.jar app.jar

ENTRYPOINT ["sh", "-c", "java $JAVA_TOOL_OPTIONS -jar /app/app.jar"]
