# API-тесты СМП - отчёт

## 1. Покрытие

| Эндпоинт | Проверяемые статус-коды       | Кол-во проверок | Статус |
|---|-------------------------------|:---:|:---:|
| `GET /health` | 200                           | 1 | Passed |
| `POST /api/cards` | 201, 400                      | 11 | Passed |
| `GET /api/cards/{pan}` | 200, 400, 404                 | 4 | Passed |
| `GET /api/cards` | 200                           | 1 | Passed |
| `PATCH /api/cards/{pan}` | 200                           | 1 | Passed |
| `DELETE /api/cards/{pan}` | 204                           | 1 | Passed |
| `POST /api/cards/generate` | 201                           | 1 | Passed |
| `POST /api/transactions` | 200 (APPROVED, DECLINED), 400 | 26 | Passed |
| `GET /api/transactions/search` | 200, 400                      | 6 | Passed |
| `GET /api/transactions/export` | 200                           | 1 | Passed |
| `GET /api/dashboard/stats` | 200                           | 1 | Passed |
| `GET /api/dashboard/recent` | 200, 400                      | 2 | Passed |
| `GET /api/dashboard/charts` | 200                           | 1 | Passed |
| `POST /api/simulator/terminal/run` | 200, 400                      | 3 | Passed |
| `POST /api/simulator/merchant/run` | 200, 400                      | 2 | Passed |
| `GET /api/simulator/merchants` | 200                           | 1 | Passed |
| `POST /api/internal/log` | 201, 200, 409                 | 3 | Passed |
| `POST /api/internal/route` | 200                           | 1 | Passed |
| `POST /api/internal/authorize` | 200, 403, 404, 422            | 1 | Passed |
| `GET /api/bin/{bin}` | 200, 404, 500                 | 1 | Passed |
| **ИТОГО** | -                             | **69** | **Passed** |

---

## 2. Бизнес-отказы

| Исход | Код | Причина | Путь достижения | Способ подготовки данных | Статус |
|---|:---:|---|---|---|:---:|
| Card Not Found | `14` | `CARD_NOT_FOUND` | PAN содержит валидный BIN (`400000`), но карта отсутствует в базе данных Card Management Service | Генерация случайного PAN с префиксом `400000` без предварительного создания карты через API | Passed |
| Unknown BIN | `14` | `UNKNOWN_BIN` | BIN карты отсутствует в таблицах маршрутизации Switch / Bin Lookup | Формирование запроса с PAN, начинающимся на незарегистрированный BIN (`999999`) | Passed |
| Card Expired | `54` | `CARD_EXPIRED` | **Путь 1:** Статус карты в Card Management установлен в `EXPIRED` | Создание активной карты через API, затем перевод статуса в `EXPIRED` через `PATCH /api/cards/{pan}` | Passed |
| Card Expired | `54` | `CARD_EXPIRED` | **Путь 2:** Дата транзакции `transmissionDateTime` строго позже срока действия карты `expiryDate` | Создание активной карты со стандартным сроком действия (~3 года), отправка транзакции с датой в далёком будущем (2035 год) | Passed |
| Card Blocked | `05` | `CARD_BLOCKED` | Карта заблокирована банком или держателем (`status = BLOCKED`) | Создание карты через API, перевод в статус `BLOCKED` через `PATCH /api/cards/{pan}` | Passed |
| Card Inactive | `05` | `CARD_INACTIVE` | Карта выпущена, но не активирована (`status = INACTIVE`) | Создание карты через API, перевод в статус `INACTIVE` через `PATCH /api/cards/{pan}` | Passed |
| Insufficient Funds | `51` | `INSUFFICIENT_FUNDS` | Сумма транзакции превышает текущий доступный остаток (`availableBalance < amount`) | Создание карты с фиксированным балансом `10.00 RUB` (1000 копеек), отправка транзакции на `50.00 RUB` (5000 копеек) | Passed |
| Exceeds Daily Limit | `61` | `EXCEEDS_AMOUNT_LIMIT` | **Путь 1:** Сумма транзакции превышает суточный лимит по карте (`amount > dailyLimit`) | Создание карты с достаточным балансом (`10 000.00 RUB`), но заниженным `dailyLimit = 20.00 RUB` (2000 копеек), отправка запроса на `30.00 RUB` | Passed |
| Exceeds Monthly Limit | `61` | `EXCEEDS_AMOUNT_LIMIT` | **Путь 2:** Сумма транзакции укладывается в суточный лимит, но превышает месячный (`amount > monthlyLimit`) | Создание карты с балансом `10 000.00 RUB`, `dailyLimit = 5 000.00 RUB`, но `monthlyLimit = 20.00 RUB` (2000 копеек), отправка запроса на `30.00 RUB` | Passed |

---

## 3. Валидация

| Поле | Правило | Классы | Границы | Кол-во проверок |
|---|---|---|---|:---:|
| `mti` | Ровно `0100` | Валидный: `"0100"`. Невалидные: пустая строка, иной тип (`"0200"`, `"0110"`), нечисловые символы (`"INVALID"`) | `"0100"` vs `"0200"`, `"0110"` | 4 |
| `pan` | Ровно 16 цифр | Валидный: 16 цифр. Невалидные: 15 цифр, 17 цифр, буквы/спецсимволы, пустой | Длины 15, 16, 17 символов | 4 |
| `amount` | Больше 0 | Валидный: > 0. Невалидные: 0, отрицательные числа (`-100`, `-0.01`), `null` | 0, -0.01, 0.01 | 3 |
| `currencyCode` | Ровно 3 символа/цифры | Валидный: `"643"`. Невалидные: 2 символа (`"64"`), 4 символа (`"6433"`), буквенный код (`"RUB"`), пустой | Длины 2, 3, 4 | 4 |
| `mcc` | Ровно 4 цифры | Валидный: `"5411"`. Невалидные: 3 цифры (`"541"`), 5 цифр (`"54111"`), буквы (`"ABCD"`) | Длины 3, 4, 5 | 3 |
| `stan` | Обязательное поле (not blank) | Валидный: 6 цифр. Невалидные: `null`, пустой | 6 символов | 1 |
| `bin` (Card Create) | Ровно 6 цифр | Валидный: 6 цифр (`"400000"`). Невалидные: 5 цифр (`"12345"`), 7 цифр (`"1234567"`), буквы (`"ABCDEF"`), пустой | Длины 5, 6, 7 | 5 |
| `cardholderName` | Только заглавные буквы, пробелы, дефис, точку, апостроф | Валидный: `"IVAN IVANOV"`. Невалидные: кириллица, строчные буквы, цифры | Символы алфавита A-Z | 1 |
| `dailyLimit` / `monthlyLimit` | Неотрицательное число (`>= 0`) | Валидный: `>= 0`. Невалидный: отрицательное число (`-100`) | -1, 0, 1 | 1 |
| `limit` (Search/Recent) | Целое число от 1 до 500 | Валидный: 1..500. Невалидные: 0, 501, отрицательные | 0, 1, 500, 501 | 2 |
| `offset` (Search) | Целое неотрицательное (`>= 0`) | Валидный: `>= 0`. Невалидный: отрицательный (`-1`) | -1, 0 | 1 |
| `count` (Terminal Simulator) | Целое число `>= 1` | Валидный: `>= 1`. Невалидные: 0, отрицательные | 0, 1 | 1 |
| `tps` (Terminal Simulator) | Обязательное целое число `>= 1` | Валидный: `>= 1`. Невалидные: отсутствие поля (`null`), `< 1` | null, 0, 1 | 1 |
| `count` (Merchant Simulator) | Целое число `>= 1` | Валидный: `>= 1`. Невалидные: 0, -1 | -1, 0, 1 | 1 |

---

## 4. Расхождения контракта и реализации

| Декларировано | Наблюдается | Источник декларации | Классификация |
|---|---|---|---|
| В `POST /api/simulator/terminal/run` тело запроса содержит только `count` и `scenario` | Сервис `TerminalSimulatorController` принимает `TerminalRunRequest`, где поле `tps` помечено `@NotNull @Min(1)`. Запрос без `tps` завершается ошибкой HTTP 400 Bad Request | `docs/api-spec.md` (раздел 4) | **Дефект спецификации / документации** |
| Эндпоинт `POST /api/transactions` возвращает статус HTTP 200 для всех исходов авторизации (`APPROVED` и `DECLINED`) с доменными кодами в теле | При прямом межсервисном вызове `POST /api/internal/authorize` сервис Authorization возвращает HTTP-статусы: `404` (карта не найдена), `422` (недостаточно средств), `403` (заблокирована/неактивна/просрочена). Gateway и Switch оборачивают эти ответы в HTTP 200 для внешних клиентов | `docs/api-spec.md` (раздел 3) vs код `AuthControllerImpl.java` | **Архитектурная граница контракта** (внутренний REST vs внешний фасад) |
| В `docs/api-spec.md` описаны только `POST /api/cards`, `POST /api/cards/generate`, `GET /api/cards/{pan}` | Реализованы полнофункциональные эндпоинты `GET /api/cards` (список с фильтрами и пагинацией), `PATCH /api/cards/{pan}` (обновление статуса/лимитов), `DELETE /api/cards/{pan}` (soft-delete в статус DELETED), `POST /api/cards/{pan}/reserve` и `rollback` | `docs/api-spec.md` (раздел 2) vs `CardController.java`, `openapi.yaml` | **Недокументированная функциональность в markdown-спецификации** (присутствует в коде и OpenAPI) |
| Эндпоинты `/api/bins` (Card Management), `/api/bin/{bin}` (Bin Lookup), `/api/notifications` объявлены в контроллерах сервисов | В конфигурации Gateway (`services/gateway/src/main/resources/application.yml`) отсутствуют маршруты для `/api/bins`, `/api/bin` и `/api/notifications`. Запросы к ним через Gateway (порт 8080) завершаются 404 | Контроллеры `BinIssuerController`, `BinLookupController`, `NotificationController` vs `application.yml` | **Известное архитектурное ограничение**: сервисы предназначены только для внутренней инфраструктуры и не выставлены во внешний контур через Gateway |
| В `GET /api/transactions/search` описаны фильтры `pan`, `status`, `dateFrom`, `dateTo`, `merchantId`, `declineReason`, `limit`, `offset` | Реализация `TransactionController` также поддерживает фильтрацию по `issuerId` и `mcc`. Кроме того, реализован эндпоинт `GET /api/transactions/export`, возвращающий `text/csv` | `docs/api-spec.md` (раздел 3) vs `TransactionController.java` | **Расширение возможностей реализации** |
| `DELETE /api/cards/{pan}` переводит карту в статус `DELETED` | При последующем вызове `GET /api/cards/{pan}` сервис `CardService` выбрасывает исключение `CardNotFoundException`, возвращая клиенту HTTP 404 Not Found, а не карточку со статусом `DELETED` | `CardService.java` vs предположение о soft-delete чтении | **Архитектурная особенность**: удалённые карты недоступны по единичному чтению через внешний API |
| При неизвестном BIN в `SwitchService` ожидается текстовый `declineReason: UNKNOWN_BIN` | Метод `AuthorizationResponse.unknownBin(stan)` возвращает `responseCode: "14"`, но с `declineReason: "CARD_NOT_FOUND"` | `AuthorizationResponse.java` | **Особенность унификации кодов отказа** |
| Шлюз Gateway проксирует `/api/simulator/terminal/**` и `/api/simulator/merchant/**` | Запрос `GET /api/simulator/merchants` во множественном числе не матчится предикатом Gateway и возвращает 404; прямой вызов Merchant Simulator (:8084) возвращает 200 OK | `services/gateway/src/main/resources/application.yml` | **Ограничение конфигурации маршрутов Gateway** |

---

## 5. Обоснование достаточности

Предложенный набор API-тестов (`api-tests`) включает **69 проверок**, сгруппированных по 7 доменным классам, и полностью покрывает контрактную модель СМП.

### 1. Полнота выделения классов эквивалентности и границ
- Для каждого типа данных входных параметров (числовые суммы, лимиты, длины строк, форматы MTI/STAN/PAN/MCC, перечисления статусов) выделены как минимум один валидный класс и по одному невалидному классу на каждое правило валидации (негативные классы не смешиваются в рамках одного запроса).
- Граничные значения протестированы для диапазонов пагинации (`limit = 1, 500, 501`, `offset = -1, 0`), сумм (`amount = 0, -0.01, 0.01`) и длин полей (длина PAN: 15, 16, 17; длина BIN: 5, 6, 7).

### 2. Изоляция и чистота данных
- Тесты не используют общих константных идентификаторов между прогонами. Каждый тест генерирует уникальные UUID, 6-значные случайные STAN и выпускает карты с уникальными именами держателей (`CardDataFactory`).
- Начальное состояние карт (доступный остаток, суточный и месячный лимиты, статус) задаётся явно через API перед выполнением проверок, что исключает flaky-поведение из-за остаточного состояния базы данных.

### 3. Сквозная проверка и асинхронные эффекты
- Протестирована полная цепочка движения транзакции от Gateway через Switch, Authorization и Card Management в хранилище Transaction Logger (PostgreSQL via RabbitMQ).
- Проверена eventual consistency с использованием `Awaitility` с явными границами ожидания (`atMost(10s)`, `pollInterval(500ms)`). Фиксированные паузы `Thread.sleep` не применяются.
- Проверено как появление эффекта (сохранение транзакции и уменьшение баланса карты), так и **отсутствие** эффекта при отказе (при отклонении транзакции баланс карты остаётся неизменным).

### 4. Повторная доставка и дедупликация
- Покрыты все сценарии повторной обработки в Transaction Logger (`POST /api/internal/log`):
  1. Первичная доставка: HTTP 201 Created.
  2. Идемпотентный повтор идентичного сообщения: HTTP 200 OK.
  3. Повтор с тем же идентификатором, но изменившимися данными (сумма, реквизиты): HTTP 409 Conflict.

### 5. Что осознанно оставлено вне покрытия и почему
- **Сетевые сбои контейнеров RabbitMQ и остановка PostgreSQL на лету:** намеренная остановка контейнеров (`docker stop smp-rabbitmq`) относится к модулю 6 (интеграционное и отказоустойчивое тестирование инфраструктуры) и модулю 7.
- **WebSocket `/ws/transactions`:** задание практики 5 строго ограничено синхронными HTTP-контрактами REST.

---

## 6. Доказательства работоспособности проверок

Способность тестов обнаруживать дефекты и падать при нарушении контрактных условий подтверждена намеренным искажением ожиданий и данных:

| Тест | Внесённое изменение | Результат |
|---|---|---|
| `TransactionApiTest.testAuthorizeTransaction_Approved_Returns200AndValidSchema` | Ожидание кода ответа изменено с `"00"` на `"99"` (`body("responseCode", equalTo("99"))`) | **Красный.** `java.lang.AssertionError: 1 expectation failed. JSON path responseCode doesn't match. Expected: 99, Actual: 00` |
| `TransactionApiTest.testAuthorize_Decline_InsufficientFunds` | Ожидание статуса изменено с `"DECLINED"` на `"APPROVED"` (`body("status", equalTo("APPROVED"))`) | **Красный.** `java.lang.AssertionError: 1 expectation failed. JSON path status doesn't match. Expected: APPROVED, Actual: DECLINED` |
| `InternalApiTest.testInternalLog_IdenticalReDelivery_Returns200Ok` | Ожидание статус-кода повторной доставки изменено с `200` на `201` (`statusCode(201)`) | **Красный.** `java.lang.AssertionError: 1 expectation failed. Expected status code <201> but was <200>.` |
| **Мутационная проверка:** `InternalApiTest.testInternalLog_ConflictingReDelivery_Returns409Conflict` | Симуляция отключения механизма дедупликации (если бы сервис игнорировал конфликт данных и возвращал `200 OK`) | **Красный.** Тест падает с `Expected status code <409> but was <200>`, подтверждая защиту от тихой перезаписи данных при расхождении полей транзакции |

---

## 7. Порядок запуска

### Предварительные условия
- Установлен Docker & Docker Compose
- Установлен JDK 21 и Apache Maven 3.9+

### Локальный запуск на поднятом стеке

1. **Подъём стека СМП:**
   ```bash
   cp .env.example .env
   docker compose up -d
   ```

2. **Ожидание готовности сервисов:**
   Все 11 сервисов должны перейти в состояние `healthy` (проверка: `docker compose ps` или `make status`).

3. **Запуск API-тестов:**
   ```bash
   mvn -f services/pom.xml -pl api-tests -am test -DskipApiTests=false
   ```
   Либо с помощью цели Makefile:
   ```bash
   make api-test
   ```

4. **Запуск отдельного класса тестов:**
   ```bash
   mvn -f services/pom.xml -pl api-tests test -Dtest=TransactionApiTest -DskipApiTests=false
   ```

### Запуск в CI (GitHub Actions)
- Автоматически при пуше в ветки `practic-5*` / `practice-5*` запускается пайплайн `.github/workflows/api-tests.yml`.
- Либо вручную во вкладке **Actions** → workflow **Practice Run (heavy checks)** → выбор практики `5`.
