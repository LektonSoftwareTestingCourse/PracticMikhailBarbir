# Practice 4: «Unit-тесты для сервисов СМП: JUnit 5, Mockito, JaCoCo»

> **Модуль**: 4 — Unit-тестирование на Java / JUnit 5
> **Тип**: Содержательная практика (воркшоп)
> **Артефакт**:  Unit-тесты на JUnit 5 (7 баллов)
> **Проверка**: CI (`java-services`), JaCoCo-отчёты, ручная проверка кода
> **Длительность**: 1,5 астрономических часа (90 мин)
> **Формат**: Постановка → демонстрация → самостоятельная работа → самопроверка и разбор
> **Связь с лекциями**: лекция 7 «Unit-тестирование: принципы FIRST, AAA, test doubles» и лекция 8 «Качество unit-тестов: четыре столпа, покрытие, maintainability» (слайды в репозитории презентаций: `slides/module-04/lecture-07.md`, `slides/module-04/lecture-08.md`)

---

## Цель практики

Написать unit-тесты для четырёх сервисов СМП — Gateway, Switch, Authorization, Card Management — по принципам FIRST и AAA, с использованием Mockito и параметризованных тестов. Настроить сбор отчётов JaCoCo по каждому модулю и добиться покрытия **по ветвям (branch coverage) не менее 70% для сервиса Switch**.

**Образовательная задача**: студент переходит от техник тест-дизайна модуля 2 к их воплощению в коде — превращает классы эквивалентности и граничные значения в параметризованные тесты, изолирует зависимости через моки, учится читать отчёт JaCoCo как карту дыр в тестировании и настраивает Quality Gate в CI.

**Ограничение задания**: тестируется только бизнес-логика четырёх указанных сервисов. Интеграционные тесты с Testcontainers и E2E-сценарии входят в модули 5–7 и в данную практику не включаются.

---

## Предварительные требования

| Компонент | Назначение | Проверка |
|---|---|---|
| JDK 21 | сборка и запуск тестов | `java -version` |
| Maven | сборка проекта | `mvn -version` |
| Docker Desktop | запуск СМП (для понимания контрактов) | `docker compose version` |
| Git | ветка `practice-4-{surname}`, коммиты артефакта | `git --version` |

Требуемые входные данные и материалы:

- репозиторий СМП `Practic{Имя}{Фамилия}` — исходный код сервисов в `services/{service}/src/main/java/`;
- технические задания: `tz/03-gateway.md`, `tz/04-authorization.md`, `tz/05-card-management.md`, `tz/06-switch.md` — для понимания бизнес-правил;
- лекция 7 и лекция 8 модуля 4 — AAA, FIRST, CQS, JaCoCo, четыре столпа.

---

## Структура практики (тайминг)

| Время | Блок | Назначение |
|:---:|---|---|
| 0:00–0:10 | Цели, охват, критерии сдачи | Постановка задания, состав артефакта, критерии приёмки |
| 0:10–0:25 | Демонстрация: три теста на Authorization | Преподаватель пишет `AuthServiceImplSimpleTest` на экране |
| 0:25–0:35 | Демонстрация: JaCoCo и Quality Gate | Настройка `pom.xml`, генерация отчёта, разбор жёлтых ромбиков |
| 0:35–0:75 | Самостоятельная работа | Студенты пишут тесты для своих модулей |
| 0:75–0:90 | Самопроверка, разбор типовых ошибок | Проверка критериев, типовые дефекты тестов |

---

## Часть 1. Цели, охват, критерии сдачи (0:00–0:10)

### Что создаётся

Набор unit-тестов для четырёх сервисов СМП. Тесты размещаются в стандартной структуре Maven:

```
services/{service}/src/test/java/...
```

| Сервисы: Gateway, Switch, Authorization, Card Management

### Критерии сдачи (приёмка)

Для сдачи артефакта должны быть выполнены **все** условия:

1. **Зелёный билд** в CI: job `java-services` завершается успешно для всех четырёх модулей.
2. **JaCoCo-отчёт** построен для каждого из четырёх модулей и доступен как артефакт сборки.
3. **Покрытие по ветвям (branch coverage) сервиса Switch ≥ 70%** — проверяется по XML-отчёту JaCoCo, отс
4. Код тестов соответствует принципам лекций: AAA, FIRST, CQS, читаемые имена, параметризованные тесты для классов эквивалентности.

> **Баллы начисляются только при наличии всех компонентов задания**: отсутствие любого из них делает артефакт неполным.

---

## Часть 2. Демонстрация: три теста на Authorization (0:10–0:25)

Преподаватель на экране показывает тестовый класс `AuthServiceImplSimpleTest` для сервиса Authorization — три теста, которые покрывают ключевые сценарии и демонстрируют приёмы лекций.

### Шаг 1. Структура класса и фикстуры

```java
@ExtendWith(MockitoExtension.class)
class AuthServiceImplSimpleTest {

    private static final String PAN = "4000001234567890";
    private static final String BIN = "400000";
    private static final String ISSUER_ID = "ISS001";
    private static final BigDecimal AMOUNT = new BigDecimal("1000");
    private static final Instant REQUEST_TIME = Instant.now();

    @Mock
    private LimitUsageRepository limitUsageRepository;

    @Mock
    private AuthorizationEventNotifier eventNotifier;

    @Mock
    private BinLookupClient binLookupClient;

    @Mock
    private CardManagementClient cardManagementClient;

    private AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        authService = new AuthServiceImpl(
                limitUsageRepository,
                eventNotifier,
                binLookupClient,
                cardManagementClient);
    }
}
```

Разбор:

- `@ExtendWith(MockitoExtension.class)` — подключен Mockito к JUnit 5; моки создаются перед каждым тестом.
- `@Mock` — создание моков зависимостей; `@InjectMocks` не используется — объект создаётся явно в `@BeforeEach`, чтобы новая зависимость давала ошибку компиляции, а не NPE в рантайме.
- Константы вынесены в начало класса — читаемость и отсутствие магических чисел.
- `REQUEST_TIME` фиксируется один раз: `AuthorizationResponse` вычисляет длительность через `Instant.now()`, и при разрыве более ~24,8 дней возникает переполнение `int`. Это деталь реализации, о которой нужно знать.

### Шаг 2. Первый тест — happy path

```java
@Test
void shouldApproveAuthorizationWhenCardIsActiveAndSufficientFunds() {
    // Arrange
    CardModel card = activeCard();
    when(cardManagementClient.getCard(PAN)).thenReturn(card);
    when(binLookupClient.getIssuerId(PAN)).thenReturn(Optional.of(ISSUER_ID));
    when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any())).thenReturn(1);

    // Act
    AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

    // Assert
    assertThat(response.status()).isEqualTo("APPROVED");
    assertThat(response.rrn()).isNotBlank();
    assertThat(response.authCode()).isNotBlank();
    verify(eventNotifier).notify(any(AuthorizationEvent.class));
}
```

Разбор:

- Имя `shouldApproveAuthorizationWhenCardIsActiveAndSufficientFunds` — спецификация: условие и ожидание.
- AAA явно разделены комментариями.
- `when(...)` — настройка stub-поведения для запросов (CQS): `getCard`, `getIssuerId`, `upsertLimitUsage` возвращают значения.
- `verify(eventNotifier).notify(...)` — проверка команды: `notify` ничего не возвращает, её эффект — отправленное событие. Verify здесь уместен и необходим.
- `activeCard()` и `validRequest()` — приватные фабричные методы (Object Mother), чтобы Arrange читался как проза.

### Шаг 3. Параметризованный тест — неактивные статусы карты

```java
@ParameterizedTest(name = "status={0}")
@EnumSource(value = CardModelStatus.class, mode = EnumSource.Mode.EXCLUDE, names = {"ACTIVE"})
void shouldDeclineWhenCardStatusIsNotActive(CardModelStatus status) {
    // Arrange
    CardModel card = cardWithStatus(status);
    when(cardManagementClient.getCard(PAN)).thenReturn(card);
    when(binLookupClient.getIssuerId(PAN)).thenReturn(Optional.of(ISSUER_ID));

    // Act
    AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

    // Assert
    assertThat(response.status()).isEqualTo("DECLINED");
    verify(eventNotifier).notify(any(AuthorizationEvent.class));
}
```

Разбор:

- `@EnumSource` с режимом `EXCLUDE` — элегантное решение: тест прогонится на всех статусах, кроме `ACTIVE`, и автоматически подхватит новый статус, если он появится в enum.
- Каждая строка в отчёте — отдельный прогон; при падении видно, какой именно статус сломан.
- Связь с модулем 2: это прямое воплощение классов эквивалентности — «неактивная карта» как класс, где все не-ACTIVE статусы равнозначны.

### Шаг 4. Тест на исключение — карта не найдена

```java
@Test
void shouldDeclineWhenCardNotFound() {
    // Arrange
    when(cardManagementClient.getCard(PAN)).thenThrow(new CardNotFoundException(PAN));

    // Act
    AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

    // Assert
    assertThat(response.status()).isEqualTo("DECLINED");
    assertThat(response.declineReason()).isEqualTo("CARD_NOT_FOUND");
    verify(eventNotifier).notify(any(AuthorizationEvent.class));
}
```

Разбор:

- `thenThrow` — stub, который бросает исключение. Тест проверяет, что сервис корректно обрабатывает `CardNotFoundException` и возвращает `DECLINED` с конкретной причиной.
- Проверяется и статус, и код причины — две проверки, но одна логическая история: «отказ по неизвестной карте корректно полностью».

### Фабричные методы (Object Mother)

```java
private AuthorizationRequest validRequest() {
    return new AuthorizationRequest(
            "0100", "000001", PAN, "000000",
            AMOUNT, "643", REQUEST_TIME,
            "TERM0001", "POS", "MERCH0000000002", "5045", "ACQ002", ISSUER_ID
    );
}

private CardModel activeCard() {
    return cardWithStatus(CardModelStatus.ACTIVE);
}

private CardModel cardWithStatus(CardModelStatus status) {
    return new CardModel(
            UUID.randomUUID(), PAN, BIN, "IVAN IVANOV",
            YearMonth.of(2027, 12), status, "643",
            new BigDecimal("100000"), new BigDecimal("500000"), new BigDecimal("10000"),
            ISSUER_ID, Instant.now()
    );
}
```

Правило: фабричные методы живут в том же классе, рядом с тестами. Имя метода — сценарий, который он создаёт.

---

## Часть 3. Демонстрация: JaCoCo и Quality Gate (0:25–0:35)

### Настройка JaCoCo в `pom.xml` модуля

JaCoCo поключаем в POM  через `pluginManagement` — цели `prepare-agent` и `report` применяются ко всем модулям автоматически. Для модуля switch нужно добавить Quality Gate — цель `check`:

```xml
<plugin>
    <groupId>org.jacoco</groupId>
    <artifactId>jacoco-maven-plugin</artifactId>
    <executions>
        <execution>
            <id>check</id>
            <phase>verify</phase>
            <goals>
                <goal>check</goal>
            </goals>
            <configuration>
                <rules>
                    <rule>
                        <element>BUNDLE</element>
                        <limits>
                            <limit>
                                <counter>BRANCH</counter>
                                <value>COVEREDRATIO</value>
                                <minimum>0.70</minimum>
                            </limit>
                        </limits>
                    </rule>
                </rules>
            </configuration>
        </execution>
    </executions>
</plugin>
```

Где `BUNDLE` — уровень всего модуля; `BRANCH` + `COVEREDRATIO` — метрика покрытия ветвей; `minimum 0.70` — порог 70%.

> **Важно**: цель `check` привязана к фазе `verify`, поэтому полный прогон — `mvn verify`, а не `mvn test`.

### Генерация и чтение отчёта

```bash
# из корня проекта
mvn -f services/pom.xml -P switch verify
# или локально в модуле
mvn -f services/switch/pom.xml verify
```

Отчёт появляется в `services/{service}/target/site/jacoco/`:

- `index.html` — для человека: зелёные/красные строки, жёлтые ромбики на ветвлениях.
- `jacoco.xml` — для машины: его читает CI.

**Жёлтый ромбик** — ветвление пройдено частично. Это конкретное место для нового теста.

### Настройка GitHub Actions

В `.github/workflows/ci.yml` job `java-services` уже запускает `mvn install` для каждого модуля. Чтобы JaCoCo-отчёт попадал в артефакты сборки, добавляется шаг загрузки:

```yaml
- name: Upload JaCoCo report
  if: always()
  uses: actions/upload-artifact@v4
  with:
    name: jacoco-report-${{ matrix.service }}
    path: services/${{ matrix.service }}/target/site/jacoco/
```

Для Quality Gate в CI достаточно заменить `mvn install` на `mvn verify` — тогда при покрытии ниже 70% сборка упадёт.

---

## Часть 4. Самостоятельная работа (0:35–0:75)

Студенты пишут unit-тесты для четырёх модулей. Рекомендуемый порядок: Authorization → Switch → Card Management → Gateway.

### Общие правила для всех модулей

1. **Один тест — одно поведение**: имя `should...When...`, один Act, логически связанные assert'ы.
2. **FIRST**: миллисекунды на тест, изоляция, детерминизм, самопроверка, тесты пишутся вместе с кодом.
3. **CQS**: stub для запросов (возвращают значение), mock + verify для команд (меняют состояние). Никаких verify на stub'ах.
4. **Параметризация**: `@CsvSource` для таблиц «вход → ожидание», `@EnumSource` для перечислений, `@MethodSource` для сложных объектов.
5. **Фабричные методы**: `activeCard()`, `blockedCard()`, `request(pan, amount)` — чтобы Arrange читался как сценарий.
6. **Никаких if/loop в теле теста** — ветвление означает скрытые сценарии; используйте параметризацию.


## Часть 5. Самопроверка и разбор типовых ошибок (0:75–0:90)

### Лист самопроверки

- [ ] Все тесты проходят: `mvn test` — зелёный
- [ ] JaCoCo-отчёт построен для каждого модуля: `mvn verify` в `services/{service}/target/site/jacoco/`
- [ ] Branch coverage Switch ≥ 70%
- [ ] Имена тестов — спецификации (`should...When...`)
- [ ] AAA явно разделены; один Act на тест
- [ ] Нет verify на stub'ах (только на командах с внешними эффектами)
- [ ] Параметризованные тесты используются для классов эквивалентности
- [ ] Нет `Thread.sleep`, статического состояния, `if/loop` в тестах
- [ ] Фабричные методы вынесены и названы по сценарию
- [ ] CI зелёный: `java-services` + артефакты JaCoCo доступны



## Оценка артефакта (7 баллов)

| Компонент | Балл |
|---|---:|
| Тесты для Authorization  | 2 |
| Тесты для Switch (branch coverage ≥ 70%) | 2 |
| Тесты для Card Management | 1 |
| Тесты для Gateway  | 1 |
| JaCoCo-отчёты в CI + Quality Gate на Switch | 1 |
| **Итого** | **7** |

> **Баллы начисляются только при наличии всех компонентов задания**: отсутствие любого из них делает артефакт неполным.

---

## Сдача артефакта

**Workflow сдачи**: см. `../../../Practic/docs/submission-guide.md`.

- Ветка: `practice-4-{surname}`
- Сдача через GitHub Issue с label `practice-4`
- В теле Issue — ссылки на:
  - зелёный прогон `java-services` в Actions;
  - артефакты JaCoCo (четыре отчёта);
  - скриншот или ссылка на `jacoco.xml` сервиса Switch с покрытием ≥ 70%.
- Проверка: CI (зелёный билд + отчёты) + ручная проверка кода тестов

---


## Связанные документы

- Лекция 7 «Unit-тестирование: принципы FIRST, AAA, test doubles» — `slides/module-04/lecture-07.md`
- Лекция 8 «Качество unit-тестов: четыре столпа, покрытие, maintainability» — `slides/module-04/lecture-08.md`
-`../../../Practic/tz/04-authorization.md` — ТЗ сервиса Authorization
- `../../../Practic/tz/05-card-management.md` — ТЗ сервиса Card Management
- `../../../Practic/docs/submission-guide.md` — сдача артефактов
