# А4. Sequence — Путь транзакции (3 сценария)

## Чего не хватало в проекте

В исходной документации (`docs/architecture.md`) присутствовала только базовая mermaid sequence диаграмма happy path. Полностью отсутствовали:
1. Сценарий отказа при блокировке карты
2. Сценарий автоматического отката резервирования при сбое публикации в RabbitMQ (Publisher Confirms timeout), хотя этот критический механизм полностью реализован в коде `RouteService.java`.
3. Разметка синхронных REST-запросов и асинхронных вилок.

## Что сделано

Построены 3 детальные sequence-диаграммы, строго соответствующие реализации в сервисах:
1. **Happy path** - успешная авторизация, бронирование средств, подтверждение брокера, асинхронный лог
2. **Declined** - отклонение по статусу `BLOCKED`, ранний выход без резервирования и без публикации в очередь
3. **Reversal** - успешная авторизация -> таймаут подтверждения брокера RabbitMQ -> откат в Authorization и CMS -> возврат клиенту кода `96`.

---

## Сценарий 1: Happy Path

```mermaid
sequenceDiagram
    participant T as Terminal :8085
    participant GW as Gateway :8080
    participant SW as Switch :8082
    participant AUTH as Authorization :8083
    participant BIN as Bin-Lookup :8096
    participant CMS as Card-Management :8081
    participant PG as PostgreSQL :5432
    participant RMQ as RabbitMQ :5672
    participant LOG as Transaction-Logger :8088
    participant NOTIF as Notification-Service :8097

    Note over T,GW: 1. Синхронный клиентский запрос (HTTP)
    T->>GW: POST /api/transactions
    Note over GW: Rate-limiting, requestId injection
    GW->>SW: POST /api/internal/route

    Note over SW,AUTH: 2. Маршрутизация по BIN и вызов авторизации (HTTP)
    Note over SW: RoutingService: BIN 400000 -> ISS001
    SW->>AUTH: POST /api/internal/authorize

    Note over AUTH,CMS: 3. Валидация, лимиты и резервирование (HTTP + JDBC)
    AUTH->>CMS: GET /api/cards/{pan}
    CMS-->>AUTH: 200 OK
    AUTH->>BIN: GET /api/bin/{bin}
    BIN-->>AUTH: 200 OK
    AUTH->>PG: SQL UPSERT INTO limit_usage
    PG-->>AUTH: Updated rows = 1
    AUTH->>CMS: POST /api/cards/{pan}/reserve
    CMS->>PG: INSERT INTO card_reservations, UPDATE cards.available_balance
    CMS-->>AUTH: 200 OK
    Note over AUTH: generateRRN(), generateAuthCode()
    AUTH-->>SW: 200 OK

    Note over SW,RMQ: 4. Публикация транзакции с подтверждением брокера (AMQP)
    SW->>RMQ: Publish (exchange: smp.transactions, rk: transaction.log)
    RMQ-->>SW: Publisher Confirm ACK
    SW-->>GW: 200 OK
    GW-->>T: 200 OK

    Note over RMQ,LOG: 5. Асинхронная ветка: логирование транзакции
    RMQ->>LOG: Deliver
    LOG->>PG: INSERT INTO transactions
    LOG->>LOG: DefaultWebSocketManager.broadcast() (/ws/transactions)

    Note over CMS,NOTIF: 6. Асинхронная ветка: Transactional Outbox
    Note over CMS: OutboxEventProcessorImpl
    CMS->>RMQ: Publish (exchange: smp.card-events, rk: card.CardServiceReserveEvent)
    RMQ->>NOTIF: Deliver (queue: card-notifications)
    NOTIF->>PG: INSERT INTO card_notifications
```

## Сценарий 2: Declined

```mermaid
sequenceDiagram
    participant T as Terminal :8085
    participant GW as Gateway :8080
    participant SW as Switch :8082
    participant AUTH as Authorization :8083
    participant CMS as Card-Management :8081

    Note over T,GW: 1. Клиентский запрос
    T->>GW: POST /api/transactions (PAN карты со статусом BLOCKED)
    GW->>SW: POST /api/internal/route
    SW->>AUTH: POST /api/internal/authorize

    Note over AUTH,CMS: 2. Проверка состояния карты в CMS
    AUTH->>CMS: GET /api/cards/{pan}
    CMS-->>AUTH: 200 OK (CardModel: status=BLOCKED)

    Note over AUTH: AuthServiceImpl:<br/>currCardStatus == BLOCKED<br/>-> DeclineOutcome.CARD_BLOCKED (code="05", reason="CARD_BLOCKED")
    Note over AUTH: AuthControllerImpl:<br/>REASON_CARD_BLOCKED -> HTTP Status 403 Forbidden
    AUTH-->>SW: 403 Forbidden (AuthorizationResponse: status=DECLINED, responseCode="05")

    Note over SW: RouteService:<br/>response.status != APPROVED.<br/>Резервирование НЕ выполнялось.<br/>Публикация в RabbitMQ НЕ вызывается.
    SW-->>GW: 200 OK (AuthorizationResponse: DECLINED, code="05")
    GW-->>T: 200 OK (Терминал выводит отказ: Карта заблокирована)
```

## Сценарий 3: Reversal

```mermaid
sequenceDiagram
    participant T as Terminal :8085
    participant GW as Gateway :8080
    participant SW as Switch :8082
    participant AUTH as Authorization :8083
    participant CMS as Card-Management :8081
    participant PG as PostgreSQL :5432
    participant RMQ as RabbitMQ :5672

    Note over T,AUTH: 1. Успешная предварительная авторизация и резервирование средств
    T->>GW: POST /api/transactions
    GW->>SW: POST /api/internal/route
    SW->>AUTH: POST /api/internal/authorize
    AUTH->>CMS: POST /api/cards/{pan}/reserve (средства заблокированы)
    CMS-->>AUTH: 200 OK
    AUTH-->>SW: 200 OK

    Note over SW,RMQ: 2. Попытка отправки в RabbitMQ завершается сбоем
    SW->>RMQ: LoggerClient.log() -> convertAndSend
    Note over RMQ:  RabbitMQ недоступен / таймаут подтверждения (2000ms) / AmqpException
    RMQ--xSW: AmqpException / No ACK received within timeout

    Note over SW: RouteService:<br/>!logged && status == APPROVED<br/>-> Инициация отката резервирования

    Note over SW,CMS: 3. Вызов компенсирующей транзакции (Rollback)
    SW->>AUTH: POST /api/internal/rollback
    Note over AUTH: AuthServiceImpl: rollback()
    AUTH->>CMS: POST /api/cards/{pan}/rollback
    Note over CMS: CardServiceImpl: rollback()<br/>Снятие блокировки с available_balance,<br/>сохранение записи в card_rollbacks
    CMS->>PG: INSERT INTO card_rollbacks, UPDATE cards SET available_balance
    CMS-->>AUTH: 200 OK
    AUTH-->>SW: 200 OK

    Note over SW: Возврат системного отказа:<br/>AuthorizationResponse.systemError<br/>status="DECLINED", responseCode="96"
    SW-->>GW: 200 OK (AuthorizationResponse: DECLINED, code="96")
    GW-->>T: 200 OK (Клиент получает DECLINED с кодом 96)
```
