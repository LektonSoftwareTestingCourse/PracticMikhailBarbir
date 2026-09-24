# Тест дизайн для Authorization и Card Management

## 1. Классы эквивалентности

### Authorization

| Условие | + класс | − класс | Ожидание − |
|---|---|---|---|
| PAN | есть в CMS | нет в CMS | DECLINED `14` |
| status | ACTIVE | INACTIVE / BLOCKED / EXPIRED | `CARD_INACTIVE` / `CARD_BLOCKED` / `54` |
| expiryDate | ≥ now (MMYY) | < now | `54` |
| daily / monthly | used+amount ≤ limit | > limit | `61` |
| balance | amount ≤ balance | amount > balance | `51` |
| CMS | доступен | недоступен | `05` ISSUER_TIMEOUT |

### Card-Management

| Поле | + класс              | − класс | Ожидание − |
|---|----------------------|---|---|
| BIN / тело create | валидные поля        | BIN≠6 цифр, пустое имя, лимиты < 0 | 4xx |
| PAN | 16 цифр, есть в БД   | нет в БД | 404 |
| status | ACTIVE…EXPIRED       | неизвестный | 4xx |
| DELETE | имеется -> DELETED   | уже DELETED | 404 |
| reserve | 0 < amount ≤ balance | amount > balance | 4xx |

## 2. Граничные значения
Исходные значения: ACTIVE, expiry 1026, daily=100000, monthly=500000, balance=200000, usage=0.

| Граница |   OFF− |     ON |   OFF+ | Ожидание |
|---|-------:|-------:|-------:|---|
| dailyLimit |  99999 | 100000 | 100001 | OK / OK / `61` |
| monthlyLimit | 499999 | 500000 | 500001 | OK / OK / `61` |
| balance | 199999 | 200000 | 200001 | OK / OK / `51` |
| expiry |   0826 |   1026 |   1126 | `54` / OK / OK |
| PAN length |     15 |     16 |     17 | 4xx / OK / 4xx |
| reserve | 199999 |      200000 | 200001 | 200 / 200 / 4xx |

## 3. Pairwise (PICT)
model.txt и результат cases.txt лежат в папке pict

## 4. Тест-кейсы
### 4.1 По классам эквивалентности

| ID | Req | Вид | Источник | Суть | Ожидание |
|---|---|---|---|---|---|
| TC-EQ-A01 | TZ-04 | − | PAN отсутствует | authorize неизвестный PAN | `14` |
| TC-EQ-A02 | TZ-04 | − | INACTIVE | authorize | CARD_INACTIVE |
| TC-EQ-A03 | TZ-04 | − | BLOCKED | authorize | CARD_BLOCKED |
| TC-EQ-A04 | TZ-04 | − | EXPIRED status | authorize | `54` |
| TC-EQ-A05 | TZ-04 | − | expiry < now | authorize | `54` |
| TC-EQ-A06 | TZ-04 | − | > daily | authorize | `61` |
| TC-EQ-A07 | TZ-04 | − | > monthly | authorize | `61` |
| TC-EQ-A08 | TZ-04 | − | > balance | authorize | `51` |
| TC-EQ-A09 | TZ-04 | + | все OK | authorize | APPROVED `00`, RRN, authCode |
| TC-EQ-A10 | TZ-04 | − | CMS down | authorize | `05` |
| TC-EQ-C01 | TZ-05 | + | валидный create | POST /cards | 201, ACTIVE |
| TC-EQ-C02 | TZ-05 | − | невалидный BIN | POST /cards | 4xx |
| TC-EQ-C03 | TZ-05 | + | карта есть | GET /cards/{pan} | 200 |
| TC-EQ-C04 | TZ-05 | − | карты нет | GET | 404 |
| TC-EQ-C05 | TZ-05 | + | soft-delete | DELETE | GET → 404 |
| TC-EQ-C06 | TZ-05 | +/− | reserve | amount ≤B / >B | 200 / 4xx |

### 4.2 По граничным значениям
| ID | Req | Вид | Источник | Суть | Ожидание |
|---|---|---|---|---|---|
| TC-BV-A01 | TZ-04 | + | daily ON | amount=100000 | `00` |
| TC-BV-A02 | TZ-04 | − | daily OFF+ | amount=100001 | `61` |
| TC-BV-A03 | TZ-04 | + | monthly ON | amount=500000 | `00` |
| TC-BV-A04 | TZ-04 | − | monthly OFF+ | amount=500001 | `61` |
| TC-BV-A05 | TZ-04 | + | balance ON | amount=200000 | `00` |
| TC-BV-A06 | TZ-04 | − | balance OFF+ | amount=200001 | `51` |
| TC-BV-A07 | TZ-04 | − | expiry OFF− | expiry=0826 | `54` |
| TC-BV-A08 | TZ-04 | + | expiry ON | expiry=0926 | `00` |
| TC-BV-C01 | TZ-05 | +/− | reserve ON/OFF+ | amount=B / B+1 | 200 / 4xx |

### 4.3 Pairwise
| ID | status | daily | monthly | balance | expiry | terminal | mcc | Ожидание |
|---|---|---|---|---|---|---|---|---|
| TC-PW-01 | EXPIRED | below | below | below | valid | pos | restaurant | `54` |
| TC-PW-02 | BLOCKED | below | below | below | current_month | atm | grocery | CARD_BLOCKED |
| TC-PW-03 | INACTIVE | below | below | below | valid | ecom | grocery | CARD_INACTIVE |
| TC-PW-04 | INACTIVE | below | below | below | expired | atm | travel | CARD_INACTIVE |
| TC-PW-05 | ACTIVE | above | above | above | current_month | ecom | electronics | `61` |
| TC-PW-06 | INACTIVE | below | below | below | current_month | pos | electronics | CARD_INACTIVE |
| TC-PW-07 | INACTIVE | below | below | below | valid | atm | restaurant | CARD_INACTIVE |
| TC-PW-08 | EXPIRED | below | below | below | expired | pos | grocery | `54` |
| TC-PW-09 | BLOCKED | below | below | below | expired | ecom | restaurant | CARD_BLOCKED |
| TC-PW-10 | ACTIVE | above | equal | equal | valid | pos | grocery | `61` |
| TC-PW-11 | BLOCKED | below | below | below | valid | pos | travel | CARD_BLOCKED |
| TC-PW-12 | ACTIVE | equal | above | equal | current_month | atm | travel | `61` |
| TC-PW-13 | ACTIVE | equal | equal | equal | current_month | ecom | restaurant | APPROVED `00` |
| TC-PW-14 | ACTIVE | below | equal | above | valid | atm | travel | `51` |
| TC-PW-15 | ACTIVE | above | above | below | valid | atm | travel | `61` |
| TC-PW-16 | ACTIVE | equal | below | above | valid | pos | electronics | `51` |
| TC-PW-17 | ACTIVE | below | equal | equal | current_month | atm | electronics | APPROVED `00` |
| TC-PW-18 | EXPIRED | below | below | below | current_month | ecom | travel | `54` |
| TC-PW-19 | ACTIVE | equal | equal | below | valid | atm | electronics | APPROVED `00` |
| TC-PW-20 | ACTIVE | below | above | above | current_month | pos | restaurant | `61` |
| TC-PW-21 | BLOCKED | below | below | below | expired | pos | electronics | CARD_BLOCKED |
| TC-PW-22 | ACTIVE | above | below | equal | valid | atm | restaurant | `61` |
| TC-PW-23 | ACTIVE | equal | above | above | current_month | atm | grocery | `61` |
| TC-PW-24 | EXPIRED | below | below | below | current_month | atm | electronics | `54` |
| TC-PW-25 | ACTIVE | below | below | below | expired | atm | grocery | `54` |
