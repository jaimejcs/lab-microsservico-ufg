# Laboratório de microsserviços

Implementação em Java 21 e Spring Boot da especificação fornecida.

## Serviços e responsabilidades

| Serviço | Porta externa | Banco | Responsabilidade |
| --- | --- | --- | --- |
| Pedido Service | 8080 | `pedido-db` | Cria pedidos e acompanha seu estado. |
| Estoque Service | 8081 | `estoque-db` | Consulta, reserva e libera produtos. |
| Pagamento Service | — | `pagamento-db` | Processa eventos de pedido. |

Nenhum serviço usa o banco de outro. A integração de reserva é síncrona por REST; os demais passos são assíncronos por RabbitMQ.

```text
POST /pedidos
  -> Pedido Service --REST--> Estoque Service (reserva)
  -> Pedido Service --pedido.criado--> RabbitMQ --> Pagamento Service
  -> Pagamento Service --pagamento.processado--> RabbitMQ --> Pedido Service
  -> se rejeitado: Pedido Service --estoque.liberar--> RabbitMQ --> Estoque Service
```

As filas são duráveis: `pedido.criado`, `pagamento.processado` e `estoque.liberar`.
Cada reserva é identificada pelo `correlationId`; assim, reentrega de um evento de liberação não devolve estoque duas vezes.

## Executar

```bash
docker compose up --build
```

RabbitMQ Management: <http://localhost:15672> (`lab` / `lab`). O usuário `guest` só entra por localhost, então os containers usam um usuário próprio. Para trocar, defina `RABBITMQ_USER` e `RABBITMQ_PASSWORD` antes do `docker compose up`.

Interface web: <http://localhost:8080>. Ela permite consultar estoque, criar pedidos e acompanhar o status do pagamento.

## Endpoints

```bash
curl http://localhost:8081/produtos

curl -X PUT http://localhost:8081/produtos/1/reservar \
  -H 'Content-Type: application/json' \
  -d '{"quantidade":2}'

O header `X-Correlation-Id` é opcional. O Pedido Service o envia para que a mesma reserva não seja descontada duas vezes.

curl -X POST http://localhost:8080/pedidos \
  -H 'Content-Type: application/json' \
  -d '{"produtoId":1,"quantidade":2}'

curl http://localhost:8080/pedidos
curl http://localhost:8080/pedidos/1
```

## Testes da atividade

```bash
# Pagamento indisponível: pedidos e reserva continuam; a fila acumula mensagens.
docker compose stop pagamento-service

# Recuperação: mensagens pendentes são consumidas sem reenvio manual.
docker compose start pagamento-service

# Dois consumidores concorrentes.
docker compose up --scale pagamento-service=2

# Logs com correlationId.
docker compose logs pedido-service estoque-service pagamento-service
```

## Testes automatizados

Cada serviço possui testes unitários com JUnit 5 e Mockito, além de testes de controller com MockMvc. Eles não precisam de Docker, PostgreSQL ou RabbitMQ.

```bash
cd estoque-service && mvn test
cd ../pedido-service && mvn test
cd ../pagamento-service && mvn test
```

## Limite intencional para discussão

O envio ao RabbitMQ ocorre após a gravação do pedido, mas não há uma transação distribuída entre PostgreSQL e RabbitMQ. A solução de produção para a janela de falha entre persistir e publicar é o **Transactional Outbox Pattern**. A implementação atual privilegia a demonstração direta solicitada no laboratório; essa escolha deve ser confirmada antes de introduzir a tabela outbox e um publicador confiável.
