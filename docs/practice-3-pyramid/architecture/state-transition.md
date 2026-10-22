# А6. State-transition — Диаграммы состояний

## Чего не хватало в проекте

В исходной документации отсутствовали диаграммы состояний и таблицы переходов:
не были формализованы допустимые и запрещенные переходы статусов карт, не описан жизненный цикл транзакции от инициализации до терминальных состояний

## Что сделано

Построены две диаграммы переходов состояний:
1. **Жизненный цикл карты**: enum `CardStatus` (`ACTIVE`, `INACTIVE`, `BLOCKED`, `EXPIRED`, `DELETED`).
2. **Жизненный цикл транзакции**: модель `TransactionStatus` (`APPROVED`, `DECLINED`), причины отказа `DeclineOutcome` и механизм роллбэка

---

## 1. Card.status — Жизненный цикл карты

```mermaid
stateDiagram-v2
    [*] --> ACTIVE : POST /api/cards <br/>CardServiceImpl.createCard()

    ACTIVE --> INACTIVE : PATCH /api/cards/{pan} (status=INACTIVE)<br/>CardServiceImpl.patchCard()
    INACTIVE --> ACTIVE : PATCH /api/cards/{pan} (status=ACTIVE)<br/>CardServiceImpl.patchCard()

    ACTIVE --> BLOCKED : PATCH /api/cards/{pan} (status=BLOCKED)
    INACTIVE --> BLOCKED : PATCH /api/cards/{pan} (status=BLOCKED)

    BLOCKED --> ACTIVE : PATCH /api/cards/{pan} (status=ACTIVE)

    ACTIVE --> EXPIRED : Наступление даты expiryDate

    ACTIVE --> DELETED : DELETE /api/cards/{pan}<br/>CardServiceImpl.deleteCard()
    INACTIVE --> DELETED : DELETE /api/cards/{pan}
    BLOCKED --> DELETED : DELETE /api/cards/{pan}
    EXPIRED --> DELETED : DELETE /api/cards/{pan}

    DELETED --> [*]

    note right of ACTIVE
        Транзакции разрешены
    end note

    note right of INACTIVE
        Транзакции отклоняются
    end note

    note right of BLOCKED
        Транзакции отклоняются
    end note

    note right of EXPIRED
        Транзакции отклоняются
    end note

    note right of DELETED
        Карта скрыта из поиска
        CMS возвращает 404 -> code 14 (CARD_NOT_FOUND)
    end note
```

## 2. Transaction — Жизненный цикл транзакции

```mermaid
stateDiagram-v2
    [*] --> PROCESSING : POST /api/transactions<br/>Gateway -> Switch.route()

    PROCESSING --> APPROVED : Все проверки пройдены
    PROCESSING --> DECLINED : Ошибка валидации / лимитов / статуса<br/>code 05, 14, 51, 54, 61, 96

    APPROVED --> ROLLED_BACK : Сбой публикации в брокер <br/>Клиент получает DECLINED, code 96

    APPROVED --> [*] : Успешно завершена
    DECLINED --> [*] : Отклонена
    ROLLED_BACK --> [*] : Скомпенсирована
```
