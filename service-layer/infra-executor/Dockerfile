
FROM docker:cli AS docker-cli

FROM golang:1.24-alpine

WORKDIR /app

# Injetamos o executável do Docker dentro do container
COPY --from=docker-cli /usr/local/bin/docker /usr/local/bin/docker

COPY go.mod ./
RUN go mod download

COPY . .

RUN go build -o infra-executor

EXPOSE 8089

CMD ["./infra-executor"]
