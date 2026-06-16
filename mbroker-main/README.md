# Message Broker (mbroker)

Broker de mensagens de alto desempenho.

Requisitos básicos:
1. Publicação em container.
2. Footprint de memória pequeno.
3. Alto desempenho.
4. Baixa latência.
5. Mensagens binárias.


# Build e Deploy

Para o build do projeto, é necessário instalar três bibliotecas de sistema, sendo elas:

* **protobuf-compiler:** Necessário para a compilação dos arquivos .proto.
* **libprotobuf-dev:** Necessário para o desenvolvimento com protobuf.
* **sqlite3:** Necessário para o banco em memória para o armazenamento de mensagens do broker de mensagens.

Instalação do suporte ao protobuff

```bash
$ sudo apt install protobuf-compiler-grpc
```

Instalação do sqlite3

```bash
$ sudo apt install sqlite3
```

Compilar o projeto

```bash
$ cargo build
```

Rodar o projeto

```bash
$ cargo run
```

Executar testes unitários

```bash
$ cargo tests
```

# Documentação

## Messaging Service

```
syntax = "proto3";

package messaging;

service Messaging {
  rpc Subscribe(RegisterRequest) returns (stream Message);
  rpc SendMessage(Message) returns (MessageSentResponse);
}

message MessageSentResponse {
  string status = 1;
}

message RegisterRequest {
  string topic = 1;
}

message Message {
  string topic = 1;
  string from = 2;
  string message = 3;
}
```
