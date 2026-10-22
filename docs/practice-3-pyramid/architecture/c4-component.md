# А3. C4 Level 3 - Component (Authorization и Card-Management)

## Чего не хватало в проекте

Компонентный уровень (Level 3) в документации СМП полностью отсутст

## Что сделано

Построены подробные компонентные диаграммы C4 Level 3 для двух ключевых микросервисов ядра процессинга:
1. **Authorization Service** 
2. **Card-Management Service** 

---

## 1. Authorization Service - Диаграмма

```mermaid
graph TB
    subgraph auth["Authorization Service (:8083)"]
        ctrl["AuthControllerImpl<br/>POST /api/internal/authorize<br/>POST /api/internal/rollback"]
        svc["AuthServiceImpl<br/>authorize(), rollback(),<br/>checkAndUpdateLimits(), generateRRN()"]
        cmsClient["CardManagementClientImpl<br/>getCard(), reserve(), rollback()"]
        binClient["BinLookupClient<br/>getIssuerId()"]
        limRepo["LimitUsageRepository<br/>upsertLimitUsage(), fetchRrnBlock()"]
        notifier["AuthorizationEventNotifierImpl<br/>publish metrics & audit events"]
        outcome["DeclineOutcome <br/>CARD_NOT_FOUND (14), CARD_BLOCKED (05),<br/>CARD_INACTIVE (05), CARD_EXPIRED (54),<br/>INSUFFICIENT_FUNDS (51), EXCEEDS_AMOUNT_LIMIT (61)"]
    end

    subgraph external_sync["Внешние REST-сервисы"]
        cms_ext["Card-Management Service :8081<br/>(/api/cards/**)"]
        bin_ext["Bin-Lookup Service :8096<br/>(/api/bin/{bin})"]
    end

    subgraph infra["Инфраструктура"]
        pg["PostgreSQL :5432<br/>(таблицы limit_usage, sequence rrn)"]
    end

    ctrl -- "1. authorize(request, inputTime)<br/>2. rollback(request, inputTime)" --> svc
    svc -- "Шаг 1: getCard(pan)" --> cmsClient
    svc -- "Шаг 2: getIssuerId(pan)" --> binClient
    svc -- "Шаг 3: проверка status, expiry, balance" --> outcome
    svc -- "Шаг 4: checkAndUpdateLimits()" --> limRepo
    svc -- "Шаг 5: reserve(amount, rrn, pan)<br/>(при откате: rollback())" --> cmsClient
    svc -- "аудит событий" --> notifier

    cmsClient -- "HTTP GET /api/cards/{pan}<br/>HTTP POST /api/cards/{pan}/reserve<br/>HTTP POST /api/cards/{pan}/rollback" --> cms_ext
    binClient -- "HTTP GET /api/bin/{bin}" --> bin_ext
    limRepo -- "JDBC: атомарный SQL UPSERT limit_usage" --> pg

    style ctrl fill:#bbdefb,stroke:#1565c0,stroke-width:2px
    style svc fill:#c8e6c9,stroke:#2e7d32,stroke-width:2px
    style cmsClient fill:#fff9c4,stroke:#fbc02d,stroke-width:2px
    style outcome fill:#ffecb3,stroke:#f57f17,stroke-width:2px
    style limRepo fill:#d1c4e9,stroke:#512da8,stroke-width:2px
```

## 2. Card-Management Service — Component Diagram

```mermaid
graph TB
    subgraph cms["Card-Management Service (:8081)"]
        cardCtrl["CardController<br/>CRUD /api/cards/**<br/>POST /reserve, POST /rollback"]
        cardSvc["CardServiceImpl<br/>createCard(), getCard(), patchCard(),<br/>deleteCard(), reserve(), rollback()"]
        cardRepo["CardRepository<br/>findByPan(), create(), update()"]
        resRepo["ReservationRepository<br/>save(), findByRrnAndPanForUpdate()"]
        rollRepo["ReservationRollbackRepository<br/>save()"]
        outboxRepo["OutboxRepository / OutboxJpaAdapter<br/>save(), findUnprocessedEvents()"]
        outboxProc["OutboxEventProcessorImpl<br/>processSingleEvent() → RabbitTemplate"]
        cardStatus["CardStatus <br/>ACTIVE, INACTIVE, BLOCKED,<br/>EXPIRED, DELETED"]
    end

    subgraph infra["Инфраструктура"]
        pg["PostgreSQL :5432<br/>(таблицы: cards, card_reservations,<br/>card_rollbacks, outbox_event)"]
        rmq["RabbitMQ :5672<br/>Exchange: smp.card-events"]
    end

    subgraph downstream["Подписчики"]
        notif["Notification-Service :8097<br/>Queue: card-notifications"]
    end

    cardCtrl -- "1. CRUD запросы<br/>2. reserve(pan, amount, rrn)<br/>3. rollback(pan, amount, rrn)" --> cardSvc
    cardSvc -- "CRUD операции с картами" --> cardRepo
    cardSvc -- "проверка уникальности и сохранение резерва" --> resRepo
    cardSvc -- "сохранение факта отката" --> rollRepo
    cardSvc -- "запись доменного события<br/>в той же БД-транзакции" --> outboxRepo
    cardSvc -- "валидация статусов" --> cardStatus

    outboxProc -- "вычитка непубликованных событий" --> outboxRepo
    outboxProc -- "публикация события" --> rmq

    cardRepo -- "JDBC" --> pg
    resRepo -- "JDBC" --> pg
    rollRepo -- "JDBC" --> pg
    outboxRepo -- "JDBC" --> pg

    rmq -. "AMQP: доставка сообщений" .-> notif

    style cardCtrl fill:#bbdefb,stroke:#1565c0,stroke-width:2px
    style cardSvc fill:#c8e6c9,stroke:#2e7d32,stroke-width:2px
    style outboxProc fill:#fff3e0,stroke:#e65100,stroke-width:2px
    style cardStatus fill:#ffecb3,stroke:#f57f17,stroke-width:2px
```