# Тест-кейсы и анализ пирамиды

## Новые тест-кейсы

Покрыты пути **sync** (HTTP REST, маршрутизация, лимиты, баланс, резервирование) и **async** (RabbitMQ, доставка в Logger, Transactional Outbox в Notification, Dead Letter Exchange DLX/DLQ) на **трёх уровнях** пирамиды (`unit`, `api/integration`, `e2e`).

| ID | Req                      | Уровень тестирования | Вид | Источник                      | Предусловие | Шаги | Ожидание |
|---|--------------------------|:---:|:---:|-------------------------------|---|---|---|
| **TC-P3-01** | TZ-04 §2.4               | **unit** | − | > daily (daily limit OFF+)    | `AuthServiceImpl`, мок CMS возвращает карту `ACTIVE` с `dailyLimit=100000`; мок `LimitUsageRepository.upsertLimitUsage` возвращает `0` | Вызвать `authService.authorize(request, Instant.now())` с `amount=10001` | DECLINED `61` (`EXCEEDS_AMOUNT_LIMIT`), `reserve` в CMS не вызывался |
| **TC-P3-02** | TZ-04 §2.6               | **unit** | − | > balance (balance OFF+)      | `AuthServiceImpl`, мок CMS возвращает карту `ACTIVE` с `availableBalance=200000`; лимиты не исчерпаны | Вызвать `authService.authorize(request, Instant.now())` с `amount=200001` | DECLINED `51` (`INSUFFICIENT_FUNDS`), метод `reserve` не вызывался |
| **TC-P3-03** | TZ-05 §2                 | **unit** | + | переход ACTIVE → BLOCKED      | `CardServiceImpl`, мок `CardRepository` с картой `status=ACTIVE` | Вызвать `cardService.patchCard(pan, CardStatus.BLOCKED, null, null, null)` | `status=BLOCKED`, вызван `cardRepository.update()`, сгенерировано событие `CardServicePatchEvent` |
| **TC-P3-04** | TZ-03 §3                 | **unit** | + | маршрутизация по BIN          | `RouteService`, настроен `RoutingService` с префиксом `"400001": "ISS002"`, мок `AuthorizationClient` | Вызвать `routeService.route(request)` с PAN `4000011234567890` (BIN `400001`) | Вызов `authorizationClient.authorize()` с установленным `issuerId="ISS002"` |
| **TC-P3-05** | TZ-04 §2                 | **api** | + | контракт Auth API             | Сервис `Authorization` запущен, WireMock CMS возвращает карту `ACTIVE`, balance=`500000` | Отправить HTTP POST `/api/internal/authorize` с `AuthorizationRequest` (MTI "0100", amount=`50000`) | HTTP 200 OK, `status=APPROVED`, `responseCode=00`, RRN 12 цифр, authCode 6 символов [0-9A-Z] |
| **TC-P3-06** | TZ-05 §5                 | **api** | + | контракт reserve CMS          | Сервис `Card-Management` запущен с тестовой БД, в таблице `cards` карта с `availableBalance=200000` | Отправить HTTP POST `/api/cards/{pan}/reserve` с `amount=100000`, `rrn="123456789012"` | HTTP 200 OK, `availableBalance=100000`, запись в `card_reservations`, повторный GET возвращает `100000` |
| **TC-P3-07** | TZ-05 §5                 | **api** | + | контракт rollback CMS         | В CMS выполнен reserve `50000` (RRN `999888777666`), доступный остаток `150000` (исходный `200000`) | Отправить HTTP POST `/api/cards/{pan}/rollback` с `amount=50000`, `rrn="999888777666"` | HTTP 200 OK, `availableBalance=200000`, создана компенсирующая запись в `card_rollbacks` |
| **TC-P3-08** | TZ-08                    | **integration** | + | асинхронная доставка в Logger | RabbitMQ и Transaction-Logger подняты, exchange `smp.transactions` и queue `transaction-log` активны | Опубликовать `TransactionRequest` в exchange `smp.transactions` с routing key `transaction.log` | Сообщение потреблено `TransactionLogListener`, в таблице `transactions` создана запись |
| **TC-P3-09** | TZ-05 outbox             | **integration** | + | Outbox -> Notification        | Card-Management и Notification-Service подключены к RabbitMQ, таблица `outbox_event` пуста | Отправить `POST /api/cards` (создание карты), дождаться срабатывания `OutboxEventProcessorImpl` | Публикация в `smp.card-events` (`card.CardServiceCreationEvent`), статус `PROCESSED`, запись в `card_notifications` |
| **TC-P3-10** | TZ-08                    | **integration** | − | DLX / retry очередей          | Transaction-Logger с эмуляцией сбоя сохранения, очередь `transaction-log` с `x-max-delivery: 3` | Опубликовать сообщение в `smp.transactions`, консьюмер 3 раза выбрасывает исключение | После 3 попыток сообщение перенаправляется брокером через DLX в `transaction-log-dlq` (TTL 60000ms) |
| **TC-P3-11** | TZ-03 §3 + TZ-04 + TZ-05 | **e2e** | + | сквозной Happy Path           | Полный стек docker-compose поднят (11 сервисов + БД + брокер), в CMS карта `ACTIVE` с балансом `100000` | Отправить HTTP POST `/api/transactions` через Gateway (:8080) с `amount=10000` | HTTP 200 OK, `status=APPROVED`, `responseCode=00`, баланс в CMS `90000`, транзакция найдена в Logger и WebSocket |
| **TC-P3-12** | TZ-03 §3                 | **e2e** | − | Reversal при timeout брокера  | Полный контур поднят, контейнер RabbitMQ остановлен после старта, тестовая карта `ACTIVE` с балансом `100000` | Отправить HTTP POST `/api/transactions` на Gateway (:8080) с `amount=20000` | HTTP 200 OK, `status=DECLINED`, `responseCode=96` (System error), баланс в CMS остался `100000` (откат reserve) |

---

## Распределение тест-кейсов практики 2

### 4.1 Классы эквивалентности — Authorization (TC-EQ-A01 .. TC-EQ-A10)

| ID | Req | Уровень тестирования | Вид | Источник | Что проверяет тест | Обоснование уровня |
|---|---|:---:|:---:|---|---|---|
| **TC-EQ-A01** | TZ-04 §2.1 | **unit** | − | PAN отсутствует | Ветка `CardNotFoundException` | Чистая логика сервиса: мок CMS возвращает 404, проверяется реакция без поднятия сети (код `14`). |
| **TC-EQ-A02** | TZ-04 §2.2 | **unit** | − | INACTIVE | Ветвление `currCardStatus == INACTIVE` | Проверка ветки `switch` в бизнес-логике: мок возвращает статус `INACTIVE` → код `05`. |
| **TC-EQ-A03** | TZ-04 §2.2 | **unit** | − | BLOCKED | Ветвление `currCardStatus == BLOCKED` | Логика отказа по заблокированной карте: мок возвращает `BLOCKED` → код `05`. |
| **TC-EQ-A04** | TZ-04 §2.2 | **unit** | − | EXPIRED (status) | Ветвление `currCardStatus == EXPIRED` | Проверка статуса истекшей карты → код `54`. Изолированный тест в памяти JVM. |
| **TC-EQ-A05** | TZ-04 §2.3 | **unit** | − | expiry < now | Логика даты `lastValidDay.isBefore(transmissionDate)`  | Чистая логика сравнения дат: `cardResponse.expiryDate().atEndOfMonth()` против даты транзакции. |
| **TC-EQ-A06** | TZ-04 §2.4 | **unit** | − | > daily | Условие `checkAndUpdateLimits() == false` | Проверка реакции сервиса на исчерпание дневного лимита: мок репозитория возвращает 0 → код `61`. |
| **TC-EQ-A07** | TZ-04 §2.5 | **unit** | − | > monthly | Условие `checkAndUpdateLimits() == false`  | Бизнес-правило лимита изолировано в сервисе: мок репозитория имитирует переполнение месяца → код `61`. |
| **TC-EQ-A08** | TZ-04 §2.6 | **unit** | − | > balance | Арифметическое сравнение `amount.compareTo(availableBalance) > 0` | Сравнение сумм в памяти JVM; возврат кода `51` (`INSUFFICIENT_FUNDS`) без внешних обращений |
| **TC-EQ-A09** | TZ-04 §2.7 | **api** | + | все OK | Сквозной контракт REST API: MTI, RRN, authCode, HTTP 200 | Проверяет REST-контракт `POST /api/internal/authorize`, сериализацию DTO и формат выходных полей. |
| **TC-EQ-A10** | TZ-04 §5 | **integration** | − | CMS down | Обработка сетевого таймаута / 503 от CMS | Проверяет сетевую устойчивость: Authorization вызывает CMS, ловит `ServiceUnavailableException` → код `96`. |

### 4.1 Классы эквивалентности — Card-Management (TC-EQ-C01 .. TC-EQ-C12)

| ID | Req | Уровень тестирования | Вид | Источник | Что проверяет тест | Обоснование уровня |
|---|---|:---:|:---:|---|---|---|
| **TC-EQ-C01** | TZ-05 §2 | **api** | + | валидный create | Контракт `POST /api/cards`: генерация PAN по Луну, статус ACTIVE, HTTP 201 | Проверяет работу REST-контроллера, валидацию DTO, вызов генератора PAN и запись в БД. |
| **TC-EQ-C02** | TZ-05 §2 | **api** | − | невалидный BIN | Валидация аннотации `@Bin` в `CreateCardRequest` | Проверяет валидацию Spring MVC: невалидный BIN отсекается до сервиса с кодом 400 Bad Request. |
| **TC-EQ-C03** | TZ-05 §2 | **api** | + | карта есть | Контракт `GET /api/cards/{pan}` → HTTP 200 | Проверяет вычитку CardModel из базы через REST API и сериализацию всех полей. |
| **TC-EQ-C04** | TZ-05 §2 | **api** | − | карты нет | Контракт `GET /api/cards/{pan}` → HTTP 404 | Проверяет обработку `CardNotFoundException` в `GlobalExceptionHandler` с формированием ErrorResponse 404. |
| **TC-EQ-C05** | TZ-05 §2 | **api** | + | soft-delete | `DELETE /api/cards/{pan}` → 204 No Content, повторный GET → 404 | Проверяет изменение состояния сущности (`card.deleted()`) через REST-эндпоинты. |
| **TC-EQ-C06** | TZ-05 §5 | **api** | + | reserve ≤ B | `POST /api/cards/{pan}/reserve` → HTTP 200, уменьшение `availableBalance` | Проверяет контракт резервирования и фиксацию записи в `card_reservations`. |
| **TC-EQ-C07** | TZ-05 §2 | **api** | − | пустое имя | Валидация `@NotBlank` поля `cardholderName` | Валидация входного JSON на уровне веб-слоя (HTTP 400). |
| **TC-EQ-C08** | TZ-05 §2 | **api** | − | лимит < 0 | Валидация `@NotNegative` поля `dailyLimit` | Валидация контракта: отрицательные числа возвращают HTTP 400. |
| **TC-EQ-C09** | TZ-05 §2 | **api** | + | PATCH status | `PATCH /api/cards/{pan}` → HTTP 200, `status=BLOCKED` | Проверяет логику PATCH и корректный маппинг enum статусов. |
| **TC-EQ-C10** | TZ-05 §2 | **api** | − | неизвестный status | Десериализация несуществующего значения `CardStatus` | Проверяет перехват ошибки десериализации Jackson (HTTP 400). |
| **TC-EQ-C11** | TZ-05 §2 | **api** | − | повторный DELETE | Повторный `DELETE /api/cards/{pan}` → HTTP 404 | Проверяет идемпотентность и корректность обработки повторного удаления. |
| **TC-EQ-C12** | TZ-05 §5 | **api** | − | reserve > B | `POST .../reserve` с `amount > availableBalance` → HTTP 402 | Проверяет бизнес-исключение `InsufficientFundsException` в CMS и возврат статуса 402 Payment Required. |

### 4.2 Граничные значения — Authorization (TC-BV-A01 .. TC-BV-A09)

| ID | Req | Уровень тестирования | Вид | Источник | Что проверяет тест | Обоснование уровня |
|---|---|:---:|:---:|---|---|---|
| **TC-BV-A01** | TZ-04 §2.4 | **unit** | + | daily ON | Граничное условие `used + amount == dailyLimit` | Проверка точного равенства границы в логике `AuthServiceImpl` (мок репозитория возвращает успех). |
| **TC-BV-A02** | TZ-04 §2.4 | **unit** | − | daily OFF+ | Граничное условие `used + amount > dailyLimit` | Проверка отсечения на 1 копейку выше дневного лимита (код `61`). |
| **TC-BV-A03** | TZ-04 §2.4 | **unit** | + | daily OFF− | Граничное условие `used + amount < dailyLimit` | Проверка одобрения транзакции на 1 копейку ниже лимита. |
| **TC-BV-A04** | TZ-04 §2.5 | **unit** | + | monthly ON | Граничное условие `used + amount == monthlyLimit` | Проверка точного равенства границы месячного лимита. |
| **TC-BV-A05** | TZ-04 §2.5 | **unit** | − | monthly OFF+ | Граничное условие `used + amount > monthlyLimit` | Проверка отсечения при превышении месячного лимита (код `61`). |
| **TC-BV-A06** | TZ-04 §2.6 | **unit** | + | balance ON | Граница полного списания: `amount == balance` | Проверяет, что при нулевом конечном остатке транзакция одобряется (`compareTo == 0`). |
| **TC-BV-A07** | TZ-04 §2.6 | **unit** | − | balance OFF+ | Граница нехватки средств: `amount > balance` на 1 коп. | Чистая логика сравнения: `compareTo > 0` → отказ `51`. |
| **TC-BV-A08** | TZ-04 §2.3 | **unit** | − | expiry OFF− | Граница срока: карта просрочена на 1 день | Проверка `lastValidDay.isBefore(transmissionDate)` → код `54`. |
| **TC-BV-A09** | TZ-04 §2.3 | **unit** | + | expiry ON | Граница срока: карта действительна до конца месяца | Проверка, что в текущем месяце карта считается действительной (`isBefore == false`). |

### 4.2 Граничные значения — Card-Management (TC-BV-C01 .. TC-BV-C02)

| ID | Req | Уровень тестирования | Вид | Источник | Что проверяет тест | Обоснование уровня |
|---|---|:---:|:---:|---|---|---|
| **TC-BV-C01** | TZ-05 §5 | **api** | +/− | reserve ON / OFF+ | Граница резервирования `amount == balance` (200) vs `amount > balance` (402) | Проверяет реакцию REST API резервирования и проверку в репозитории на реальной БД. |
| **TC-BV-C02** | TZ-05 PAN | **api** | −/+ | длина PAN | Валидация аннотации `@Pan` (ровно 16 цифр) в URL: 15, 16, 17 | Проверяет отсечение невалидных URL-параметров: 15 и 17 цифр возвращают HTTP 400, 16 -> 200. |

### 4.3 Pairwise — Authorization (TC-PW-01 .. TC-PW-25)

Все 25 кейсов комбинаторного тестирования PICT проверяют **порядок приоритета проверок и доминирование условий отказа** внутри метода `AuthServiceImpl.authorize()`.
Так как они проверяют внутреннее дерево ветвлений без изменения сетевых протоколов, все они отнесены к уровню **unit** с моками CMS и репозитория лимитов:

| ID | Req | Уровень тестирования | Вид | Источник | Что проверяет тест | Обоснование уровня |
|---|---|:---:|:---:|---|---|---|
| **TC-PW-01** | TZ-04 | **unit** | − | EXPIRED, valid expiry, pos, restaurant | Код `54` (CARD_EXPIRED) | Статус `EXPIRED` доминирует над валидной датой и лимитами. |
| **TC-PW-02** | TZ-04 | **unit** | − | BLOCKED, current_month, atm, grocery | Код `05` (CARD_BLOCKED) | Статус `BLOCKED` останавливает выполнение до проверки баланса. |
| **TC-PW-03** | TZ-04 | **unit** | − | INACTIVE, valid expiry, ecom, grocery | Код `05` (CARD_INACTIVE) | Статус `INACTIVE` останавливает проверку лимитов. |
| **TC-PW-04** | TZ-04 | **unit** | − | INACTIVE, expired expiry, atm, travel | Код `05` (CARD_INACTIVE) | Проверка статуса в `switch` предшествует проверке даты. |
| **TC-PW-05** | TZ-04 | **unit** | − | ACTIVE, daily/monthly/balance above | Код `51` (INSUFFICIENT_FUNDS) | Проверка баланса предшествует обращению к лимитам в БД. |
| **TC-PW-06** | TZ-04 | **unit** | − | INACTIVE, current_month, pos, electronics | Код `05` (CARD_INACTIVE) | Проверка изоляции терминала POS и MCC при неактивной карте. |
| **TC-PW-07** | TZ-04 | **unit** | − | INACTIVE, valid, atm, restaurant | Код `05` (CARD_INACTIVE) | Комбинация ATM + restaurant при неактивной карте. |
| **TC-PW-08** | TZ-04 | **unit** | − | EXPIRED, expired date, pos, grocery | Код `54` (CARD_EXPIRED) | Двойное условие просрочки (статус + дата). |
| **TC-PW-09** | TZ-04 | **unit** | − | BLOCKED, expired date, ecom, restaurant | Код `05` (CARD_BLOCKED) | Статус `BLOCKED` проверяется раньше даты `expiryDate`. |
| **TC-PW-10** | TZ-04 | **unit** | − | ACTIVE, daily above, monthly/balance equal | Код `61` (EXCEEDS_AMOUNT_LIMIT) | Баланс достаточен, отказ генерируется на шаге лимитов. |
| **TC-PW-11** | TZ-04 | **unit** | − | BLOCKED, valid date, pos, travel | Код `05` (CARD_BLOCKED) | Блокировка карты в POS-терминале для категории travel. |
| **TC-PW-12** | TZ-04 | **unit** | − | ACTIVE, daily equal, monthly above | Код `61` (EXCEEDS_AMOUNT_LIMIT) | Дневной лимит соблюден, месячный превышен → код `61`. |
| **TC-PW-13** | TZ-04 | **unit** | + | ACTIVE, все лимиты и баланс на границе | Код `00` (APPROVED) | Все проверки равны границе (equal) → успешное одобрение. |
| **TC-PW-14** | TZ-04 | **unit** | − | ACTIVE, daily below, balance above | Код `51` (INSUFFICIENT_FUNDS) | Отказ по балансу до обращения к лимитам. |
| **TC-PW-15** | TZ-04 | **unit** | − | ACTIVE, daily/monthly above, balance below | Код `61` (EXCEEDS_AMOUNT_LIMIT) | Баланс есть, отказ по лимитам. |
| **TC-PW-16** | TZ-04 | **unit** | − | ACTIVE, daily equal, balance above | Код `51` (INSUFFICIENT_FUNDS) | Приоритет проверки баланса над проверкой лимитов. |
| **TC-PW-17** | TZ-04 | **unit** | + | ACTIVE, daily below, monthly/balance equal | Код `00` (APPROVED) | Граничный позитивный исход. |
| **TC-PW-18** | TZ-04 | **unit** | − | EXPIRED, current_month, ecom, travel | Код `54` (CARD_EXPIRED) | Статус EXPIRED в интернет-эквайринге. |
| **TC-PW-19** | TZ-04 | **unit** | + | ACTIVE, daily/monthly equal, balance below | Код `00` (APPROVED) | Позитивный исход на границе лимитов. |
| **TC-PW-20** | TZ-04 | **unit** | − | ACTIVE, daily below, monthly/balance above | Код `51` (INSUFFICIENT_FUNDS) | Отказ по балансу при превышении месяца. |
| **TC-PW-21** | TZ-04 | **unit** | − | BLOCKED, expired date, pos, electronics | Код `05` (CARD_BLOCKED) | Изоляция блокировки в электронике. |
| **TC-PW-22** | TZ-04 | **unit** | − | ACTIVE, daily above, balance equal | Код `61` (EXCEEDS_AMOUNT_LIMIT) | Отказ по дневному лимиту при доступном балансе. |
| **TC-PW-23** | TZ-04 | **unit** | − | ACTIVE, daily equal, monthly above | Код `61` (EXCEEDS_AMOUNT_LIMIT) | Отказ по месячному лимиту в банкомате (ATM). |
| **TC-PW-24** | TZ-04 | **unit** | − | EXPIRED, current_month, atm, electronics | Код `54` (CARD_EXPIRED) | Просроченная карта в ATM. |
| **TC-PW-25** | TZ-04 | **unit** | − | ACTIVE, expired date, all limits below | Код `54` (CARD_EXPIRED) | Проверка отсечения по дате при активном статусе. |

### 4.4 Pairwise — Card-Management (TC-PW-C01 .. TC-PW-C29)

Все 29 кейсов PICT для CMS проверяют комбинации операций REST-контроллера (`get`, `create`, `patch`, `delete`, `reserve`, `generate`) с различными статусами, длинами PAN и флагами существования карты.
Поскольку проверяются **HTTP-статусы (200, 201, 400, 404, 402, 409)** и валидаторы Spring Boot, все 29 кейсов классифицированы как **API-тесты**:

| ID | Req | Уровень тестирования | Вид | Источник | Что проверяет тест | Обоснование уровня |
|---|---|:---:|:---:|---|---|---|
| **TC-PW-C01** | TZ-05 | **api** | + | patch INACTIVE, valid PAN 16 | HTTP 200 OK | Проверяет контракт PATCH /api/cards/{pan} со сменой статуса на INACTIVE. |
| **TC-PW-C02** | TZ-05 | **api** | − | patch ACTIVE, PAN len 17 | HTTP 400 Bad Request | Валидация длины PAN (17 символов) на уровне REST-контроллера. |
| **TC-PW-C03** | TZ-05 | **api** | − | delete INACTIVE, PAN len 15 | HTTP 400 Bad Request | Валидация длины PAN (15 символов) при вызове DELETE. |
| **TC-PW-C04** | TZ-05 | **api** | − | delete ACTIVE, несуществующий PAN | HTTP 404 Not Found | Проверяет 404 при удалении несуществующей карты. |
| **TC-PW-C05** | TZ-05 | **api** | − | patch BLOCKED, PAN len 15 | HTTP 400 Bad Request | Валидация невалидного формата PAN при блокировке. |
| **TC-PW-C06** | TZ-05 | **api** | − | delete BLOCKED, PAN len 17 | HTTP 400 Bad Request | Валидация длины PAN в DELETE endpoint. |
| **TC-PW-C07** | TZ-05 | **api** | − | reserve BLOCKED, amount > balance | HTTP 402 Payment Required | Проверка возврата 402 при превышении баланса в reserve. |
| **TC-PW-C08** | TZ-05 | **api** | + | get BLOCKED, valid PAN | HTTP 200 OK | Контракт GET возвращает заблокированную карту со всеми реквизитами. |
| **TC-PW-C09** | TZ-05 | **api** | + | delete EXPIRED, valid PAN | HTTP 204 No Content | Проверяет возможность удаления просроченной карты. |
| **TC-PW-C10** | TZ-05 | **api** | + | reserve INACTIVE, amount == balance | HTTP 200 OK | Контракт reserve на уровне CMS (CMS проверяет только баланс). |
| **TC-PW-C11** | TZ-05 | **api** | − | get EXPIRED, PAN len 15 | HTTP 400 Bad Request | Валидация URL-параметра длины 15 в GET. |
| **TC-PW-C12** | TZ-05 | **api** | + | reserve EXPIRED, amount < balance | HTTP 200 OK | Контракт резерва CMS. |
| **TC-PW-C13** | TZ-05 | **api** | − | reserve INACTIVE, amount > balance | HTTP 402 Payment Required | Отказ 402 при превышении остатка. |
| **TC-PW-C14** | TZ-05 | **api** | − | reserve ACTIVE, несуществующий PAN | HTTP 404 Not Found | Проверка ответа 404 в reserve при отсутствии карты. |
| **TC-PW-C15** | TZ-05 | **api** | − | get ACTIVE, PAN len 17 | HTTP 400 Bad Request | Валидация длины PAN (17 цифр) в GET. |
| **TC-PW-C16** | TZ-05 | **api** | − | generate count=0 | HTTP 400 Bad Request | Проверка валидации `@Positive` на количество генерируемых карт. |
| **TC-PW-C17** | TZ-05 | **api** | − | reserve EXPIRED, amount > balance | HTTP 402 Payment Required | Нехватка средств при резерве просроченной карты. |
| **TC-PW-C18** | TZ-05 | **api** | − | delete ACTIVE, PAN len 15 | HTTP 400 Bad Request | Валидация длины PAN в DELETE. |
| **TC-PW-C19** | TZ-05 | **api** | + | generate count=100 | HTTP 200 OK | Контракт массовой генерации пула карт. |
| **TC-PW-C20** | TZ-05 | **api** | + | reserve EXPIRED, amount == balance | HTTP 200 OK | Резервирование полного остатка. |
| **TC-PW-C21** | TZ-05 | **api** | − | patch ACTIVE, несуществующий PAN | HTTP 404 Not Found | Обновление несуществующей карты возвращает 404. |
| **TC-PW-C22** | TZ-05 | **api** | − | get INACTIVE, PAN len 17 | HTTP 400 Bad Request | Валидация URL-параметра PAN. |
| **TC-PW-C23** | TZ-05 | **api** | + | reserve BLOCKED, amount == balance | HTTP 200 OK | Контракт резерва по балансу. |
| **TC-PW-C24** | TZ-05 | **api** | − | get ACTIVE, несуществующий PAN | HTTP 404 Not Found | Проверка 404 в GET по несуществующему PAN. |
| **TC-PW-C25** | TZ-05 | **api** | − | create с невалидным BIN | HTTP 400 Bad Request | Отклонение создания карты с невалидным BIN. |
| **TC-PW-C26** | TZ-05 | **api** | + | create валидной карты | HTTP 201 Created | Контракт создания карты с кодом 201. |
| **TC-PW-C27** | TZ-05 | **api** | + | generate count=1 | HTTP 200 OK | Генерация одной тестовой карты. |
| **TC-PW-C28** | TZ-05 | **api** | − | reserve ACTIVE, несуществующий PAN, equal | HTTP 404 Not Found | Проверка 404 в reserve. |
| **TC-PW-C29** | TZ-05 | **api** | − | patch EXPIRED, PAN len 17 | HTTP 400 Bad Request | Валидация формата PAN при PATCH. |

### 4.5 Ручные сочетания (TC-MAN-01 .. TC-MAN-04)

| ID | Req | Уровень тестирования | Вид | Источник | Что проверяет тест                                        | Обоснование уровня |
|---|---|:---:|:---:|---|-----------------------------------------------------------|---|
| **TC-MAN-01** | TZ-04 §2.6 | **unit** | − | тройка вручную | daily=ON, monthly=ON, balance=OFF+                        | Приоритет проверки баланса над лимитами: при равенстве лимитов границе отказ идет по балансу (`51`). Изолированный unit-тест. |
| **TC-MAN-02** | TZ-04 §2.5 | **unit** | − | изоляция месяца | daily OK, monthly OFF+                                    | Проверка отсечения по месячному лимиту при неисчерпанном дневном лимите в сервисе. |
| **TC-MAN-03** | TZ-04 §2.1 | **integration** | − | DELETED как «нет карты» | Soft-deleted карта не находится в CMS (`404`) -> код `14` | Требует реального взаимодействия с БД: убедиться, что флаг `DELETED` исключает карту из выборки SQL. |
| **TC-MAN-04** | TZ-04 §2.2 | **unit** | − | EXPIRED доминирует над лимитами | Ранний выход: EXPIRED доминирует над лимитами             | Проверка порядка выполнения: верификация моков подтверждает отсутствие лишних обращений к БД при просроченной карте. |

---

## Часть (г): анализ пропорций (20+ тест-кейсов)

### Сводная таблица

| Уровень | Итого | Доля |
|---|---:|---:|
| unit | **56** | 54.4% |
| api  | **45** | 43.7% |
| e2e | **2** | 1.9% |
| **Итого** | **103** | **100%** |

### Фактические пропорции

Итоговое распределение 103 тест-кейсов по уровням тестовой пирамиды СМП:
- **Unit**: **54.4%** (56 кейсов)
- **API**: **43.7%** (45 кейсов)
- **E2E**: **1.9%** (2 кейса)`

---

### Объяснение отклонений

#### 1. Совпадает ли пирамида с ориентиром 70/20/10?
**Нет, не совпадает, и это абсолютно закономерно для архитектуры СМП.**
Ориентир 70/20/10 — эвристический шаблон для монолитных приложений с преобладанием чистой вычислительной логики. В распределенной микросервисной платформе доля `api / integration` закономерно возрастает до **43.7%** за счет сжатия `unit` до **54.4%**.
- Бизнес-логика авторизации (`Authorization Service`) великолепно ложится на `unit`-уровень (все 7 шагов проверок легко изолируются моками, что дало 56 быстрых unit-тестов).
- Однако второй ключевой сервис - `Card-Management` является сервисом управления состоянием. Тестировать CRUD в unit-тестах с моками репозиториев неэффективно: такие тесты проверяют лишь поведение моков, а не валидацию Spring (`@Valid`, `@Pan`), не сериализацию Jackson и не ограничения реляционной схемы. Поэтому проверки CMS перенесены на уровень `api`.

#### 2. Почему в enterprise-микросервисах доля api/integration часто выше 20%?
1. **Плотность межсервисных контрактов:** Взаимодействие между 11 сервисами осуществляется по строгим REST-контрактам и DTO. Ошибки несовпадения полей, неверных HTTP-статусов (400 vs 404 vs 422) и валидаторов отлавливаются именно на уровне API-тестов.
2. **Асинхронные границы и брокер сообщений:** В СМП присутствуют две асинхронные шины (Switch -> Logger и Outbox -> Notification). Проверить сериализацию в RabbitMQ, работу транзакционного Outbox, роутинг ключей `card.*` и поведение при падении консьюмера (Dead Letter Exchange) возможно **только на уровне integration-тестов**.
3. **Стоимость дефекта интеграции:** В финансовых системах расхождение в контрактах между сервисами ведет к блокировке платежей. API-тесты дешевле E2E, но надежно гарантируют целостность контрактов.

#### 3. Почему e2e мало и какие сценарии туда попали?
Доля E2E составляет всего **1.9% (2 кейса)**. Это осознанное архитектурное решение:
- E2E-тесты в микросервисах наиболее дорогие в поддержке, медленные и подвержены ложным срабатываниям (flaky tests) из-за сетевых задержек.
- Поднимать полный контур из 11 сервисов, PostgreSQL и RabbitMQ ради проверки того, что заблокированная карта отклоняется, не имеет смысла — это на 100% проверяется unit-тестом авторизации за 5 миллисекунд.
- На уровень E2E вынесены **только 2 критических бизнес-сценария целиком**:
  1. **TC-P3-11 (Happy Path)** — сквозная цепочка: Терминал → Gateway → Switch → Authorization → CMS (резерв) → RabbitMQ → Logger → WebSocket Дашборда.
  2. **TC-P3-12 (Reversal)** — компенсирующий откат транзакции при сбое подтверждения брокера (Publisher Confirm timeout 2s), гарантирующий сохранение финансовой целостности баланса клиента при отказе инфраструктуры очередей.

#### 4. Нет ли риска ice-cream cone или дефицита контрактных проверок?
- **Риск Ice-Cream Cone:** **Полностью отсутствует.** Пирамида имеет широкое, устойчивое основание (56 unit-тестов) и плавное сужение к вершине (45 API -> 2 E2E). Нет перекоса в тяжелые E2E-проверки.
- **Дефицит контрактных проверок:** **Полностью отсутствует.** 39 API-тестов исчерпывающе покрывают все REST-эндпоинты ядра системы, коды возврата и граничные условия параметров запросов.
