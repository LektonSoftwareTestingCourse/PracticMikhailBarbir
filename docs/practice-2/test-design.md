# Тест дизайн для Authorization и Card Management

Контекст границ и pairwise, если не указано иное: `now = 0926`, карта ACTIVE, `expiryDate = 1026`, `dailyLimit = 100000`, `monthlyLimit = 500000`, `availableBalance = 200000`, usage дня и месяца = 0. Суммы в копейках

Маппинг pairwise-значений в данные: `below` / `equal` / `above` относительно проверяемой границы; `valid` → `1026`, `current_month` → `0926`, `expired` → `0826`; POS/ATM/ECOM; MCC grocery=5411, restaurant=5812, electronics=5732, travel=4722.

---

## 1. Классы эквивалентности

Методика: один класс — один представитель; невалидные классы не смешиваются в одном кейсе.

### Authorization (`POST /api/internal/authorize`)

| Условие | Класс | Знак | Представитель | Кейс |
|---|---|---|---|---|
| PAN | карта есть | + | PAN `4000001234567891` (есть в CMS) | TC-EQ-A09 |
| PAN | карты нет | − | PAN `4000000000000001` (нет в CMS) | TC-EQ-A01 |
| status | ACTIVE | + | `ACTIVE` | TC-EQ-A09 |
| status | INACTIVE | − | `INACTIVE` | TC-EQ-A02 |
| status | BLOCKED | − | `BLOCKED` | TC-EQ-A03 |
| status | EXPIRED | − | `EXPIRED` | TC-EQ-A04 |
| expiryDate | ≥ now | + | `1026` | TC-EQ-A09 |
| expiryDate | < now | − | `0826` | TC-EQ-A05 |
| daily | used+amount ≤ dailyLimit | + | amount=`50000` при limit=`100000` | TC-EQ-A09 |
| daily | used+amount > dailyLimit | − | amount=`100001` | TC-EQ-A06 |
| monthly | used+amount ≤ monthlyLimit | + | amount=`50000` при limit=`500000` | TC-EQ-A09 |
| monthly | used+amount > monthlyLimit | − | amount=`500001` (dailyLimit ≥ amount) | TC-EQ-A07 |
| balance | amount ≤ balance | + | amount=`150000` при balance=`200000` | TC-EQ-A09 |
| balance | amount > balance | − | amount=`200001` | TC-EQ-A08 |
| CMS | доступен | + | CMS отвечает 200 | TC-EQ-A09 |
| CMS | недоступен | − | CMS timeout / 5xx | TC-EQ-A10 |

### Card-Management

| Условие | Класс | Знак | Представитель | Кейс |
|---|---|---|---|---|
| BIN (create) | 6 цифр | + | `400000` | TC-EQ-C01 |
| BIN (create) | не 6 цифр | − | `40A` | TC-EQ-C02 |
| cardholderName | непустое | + | `IVAN IVANOV` | TC-EQ-C01 |
| cardholderName | пустое | − | `""` | TC-EQ-C07 |
| limits / balance | ≥ 0 | + | daily=`15000000` | TC-EQ-C01 |
| limits / balance | < 0 | − | dailyLimit=`-1` | TC-EQ-C08 |
| PAN | 16 цифр, есть в БД | + | PAN созданной карты | TC-EQ-C03 |
| PAN | нет в БД | − | `4000000000000000` | TC-EQ-C04 |
| status (PATCH/фильтр) | ACTIVE…EXPIRED | + | `BLOCKED` | TC-EQ-C09 |
| status | неизвестный | − | `FOO` | TC-EQ-C10 |
| DELETE | карта есть | + | ACTIVE → DELETED | TC-EQ-C05 |
| DELETE | уже DELETED | − | повторный DELETE | TC-EQ-C11 |
| reserve | 0 < amount ≤ balance | + | amount=`100000` при B=`200000` | TC-EQ-C06 |
| reserve | amount > balance | − | amount=`200001` | TC-EQ-C12 |

---

## 2. Граничные значения

Тройка OFF− / ON / OFF+ для каждой границы.

| Граница | OFF− | ON | OFF+ | Ожидание | Кейсы |
|---|---:|---:|---:|---|---|
| dailyLimit | 99999 | 100000 | 100001 | OK / OK / `61` | TC-BV-A01…A03 |
| monthlyLimit | 499999 | 500000 | 500001 | OK / OK / `61` | TC-BV-A04…A05 |
| balance | 199999 | 200000 | 200001 | OK / OK / `51` | TC-BV-A06…A07 |
| expiry (now=0926) | 0826 | 0926 | 1026 | `54` / OK / OK | TC-BV-A08…A09 |
| длина PAN | 15 | 16 | 17 | 4xx / 200 / 4xx | TC-BV-C02 |
| reserve vs B=200000 | 199999 | 200000 | 200001 | 200 / 200 / 4xx | TC-BV-C01 |

---

## 3. Pairwise (PICT)

### 3.1 Обоснование 2wise

Модель Authorization: 4×3×3×3×3×3×4 = **3888** комбинаций. Полный перебор не укладывается в объём практики и дублирует проверки, где система останавливается на раннем шаге алгоритма.

2-wise (PICT) покрывает **каждую пару значений любых двух параметров** хотя бы один раз. Для declinе-логики ТЗ достаточно: отказ определяется одной доминирующей проверкой (статус, expiry, daily, monthly, balance), а пары «статус x expiry», «daily x balance», «terminal x mcc» - типичные места дефектов интеграции полей запроса.

3-wise и выше не гарантируются: взаимодействие трёх факторов (например, одновременное равенство дневного и месячного лимита при нехватке баланса) pairwise может не собрать в одной строке.

Ограничения модели отсекают невозможные сочетания: при `status != ACTIVE` и при `expiry = expired` алгоритм не доходит до лимитов, поэтому `amount_vs_*` фиксируется в `below`.

### 3.2 Ручные сочетания (сверх pairwise)

Добавлены вручную, потому что 2-wise их не обязан дать, а риск высокий:

| ID | Почему не покрывает 2-wise                  | Сочетание | Ожидание |
|---|---------------------------------------------|---|---|
| TC-MAN-01 | тройка daily=ON x monthly=ON x balance=OFF+ | ACTIVE, amount=`200001`, dailyLimit=`200001`, monthlyLimit=`200001` | `51` (отказ по балансу при лимитах на границе) |
| TC-MAN-02 | изоляция месяца: daily OK, monthly OFF+     | ACTIVE, amount=`500001`, dailyLimit=`600000` | `61` по месяцу |
| TC-MAN-03 | карта DELETED (нет в GET CMS)               | authorize PAN soft-deleted | `14` |
| TC-MAN-04 | ранний выход: EXPIRED + amount > все лимиты | status=EXPIRED, amount=`999999` | `54`, баланс не тронут |

---

## 4. Тест-кейсы

Формат: ID, требование, вид, источник, предусловие, шаги, ожидание.

### 4.1 Классы эквивалентности

| ID | Req | Вид | Источник | Предусловие | Шаги | Ожидание |
|---|---|---|---|---|---|---|
| TC-EQ-A01 | TZ-04 §2.1 | − | PAN отсутствует | CMS без PAN `4000000000000001` | `POST /api/internal/authorize` с этим PAN, amount=`10000` | DECLINED `14` |
| TC-EQ-A02 | TZ-04 §2.2 | − | INACTIVE | карта INACTIVE, expiry=`1026` | authorize, amount=`10000` | DECLINED `CARD_INACTIVE` |
| TC-EQ-A03 | TZ-04 §2.2 | − | BLOCKED | карта BLOCKED, expiry=`1026` | authorize, amount=`10000` | DECLINED `CARD_BLOCKED` |
| TC-EQ-A04 | TZ-04 §2.2 | − | EXPIRED (status) | status=`EXPIRED` | authorize, amount=`10000` | DECLINED `54` |
| TC-EQ-A05 | TZ-04 §2.3 | − | expiry < now | ACTIVE, expiry=`0826` | authorize, amount=`10000` | DECLINED `54` |
| TC-EQ-A06 | TZ-04 §2.4 | − | > daily | ACTIVE, usage=0, dailyLimit=`100000` | authorize amount=`100001` | DECLINED `61` |
| TC-EQ-A07 | TZ-04 §2.5 | − | > monthly | ACTIVE, dailyLimit=`600000`, monthlyLimit=`500000` | authorize amount=`500001` | DECLINED `61` |
| TC-EQ-A08 | TZ-04 §2.6 | − | > balance | ACTIVE, balance=`200000`, лимиты ≥ amount | authorize amount=`200001` | DECLINED `51` |
| TC-EQ-A09 | TZ-04 §2.7 | + | все OK | ACTIVE, expiry=`1026`, CMS up | authorize amount=`50000` | APPROVED `00`, RRN 12 цифр, authCode 6 символов; баланс −50000 |
| TC-EQ-A10 | TZ-04 §5 | − | CMS down | Authorization поднят, CMS недоступен | authorize валидным PAN | DECLINED `05` `ISSUER_TIMEOUT` |
| TC-EQ-C01 | TZ-05 §2 | + | валидный create | сервис поднят | `POST /api/cards` BIN=`400000`, name=`IVAN IVANOV`, limits ≥ 0 | 201, PAN 16 цифр, status=ACTIVE |
| TC-EQ-C02 | TZ-05 §2 | − | невалидный BIN | — | POST /cards, BIN=`40A` | 4xx, карта не создана |
| TC-EQ-C03 | TZ-05 §2 | + | карта есть | карта создана, известен PAN | `GET /api/cards/{pan}` | 200, поля карты |
| TC-EQ-C04 | TZ-05 §2 | − | карты нет | — | GET `/api/cards/4000000000000000` | 404 |
| TC-EQ-C05 | TZ-05 §2 | + | soft-delete | карта ACTIVE | `DELETE /api/cards/{pan}` | 200; повторный GET → 404 |
| TC-EQ-C06 | TZ-05 §5 | + | reserve ≤ B | balance=`200000` | `POST .../reserve` amount=`100000` | 200, balance=`100000` |
| TC-EQ-C07 | TZ-05 §2 | − | пустое имя | — | POST /cards, `cardholderName=""` | 4xx |
| TC-EQ-C08 | TZ-05 §2 | − | лимит < 0 | — | POST /cards, `dailyLimit=-1` | 4xx |
| TC-EQ-C09 | TZ-05 §2 | + | PATCH status | карта ACTIVE | PATCH `{ "status": "BLOCKED" }` | 200, status=BLOCKED |
| TC-EQ-C10 | TZ-05 §2 | − | неизвестный status | карта есть | PATCH `{ "status": "FOO" }` | 4xx |
| TC-EQ-C11 | TZ-05 §2 | − | повторный DELETE | карта уже DELETED | DELETE повторно | 404 |
| TC-EQ-C12 | TZ-05 §5 | − | reserve > B | balance=`200000` | reserve amount=`200001` | 4xx, баланс не изменён |

### 4.2 Граничные значения

| ID | Req | Вид | Источник | Предусловие | Шаги | Ожидание |
|---|---|---|---|---|---|---|
| TC-BV-A01 | TZ-04 §2.4 | + | daily ON | dailyLimit=`100000`, usage=0 | authorize amount=`100000` | `00` |
| TC-BV-A02 | TZ-04 §2.4 | − | daily OFF+ | то же | amount=`100001` | `61` |
| TC-BV-A03 | TZ-04 §2.4 | + | daily OFF− | то же | amount=`99999` | `00` |
| TC-BV-A04 | TZ-04 §2.5 | + | monthly ON | monthlyLimit=`500000`, dailyLimit ≥ amount | amount=`500000` | `00` |
| TC-BV-A05 | TZ-04 §2.5 | − | monthly OFF+ | то же | amount=`500001` | `61` |
| TC-BV-A06 | TZ-04 §2.6 | + | balance ON | balance=`200000` | amount=`200000` | `00`, balance → 0 |
| TC-BV-A07 | TZ-04 §2.6 | − | balance OFF+ | balance=`200000` | amount=`200001` | `51` |
| TC-BV-A08 | TZ-04 §2.3 | − | expiry OFF− | now=`0926`, ACTIVE | expiryDate=`0826`, amount=`10000` | `54` |
| TC-BV-A09 | TZ-04 §2.3 | + | expiry ON | now=`0926` | expiryDate=`0926`, amount=`10000` | `00` |
| TC-BV-C01 | TZ-05 §5 | +/− | reserve ON / OFF+ | balance=`200000` | reserve `200000`; затем на копии карты `200001` | 200 / 4xx |
| TC-BV-C02 | TZ-05 PAN | −/+ | длина PAN | карта с PAN 16 цифр | GET длины 15; GET 16; GET 17 | 4xx / 200 / 4xx |

### 4.3 Pairwise Authorization

Общие **предусловие и шаги** для TC-PW-01…25: подготовить карту со `status` и `expiry` строки; usage=0; выставить лимиты/баланс так, чтобы amount попал в below/equal/above; `POST /api/internal/authorize` с `terminalType` и `mcc` строки.

| ID | Вид | status | daily | monthly | balance | expiry | terminal | mcc | Ожидание |
|---|---|---|---|---|---|---|---|---|---|
| TC-PW-01 | − | EXPIRED | below | below | below | valid | pos | restaurant | `54` |
| TC-PW-02 | − | BLOCKED | below | below | below | current_month | atm | grocery | CARD_BLOCKED |
| TC-PW-03 | − | INACTIVE | below | below | below | valid | ecom | grocery | CARD_INACTIVE |
| TC-PW-04 | − | INACTIVE | below | below | below | expired | atm | travel | CARD_INACTIVE |
| TC-PW-05 | − | ACTIVE | above | above | above | current_month | ecom | electronics | `61` |
| TC-PW-06 | − | INACTIVE | below | below | below | current_month | pos | electronics | CARD_INACTIVE |
| TC-PW-07 | − | INACTIVE | below | below | below | valid | atm | restaurant | CARD_INACTIVE |
| TC-PW-08 | − | EXPIRED | below | below | below | expired | pos | grocery | `54` |
| TC-PW-09 | − | BLOCKED | below | below | below | expired | ecom | restaurant | CARD_BLOCKED |
| TC-PW-10 | − | ACTIVE | above | equal | equal | valid | pos | grocery | `61` |
| TC-PW-11 | − | BLOCKED | below | below | below | valid | pos | travel | CARD_BLOCKED |
| TC-PW-12 | − | ACTIVE | equal | above | equal | current_month | atm | travel | `61` |
| TC-PW-13 | + | ACTIVE | equal | equal | equal | current_month | ecom | restaurant | `00` |
| TC-PW-14 | − | ACTIVE | below | equal | above | valid | atm | travel | `51` |
| TC-PW-15 | − | ACTIVE | above | above | below | valid | atm | travel | `61` |
| TC-PW-16 | − | ACTIVE | equal | below | above | valid | pos | electronics | `51` |
| TC-PW-17 | + | ACTIVE | below | equal | equal | current_month | atm | electronics | `00` |
| TC-PW-18 | − | EXPIRED | below | below | below | current_month | ecom | travel | `54` |
| TC-PW-19 | + | ACTIVE | equal | equal | below | valid | atm | electronics | `00` |
| TC-PW-20 | − | ACTIVE | below | above | above | current_month | pos | restaurant | `61` |
| TC-PW-21 | − | BLOCKED | below | below | below | expired | pos | electronics | CARD_BLOCKED |
| TC-PW-22 | − | ACTIVE | above | below | equal | valid | atm | restaurant | `61` |
| TC-PW-23 | − | ACTIVE | equal | above | above | current_month | atm | grocery | `61` |
| TC-PW-24 | − | EXPIRED | below | below | below | current_month | atm | electronics | `54` |
| TC-PW-25 | − | ACTIVE | below | below | below | expired | atm | grocery | `54` |

### 4.4 Pairwise Card-Management

Общие **предусловие и шаги** для TC-PW-C01…29: если `pan_exists=yes` — создать карту со `status` и PAN длины `pan_len`; если `no` — взять несуществующий PAN. `create`/`generate` — POST без существующего PAN. `reserve` — amount относительно текущего баланса (below/equal/above). 

| ID | Вид | operation | bin | status | pan_exists | pan_len | amount | count | Ожидание |
|---|---|---|---|---|---|---|---|---|---|
| TC-PW-C01 | + | patch | valid | INACTIVE | yes | 16 | below | one | 200 |
| TC-PW-C02 | − | patch | valid | ACTIVE | yes | 17 | below | one | 4xx |
| TC-PW-C03 | − | delete | valid | INACTIVE | yes | 15 | below | one | 4xx |
| TC-PW-C04 | − | delete | valid | ACTIVE | no | 16 | below | one | 404 |
| TC-PW-C05 | − | patch | valid | BLOCKED | yes | 15 | below | one | 4xx |
| TC-PW-C06 | − | delete | valid | BLOCKED | yes | 17 | below | one | 4xx |
| TC-PW-C07 | − | reserve | valid | BLOCKED | yes | 16 | above | one | 4xx |
| TC-PW-C08 | + | get | valid | BLOCKED | yes | 16 | below | one | 200 |
| TC-PW-C09 | + | delete | valid | EXPIRED | yes | 16 | below | one | 200 |
| TC-PW-C10 | + | reserve | valid | INACTIVE | yes | 16 | equal | one | 200 |
| TC-PW-C11 | − | get | valid | EXPIRED | yes | 15 | below | one | 4xx |
| TC-PW-C12 | + | reserve | valid | EXPIRED | yes | 16 | below | one | 200 |
| TC-PW-C13 | − | reserve | valid | INACTIVE | yes | 16 | above | one | 4xx |
| TC-PW-C14 | − | reserve | valid | ACTIVE | no | 16 | above | one | 404 |
| TC-PW-C15 | − | get | valid | ACTIVE | yes | 17 | below | one | 4xx |
| TC-PW-C16 | − | generate | valid | ACTIVE | no | 16 | below | zero | 4xx |
| TC-PW-C17 | − | reserve | valid | EXPIRED | yes | 16 | above | one | 4xx |
| TC-PW-C18 | − | delete | valid | ACTIVE | yes | 15 | below | one | 4xx |
| TC-PW-C19 | + | generate | valid | ACTIVE | no | 16 | below | hundred | 200 |
| TC-PW-C20 | + | reserve | valid | EXPIRED | yes | 16 | equal | one | 200 |
| TC-PW-C21 | − | patch | valid | ACTIVE | no | 16 | below | one | 404 |
| TC-PW-C22 | − | get | valid | INACTIVE | yes | 17 | below | one | 4xx |
| TC-PW-C23 | + | reserve | valid | BLOCKED | yes | 16 | equal | one | 200 |
| TC-PW-C24 | − | get | valid | ACTIVE | no | 16 | below | one | 404 |
| TC-PW-C25 | − | create | invalid | ACTIVE | no | 16 | below | one | 4xx |
| TC-PW-C26 | + | create | valid | ACTIVE | no | 16 | below | one | 201 |
| TC-PW-C27 | + | generate | valid | ACTIVE | no | 16 | below | one | 200 |
| TC-PW-C28 | − | reserve | valid | ACTIVE | no | 16 | equal | one | 404 |
| TC-PW-C29 | − | patch | valid | EXPIRED | yes | 17 | below | one | 4xx |

### 4.5 Ручные сочетания

| ID | Req | Вид | Источник | Предусловие | Шаги | Ожидание |
|---|---|---|---|---|---|---|
| TC-MAN-01 | TZ-04 §2.6 | − | тройка вручную | ACTIVE, daily=monthly=`200001`, balance=`200000` | authorize amount=`200001` | `51` |
| TC-MAN-02 | TZ-04 §2.5 | − | изоляция месяца | ACTIVE, dailyLimit=`600000`, monthlyLimit=`500000` | authorize amount=`500001` | `61` |
| TC-MAN-03 | TZ-04 §2.1 | − | DELETED как «нет карты» | карта soft-deleted | authorize её PAN | `14` |
| TC-MAN-04 | TZ-04 §2.2 | − | EXPIRED доминирует над лимитами | status=EXPIRED, balance большой | authorize amount=`999999` | `54`, баланс без изменений |
