# А5. RabbitMQ-топология

## Чего не хватало в проекте

В исходной документации архитектуры описание очередей ограничивалось текстовой таблицей:
не было наглядной схемы топологии брокера с отображением типов обменников, биндингов, DLX/DLQ, параметров подтверждения доставки , политик повторной обработки и TTL сообщений

## Что сделано

Построена детальная визуальная диаграмма RabbitMQ-топологии СМП, точно отражающая Java-конфигурации классов `RabbitMQConfig` в сервисах `switch`, `card-management`, `transaction-logger` и `notification-service`.

## Диаграмма

```mermaid
graph LR
    subgraph producers["Продюсеры"]
        sw["Switch Service"]
        outbox["Card-Management Service"]
    end

    subgraph exchanges["Обменники"]
        ex_tx["smp.transactions"]
        ex_card["smp.card-events"]
    end

    subgraph queues["Очереди"]
        q_log["transaction-log<br/>x-max-delivery: 3<br/>x-dead-letter-exchange: smp.transactions.dlx"]
        q_notif["card-notifications<br/>x-max-delivery: 3<br/>x-dead-letter-exchange: smp.card-events.dlx"]
    end

    subgraph dlx["DLX"]
        dlx_tx["smp.transactions.dlx"]
        dlx_card["smp.card-events.dlx"]
    end

    subgraph dlq["DLQ"]
        dlq_log["transaction-log-dlq<br/>x-message-ttl: 60000ms"]
        dlq_notif["card-notifications-dlq<br/>x-message-ttl: 60000ms"]
    end

    subgraph consumers["Консюмеры"]
        logger["Transaction-Logge"]
        notif["Notification-Service"]
    end

    sw -- "Publish<br/>(routing key: transaction.log)<br/>publisher-confirm=correlated, 2s" --> ex_tx
    ex_tx -- "Binding: transaction.log" --> q_log
    q_log -- "Deliver (@RabbitListener)" --> logger

    outbox -- "Publish<br/>(routing key: card.<EventClass>)" --> ex_card
    ex_card -- "Binding pattern: card.*" --> q_notif
    q_notif -- "Deliver (@RabbitListener)" --> notif

    q_log -. "Отказ после 3 доставок<br/>(x-dead-letter-routing-key: transaction-log)" .-> dlx_tx
    dlx_tx -. "Binding: transaction-log" .-> dlq_log

    q_notif -. "Отказ после 3 доставок<br/>(x-dead-letter-routing-key: card-notifications)" .-> dlx_card
    dlx_card -. "Binding: card-notifications" .-> dlq_notif

    style ex_tx fill:#fff3e0,stroke:#e65100,stroke-width:2px
    style ex_card fill:#fff3e0,stroke:#e65100,stroke-width:2px
    style dlx_tx fill:#ffebee,stroke:#c62828,stroke-width:2px
    style dlx_card fill:#ffebee,stroke:#c62828,stroke-width:2px
    style dlq_log fill:#ffebee,stroke:#c62828,stroke-width:2px
    style dlq_notif fill:#ffebee,stroke:#c62828,stroke-width:2px
```
