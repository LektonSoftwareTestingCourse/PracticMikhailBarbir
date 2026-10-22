# А2. C4 Level 2 - Container Diagram

## Чего не хватало в проекте

1. **Не были разделены синхронные и асинхронные коммуникации**: вызовы HTTP/REST, транзакции JDBC и сообщения AMQP рисовались одинаковыми стрелками.
2 **Несоответствие сервисов и портов реальной конфигурации `docker-compose.yaml` и `.env.example`**:
   - Порты в устаревшей документации не соответствовали реальным контейнерам (`Card-Management`: 8081, `Switch`: 8082, `Authorization`: 8083, `Merchant-Acquirer`: 8084, `Terminal-Simulator`: 8085, `Transaction-Logger`: 8088, `Bin-Lookup`: 8096, `Notification-Service`: 8097).

## Что сделано

Построена детальная Container-диаграмма C4 Level 2, сверенная с кодом всех сервисов, `docker-compose.yaml` и конфигурациями Spring Boot:
- Сплошные стрелки = синхронные вызовы (HTTP REST, JDBC к PostgreSQL).
- Пунктирные стрелки = асинхронное взаимодействие (AMQP через RabbitMQ).

## Диаграмма

```mermaid
graph TB
    subgraph external["Внешние клиенты и генераторы"]
        terminal["Terminal Simulator<br/>:8085"]
        merchSim["Merchant Acquirer<br/>:8084"]
        dashUser["Dashboard User"]
    end

    subgraph smp["СМП - Контейнеры микросервисной платформы"]
        gw["Gateway<br/>:8080"]
        sw["Switch<br/>:8082"]
        auth["Authorization<br/>:8083"]
        cms["Card-Management<br/>:8081"]
        bin["Bin-Lookup<br/>:8096"]
        tlog["Transaction-Logger<br/>:8088<"]
        notif["Notification-Service<br/>:8097"]
        dash["Dashboard<br/>:3000"]

        rmq["RabbitMQ<br/>:5672 / :15672"]
        pg["PostgreSQL<br/>:5432"]
    end

    terminal -- "HTTP POST /api/transactions" --> gw
    merchSim -- "HTTP POST /api/transactions<br/>GET /api/cards" --> gw
    dashUser -- "HTTP :3000" --> dash
    dash -- "HTTP REST через Gateway" --> gw

    gw -- "HTTP POST /api/internal/route" --> sw
    gw -- "HTTP /api/cards/**" --> cms
    gw -- "HTTP /api/transactions/**, /api/dashboard/**" --> tlog
    gw -- "HTTP /api/simulator/terminal/**" --> terminal
    gw -- "HTTP /api/simulator/merchant/**" --> merchSim

    %% Синхронная цепочка авторизации (HTTP REST)
    sw -- "HTTP POST /api/internal/authorize<br/>(reversal: POST /api/internal/rollback)" --> auth
    sw -- "HTTP GET /api/internal/merchants/fee" --> merchSim
    auth -- "HTTP GET /api/cards/{pan}<br/>POST /api/cards/{pan}/reserve<br/>(reversal: POST /api/cards/{pan}/rollback)" --> cms
    auth -- "HTTP GET /api/bin/{bin}" --> bin

    %% Синхронный доступ к БД (JDBC)
    cms -- "JDBC (cards, reservations, outbox_event)" --> pg
    auth -- "JDBC (limit_usage, sequence rrn)" --> pg
    tlog -- "JDBC (transactions)" --> pg
    notif -- "JDBC (card_notifications)" --> pg
    merchSim -- "JDBC (merchants, terminals, fees)" --> pg

    %% Асинхронные потоки через RabbitMQ (AMQP)
    sw -. "AMQP publish (Publisher Confirms, timeout 2s)<br/>Exchange: smp.transactions <br/>Routing Key: transaction.log" .-> rmq
    rmq -. "AMQP consume<br/>Queue: transaction-log<br/>(DLX: smp.transactions.dlx)" .-> tlog

    cms -. "AMQP publish (Outbox processor)<br/>Exchange: smp.card-events <br/>Routing Key: card.*" .-> rmq
    rmq -. "AMQP consume<br/>Queue: card-notifications<br/>(DLX: smp.card-events.dlx)" .-> notif

    style rmq fill:#fff3e0,stroke:#e65100,stroke-width:2px
    style pg fill:#e8f5e9,stroke:#2e7d32,stroke-width:2px
    style gw fill:#e1f5fe,stroke:#0288d1,stroke-width:2px
    style sw fill:#e8eaf6,stroke:#3f51b5,stroke-width:2px
    style auth fill:#f3e5f5,stroke:#7b1fa2,stroke-width:2px
    style cms fill:#ede7f6,stroke:#512da8,stroke-width:2px
```
