# 04 — Plan de implementación

> Fase 4 · 2026-11-07 · Estado: **aprobado por el autor el 2026-11-07**
>
> **Para quien lo ejecute con agentes:** usar `superpowers:subagent-driven-development` (recomendado) o
> `superpowers:executing-plans`, tarea por tarea. Los pasos usan casillas (`- [ ]`) para seguir el avance.

**Objetivo:** construir la API que diseña el RFC (las cinco funciones, su base, sus controles de calidad y su entrega
con un comando), y después su observabilidad, en tareas chicas con el test primero.

**Arquitectura:** Spring Boot 4.1 en capas agrupadas por función (`account`, `instruments`, `portfolio`, `orders`, más
`shared`), con los cálculos en clases de Java puro y el SQL escrito a mano con `JdbcClient` (ADR 0001 y 0002).
Postgres 18 arranca con `database.sql`, y Flyway le aplica los cambios del RFC como versiones 2 a 6. Todo se levanta
con `docker compose up`.

**Tecnologías:**
- Java 25 y Spring Boot 4.1.2, que trae Spring Framework 7.0.10, Jackson 3, JUnit 6 y Testcontainers 2.0.5.
- Postgres 18 y Flyway 12.4.0.
- springdoc-openapi 3.2.0, y Micrometer con Prometheus y Grafana.
- Maven 3.9.16 con wrapper.
- Spotless con google-java-format 2.0.0, Error Prone 2.52.0, NullAway 0.14.3 y JaCoCo 0.8.16.

**Spec:** [`03-rfc.md`](03-rfc.md) y sus ADR ([`adr/`](adr/)). Los casos de prueba son los del PRD
([`02-prd.md`](02-prd.md)), y cada tarea cita los suyos. Quien ejecuta una tarea lee la tarea y las secciones del RFC
que nombra.

## Restricciones globales

Valen para todas las tareas, aunque una tarea no las repita.

**Stack y estructura**
- Java 25 y Spring Boot 4.1.2 (`spring-boot-starter-parent`). Maven 3.9.16 con wrapper (`./mvnw`), en `api/`.
- El paquete base es `com.cocos.challenge`. Hay un paquete por función (`account`, `instruments`, `portfolio`,
  `orders`) más `shared`, con los subpaquetes de la "Estructura de archivos".
- Las dependencias van de cada función hacia `account` e `instruments`, nunca al revés (ADR 0001).
- Los cálculos y las reglas van en clases de Java puro, sin anotaciones de Spring. Se registran como beans en la
  `@Configuration` de su paquete.

**Plata y valores**
- Plata y precios en `BigDecimal`, nunca en `double`. Las divisiones van con `MathContext.DECIMAL128`, con un solo
  redondeo al final, a 2 decimales y con `RoundingMode.HALF_UP`. La cantidad por monto se corta con
  `RoundingMode.DOWN` (RFC 4.5).
- Sin números mágicos ni textos sueltos:
  - lo configurable va en `application.yml`, con `@ConfigurationProperties` validadas bajo `cocos.*`;
  - el resto son constantes con nombre;
  - los textos para el usuario van en `messages.properties`, en español;
  - lados, tipos, estados y motivos son enums.

**Pedidos y errores**
- JSON estricto: `spring.jackson.deserialization.fail-on-unknown-properties: true` y `accept-float-as-int: false`
  (RFC 9).
- Los errores van en Problem Details (RFC 9457), con los `type` del catálogo del RFC 7.3 (`/problems/...`).
- El envío corre en `READ COMMITTED` y toma el turno del usuario con un lock sobre su fila de `users`. El
  `lock_timeout` es de 2 segundos, y si se vence la respuesta es un 503 (RFC 5.2).
- La hora de una orden la pone la base, con `clock_timestamp()`. Las respuestas la muestran en
  `America/Argentina/Buenos_Aires`, en ISO 8601 con la zona (RFC 3.4 y 6.4).

**Tests**
- Los tests nunca tocan Neon. Usan Testcontainers con `postgres:18`, `database.sql` y las migraciones, y la base vuelve
  a los datos originales antes de cada test (RFC 10.2).
- Corren de a uno, con la JVM en UTC (`-Duser.timezone=UTC`).
- `./mvnw verify` frena el build si falla cualquiera de estos controles:
  - Spotless (google-java-format 2.0.0);
  - Error Prone 2.52.0;
  - NullAway 0.14.3, en modo JSpecify, con `@NullMarked` en cada paquete;
  - JaCoCo 0.8.16, que exige el 85 % de las líneas y el 80 % de las ramas.

**Repo y ramas**
- Ninguna credencial en el repo. La base llega por `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` y
  `SPRING_DATASOURCE_PASSWORD` (RFC 9), y `application.yml` no tiene datos de conexión.
- La documentación va en español. El código y los nombres van en inglés, igual que los commits (Conventional Commits,
  con el pie `Co-Authored-By`) y los títulos de los PR. El cuerpo de los PR va en español.
- `feat/phase-5-implementation` y `feat/phase-6-observability` salen de `develop`. Cada tarea va en
  `task/<fase>-<NN>-<nombre>`, sale de la rama de su fase y vuelve con un PR a ella. Siempre con merge commit, nunca
  squash (`CLAUDE.md`).

## Foco de revisión

Son cinco situaciones que el RFC implica, que ningún caso del PRD prueba y que más le pueden molestar a quien use la
API. Cada una tiene su test en la tarea dueña del código.

1. **Un valor de la dirección o de los parámetros que no es un número o un estado válido:** `/users/abc/portfolio`,
   un `userId` de más de 19 dígitos (no entra en un `long`), `limit=x`, `status=FOO` o `orderId=x`. Responde 400 `validation-error`, nunca 500.
   Tests en las tareas 7, 11 y 12.
2. **Un número del cuerpo que no entra en Java ni en la base:** `"quantity": 3000000000`, `"price": 1e400`, o un
   exponente enorme como `"amount": 1e999999999` o `"price": 1e2147483647`. Responde 400 enseguida, nunca 500 ni una
   respuesta que tarda: el número no llega a la huella ni a la división. Tests en las tareas 8 y 9.
3. **Un texto de búsqueda con caracteres especiales de SQL:** `'`, `\` o `%`. Se busca como texto y responde 200,
   nunca 500. Test en la tarea 5.
4. **Un reintento con el monto escrito de otra forma:** `10000`, `10000.00` o `1E+4`. Es el mismo pedido: responde 200
   con la orden original. Tests en la tarea 9, uno para la huella y otro por HTTP.
5. **La API corriendo con la JVM en UTC,** como en un contenedor. Las fechas siguen saliendo en `-03:00`: los tests
   corren con `-Duser.timezone=UTC` (tarea 1) y verifican la zona en las tareas 9 y 12.

## Cómo se trabaja cada tarea

1. Se crea la rama de la tarea desde la de su fase: `git checkout -b task/5-NN-<nombre>`.
2. Se siguen los pasos en orden: el test que falla, verlo fallar, el código mínimo y verlo pasar. Si un paso dice que
   el test "tiene que fallar" y no dice por qué, falla porque todavía no existe lo que el test usa: no compila, o el
   endpoint da 404.
3. Desde `api/`, `./mvnw -B verify` tiene que dar `BUILD SUCCESS`: formato, Error Prone, NullAway, tests y cobertura.
4. Commit, push y PR a la rama de la fase. Al aprobar este plan, el autor autoriza estos commits y push para todas
   las tareas; el merge de cada fase a `develop` lo aprueba él. Desde la tarea 16, el CI tiene que dar verde.
5. Merge con merge commit.

Un test que no levanta Spring ni Postgres se llama `*Test`; uno que los levanta, `*IT`. Los dos corren con
`./mvnw test`, y uno solo, con `./mvnw -B test -Dtest=<Clase>`. Los comandos corren desde `api/`, salvo que el paso diga
otra cosa.

## Estructura de archivos

```text
api/
├── pom.xml, mvnw, mvnw.cmd, .mvn/wrapper/, .mvn/jvm.config, Dockerfile, .dockerignore
└── src/
    ├── main/java/com/cocos/challenge/
    │   ├── CocosChallengeApplication.java
    │   ├── shared/
    │   │   ├── domain/        OrderSide, OrderStatus, Decimals
    │   │   ├── error/         ProblemType, ApiException, InvalidRequestException, FieldViolation, ApiExceptionHandler
    │   │   ├── web/           RequestIds, RequestIdFilter, OpenApiConfiguration
    │   │   ├── validation/    MaxScale, MaxScaleValidator
    │   │   └── display/       DisplayProperties, DisplayFormatter, DisplayConfiguration
    │   ├── account/           AccountEntry, Position, Account, AccountCalculator, AccountRepository, AccountService,
    │   │                      AccountProperties, AccountConfiguration, UserNotFoundException, UserBusyException,
    │   │                      TurnMetrics
    │   ├── instruments/       Instrument, MarketPrice, InstrumentRepository, MarketPriceRepository, InstrumentCatalog
    │   │   ├── search/        SearchProperties, SearchResult, InstrumentSearchService
    │   │   └── web/           InstrumentController, InstrumentSearchResponse, InstrumentSummary
    │   ├── portfolio/         Portfolio, PositionValuation, PortfolioValuator, PortfolioService, PortfolioConfiguration
    │   │   └── web/           PortfolioController, PortfolioResponse, CashResponse, PositionResponse
    │   └── orders/            OrderType, RejectionReason, OrderLimits, StoredOrder, NewOrder, OrderRepository,
    │       │                  OrderNotFoundException, OrdersConfiguration, OrderMetrics, OrderEventLogger
    │       ├── submission/    SubmitOrderCommand, RequestFingerprint, QuantityResolver, ResolvedOrder, SubmissionResult,
    │       │                  OrderSubmissionService, OrderSubmissionTransaction, InvalidOrderException,
    │       │                  ClientOrderIdReusedException
    │       ├── evaluation/    OrderCandidate, OrderDecision, OrderEvaluator, RejectionRule, QuotedInstrumentRule,
    │       │                  EnoughCashRule, EnoughSharesRule
    │       ├── cancellation/  OrderCancellationService, OrderNotCancellableException
    │       ├── listing/       OrderCursor, OrderPage, OrderPageProperties, OrderQueryService
    │       └── web/           OrderController, SubmitOrderRequest, ConsistentOrderRequest,
    │                          ConsistentOrderRequestValidator, OrderResponse, OrderResponseMapper, OrderPageResponse
    ├── main/resources/        application.yml, messages.properties, db/migration/V2__… a V6__…
    └── test/java/com/cocos/challenge/
        ├── support/           PostgresContainer, IntegrationTest, OriginalData, OriginalDataExtension,
        │                      ApiClient, TestData, Concurrency
        └── (los mismos paquetes que main, con sus tests)
docker-compose.yml   scripts/   postman/   observability/   perf/   .github/workflows/ci.yml   README.md
```

Cada paquete y subpaquete lleva su `package-info.java` con `@NullMarked`, el aviso de JSpecify con el que NullAway
revisa ese paquete. Lo que puede faltar se marca con `@Nullable`.

## Diseño del código: SOLID y patrones

| Principio | Cómo se aplica |
|---|---|
| Responsabilidad única | Cada pieza hace una sola cosa. El controller atiende HTTP y valida la forma; el service abre la transacción y ordena los pasos; las calculadoras y las reglas tienen la lógica, en Java puro; el repository ejecuta el SQL; el mapper convierte de dominio a respuesta |
| Abierto y cerrado | Un error nuevo es una subclase de `ApiException` con su `ProblemType`, sin tocar el handler. Un motivo de rechazo nuevo es otra `RejectionRule` en la lista del evaluador, sin tocar el evaluador |
| Sustitución de Liskov | El evaluador recorre las reglas de rechazo sin saber cuál es cuál |
| Segregación de interfaces | `RejectionRule` tiene un solo método |
| Inversión de dependencias | Todo colaborador entra por el constructor, y ninguna clase hace `new` de un colaborador con estado. Hay interfaz donde hay más de una implementación (las reglas). Los repositories son clases, sin una interfaz de una sola implementación, como decidió el ADR 0001 |

| Patrón | Dónde |
|---|---|
| Repository | los `*Repository`, cada uno con su SQL |
| Command | `SubmitOrderCommand`: el pedido ya validado, separado del JSON |
| Cadena de reglas (Strategy) | `OrderEvaluator` con sus `RejectionRule`: gana la primera que rechaza |
| Value Object | `RequestFingerprint`, `OrderCursor`, `ResolvedOrder` y los records del dominio |
| DTO y mapper | los `*Response` y `OrderResponseMapper`: el contrato de la API no depende del dominio |
| Fachada con reintento | `OrderSubmissionService` reintenta una vez la transacción si salta la regla de unicidad (RFC 5.3) |

Los nombres siguen una sola convención:
- las clases son sustantivos, y las que hacen algo terminan en lo que hacen (`*Calculator`, `*Evaluator`,
  `*Resolver`, `*Service`, `*Repository`);
- los métodos que hacen algo son verbos (`calculate`, `evaluate`, `findEntries`, `roundMoney`). Los que devuelven un
  dato o un valor derivado se llaman como el dato, igual que los accessors de un record (`availableCash()`,
  `percentChange(...)`);
- las claves de `messages.properties` son `<área>.<caso>`, con el caso en minúsculas y con guiones
  (`order.quantity-zero`). Solo las familias tienen un nivel más (`problem.<slug>.title`, `order.rejection.<motivo>`);
- los tests cuentan el comportamiento (`rejectsAPurchaseThatNeedsMoreThanTheAvailableCash`).

**Dónde se valida (RFC 7.2).** El controller valida la forma con Bean Validation. Hay dos topes de forma que se
validan en el service: el largo del texto de búsqueda y el tamaño de página. Se validan ahí porque son configurables,
y una anotación no puede leer `application.yml`. Dan el mismo `validation-error`, con su campo.

**Las excepciones no arman respuestas HTTP.** Una excepción de un service lleva su `ProblemType`, la clave de su
texto y sus datos. El handler arma la respuesta y sus encabezados.

## Configuración (`application.yml`)

Cada bloque lo agrega la tarea que lo usa. Así queda al final:

```yaml
spring:
  application.name: cocos-challenge-api                 # tarea 1
  flyway:                                               # tarea 1 (RFC 3.6)
    enabled: false                                      # el compose y los tests lo prenden
    baseline-on-migrate: true
    baseline-version: 1
    baseline-description: database.sql de Cocos
    group: true
  jackson.deserialization:                              # tarea 3 (RFC 9)
    fail-on-unknown-properties: true
    accept-float-as-int: false
  mvc.problemdetails.enabled: true                      # tarea 3 (RFC 7.1)
  messages.fallback-to-system-locale: false             # tarea 3
logging.structured.format.console: ecs                  # tarea 17 (RFC 8.1)
management:                                             # tarea 17 (RFC 8.2 y 9)
  endpoints.web.exposure.include: health, prometheus
  metrics.distribution.percentiles-histogram:
    http.server.requests: true
    orders.turn.wait: true                            # tarea 18
cocos:
  display:                                              # tarea 3
    time-zone: America/Argentina/Buenos_Aires
    locale: es-AR
  account.turn-timeout: 2s                              # tarea 6 (RFC 5.2)
  search:                                               # tarea 5 (RFC 6.6)
    max-results: 20
    max-query-length: 50
  orders.page:                                          # tarea 12 (RFC 6.6)
    default-size: 20
    max-size: 100
```

## Textos (`messages.properties`)

Son todas las claves, agrupadas por la tarea que las agrega. Los textos que muestra el RFC (6.2 y 7) están tal cual;
el resto sigue la misma regla: dicen qué falta o qué está mal, y cómo arreglarlo (GEN-6).

Los argumentos de un mensaje (`{0}`) se pasan ya formateados como texto: los ids, con `String.valueOf`, y la plata,
con `DisplayFormatter.formatMoney`. Así un id no sale como "1.234". Los textos de Bean Validation no llevan
argumentos: los resuelve el `MessageSource` por su clave.

```properties
# Tarea 3: los errores comunes
problem.validation-error.title=El pedido no es válido
problem.invalid-order.title=La orden no se puede armar
problem.user-not-found.title=El usuario no existe
problem.order-not-found.title=La orden no existe
problem.order-not-cancellable.title=La orden no se puede cancelar
problem.client-order-id-reused.title=El identificador ya se usó
problem.user-busy.title=La cuenta está ocupada
problem.internal-error.title=Error inesperado
request.several-errors=El pedido tiene {0} errores: el detalle de cada uno está en errors.
request.malformed=El cuerpo del pedido no es un JSON válido.
request.unknown-field=El campo {0} no existe en este pedido.
request.invalid-value=El valor de {0} no es válido.
error.unexpected=Ocurrió un error inesperado.
# Tarea 5: la búsqueda
search.query-blank=Escribí algo para buscar.
search.query-too-long=El texto de búsqueda puede tener hasta {0} caracteres.
# Tarea 6: el usuario
user.not-found=No existe el usuario {0}.
user.busy=Tu cuenta está procesando otra orden. Intentá de nuevo.
# Tarea 8: el pedido de una orden, la cantidad y los rechazos
order.client-order-id-missing=Falta el identificador de la orden (clientOrderId).
order.instrument-id-missing=Falta el activo (instrumentId).
order.side-missing=Falta el lado: BUY o SELL.
order.type-missing=Falta el tipo: MARKET o LIMIT.
order.side-not-trade=El lado tiene que ser BUY o SELL: los depósitos y retiros no se mandan como orden.
order.quantity-not-positive=La cantidad tiene que ser 1 o más.
order.amount-not-positive=El monto tiene que ser mayor a 0.
order.amount-too-many-decimals=El monto puede tener hasta 2 decimales.
order.amount-too-large=El monto es demasiado grande para una orden.
order.price-not-positive=El precio límite tiene que ser mayor a 0.
order.price-too-many-decimals=El precio límite puede tener hasta 2 decimales.
order.price-too-large=El precio límite es demasiado grande.
order.quantity-and-amount=Mandá la cantidad o el monto, no los dos.
order.quantity-or-amount-missing=Mandá la cantidad o el monto.
order.limit-needs-price=Una orden LIMIT necesita precio límite.
order.market-has-price=Una orden MARKET no lleva precio: se ejecuta al último precio.
order.instrument-is-currency=ARS es la moneda de la cuenta: no se compra ni se vende.
order.quantity-zero=Con ${0} no alcanza para una acción de {1} (${2}).
order.quantity-too-large=Ese monto da más acciones que el máximo de una orden ({0}).
order.no-price-for-amount={0} no cotiza: sin precio no se puede calcular la cantidad de un monto.
order.rejection.insufficient-cash=No alcanzan los pesos disponibles para esta orden.
order.rejection.insufficient-shares=No alcanzan las acciones disponibles para esta venta.
order.rejection.no-market-price=El activo no cotiza.
# Tarea 9: el envío
order.instrument-not-found=No existe el activo {0}.
order.client-order-id-reused=Ese identificador ya se usó en otra orden. Para una orden nueva, generá uno nuevo.
# Tarea 11: la cancelación
order.not-found=No existe la orden {0}.
order.not-cancellable=La orden {0} ya está {1}: solo se cancela una orden NEW.
# Tarea 12: el listado
order-list.limit-out-of-range=El tamaño de página va de 1 a {0}.
order-list.cursor-invalid=La marca de página no es válida: usá el nextCursor de la respuesta anterior.
```

## Datos de referencia

Ids de `database.sql` que usan los tests:

| Activo | Id | Activo | Id |
|---|---|---|---|
| PGR | 3 | PAMP | 47 |
| DOME | 12 | METR | 54 |
| BBAR | 22 | ARS | 66 |
| CARC | 30 | | |
| BMA | 31 | | |
| LOMA | 45 | | |

Los números del usuario 1 son los del PRD (sección 3) y los del RFC (sección 6.2). Con los datos originales, la orden
nueva siempre es la 12.

---

## Parte A — Fase 5: la API

Antes de la tarea 1: `git checkout develop && git pull && git checkout -b feat/phase-5-implementation`, y push.

### Tarea 1: el proyecto, sus controles de calidad y la base de los tests

Rama `task/5-01-project-skeleton`.

**Archivos:**
- Crear `api/pom.xml`, `api/mvnw`, `api/mvnw.cmd`, `api/.mvn/wrapper/maven-wrapper.properties`,
  `api/.mvn/jvm.config`, `api/src/main/java/com/cocos/challenge/CocosChallengeApplication.java`,
  `api/src/main/java/com/cocos/challenge/package-info.java`, `api/src/main/resources/application.yml`.
- Test: `support/PostgresContainer.java`, `support/IntegrationTest.java`, `ApplicationStartupIT.java`.

**Interfaces:**
- Produce:
  - `@IntegrationTest`, la anotación de todos los `*IT`: `@SpringBootTest(webEnvironment = RANDOM_PORT, properties =
    "spring.flyway.enabled=true")`, más `@ImportTestcontainers(PostgresContainer.class)`.
  - `PostgresContainer`: una interfaz con un solo campo, `@ServiceConnection PostgreSQLContainer POSTGRES`
    (`org.testcontainers.postgresql`), con `postgres:18`. Copia `database.sql`, que está en el classpath de los tests,
    a `/docker-entrypoint-initdb.d/01-database.sql` (`MountableFile.forClasspathResource`).
    - Es un campo estático, así que todos los contextos de Spring de la corrida usan el mismo Postgres (RFC 10.2),
      aunque un test cambie una propiedad o espíe un bean. Un `@Bean` crearía un contenedor por contexto.
    - Un test cambia una propiedad con `@TestPropertySource(properties = ...)`, que vale para toda la clase.

- [ ] **Paso 1: armar el proyecto.**
  - **Wrapper:** desde `api/`, `mvn -N org.apache.maven.plugins:maven-wrapper-plugin:3.3.4:wrapper -Dmaven=3.9.16`.
  - **`pom.xml`:**
    - parent `org.springframework.boot:spring-boot-starter-parent:4.1.2`; groupId `com.cocos`, artifactId
      `cocos-challenge-api`, versión `1.0.0-SNAPSHOT`;
    - propiedades: `java.version` 25, `error-prone.version` 2.52.0, `nullaway.version` 0.14.3,
      `google-java-format.version` 2.0.0, `spotless.version` 3.11.0 y `jacoco.version` 0.8.16;
    - dependencias: `spring-boot-starter-webmvc`, `-jdbc`, `-validation`, `-actuator` y `-flyway`, con
      `org.flywaydb:flyway-database-postgresql` y `org.postgresql:postgresql` (runtime);
    - `org.jspecify:jspecify`, con la versión del parent (1.0.1), porque el código usa sus anotaciones;
    - de test: `spring-boot-starter-test`, `spring-boot-starter-webmvc-test`, `spring-boot-testcontainers` y
      `org.testcontainers:testcontainers-postgresql`;
    - `testResources`: `src/test/resources` y `../docs/00-fuentes`, solo con `database.sql`. Así los tests leen el
      archivo del repo sin copiarlo, desde Maven o desde el IDE.
  - **Plugins:**
    - `spring-boot-maven-plugin`, sin versión (la maneja el parent). Su `repackage` arma el jar que arranca solo: sin
      él, el jar no trae las dependencias y la imagen de la tarea 14 no arranca;
    - `maven-compiler-plugin`, con Error Prone y NullAway en `annotationProcessorPaths` y estos `compilerArgs`:
      `-XDcompilePolicy=simple`, `--should-stop=ifError=FLOW` y
      `-Xplugin:ErrorProne -XepOpt:NullAway:OnlyNullMarked=true -XepOpt:NullAway:JSpecifyMode=true -Xep:NullAway:ERROR`;
    - `.mvn/jvm.config` con los `--add-exports` y `--add-opens` de `jdk.compiler` que pide Error Prone. Son los mismos
      del proyecto de prueba de la sección 11.1 del RFC;
    - `maven-dependency-plugin`, con el objetivo `properties`, para tener la ruta del jar de Mockito;
    - `maven-surefire-plugin`, que incluye `**/*Test.java` y `**/*IT.java`, con
      `<argLine>@{argLine} -javaagent:${org.mockito:mockito-core:jar} -Duser.timezone=UTC</argLine>`. Mockito entra
      como agente, como pide su documentación desde Java 21: si no, se conecta solo y avisa que dejará de funcionar;
    - `spotless-maven-plugin`, con `googleJavaFormat` 2.0.0 y `check` en `verify`;
    - `jacoco-maven-plugin`, con `prepare-agent`, `report` y `check` en `verify`. La regla `BUNDLE` pide
      `LINE COVEREDRATIO 0.85` y `BRANCH COVEREDRATIO 0.80`, y excluye `CocosChallengeApplication.class`.
  - **`application.yml`:** el bloque de la tarea 1 de la sección "Configuración".

- [ ] **Paso 2: escribir el test que falla.** `ApplicationStartupIT.startsAgainstTheCocosDatabase()`: con
  `@IntegrationTest`, el contexto arranca, hay un bean de `Flyway` y `select count(*) from orders` da 11. El
  historial de migraciones se verifica en la tarea 2, cuando hay migraciones.

- [ ] **Paso 3: correrlo.** `./mvnw -B test -Dtest=ApplicationStartupIT` tiene que fallar porque no existe
  `CocosChallengeApplication`.

- [ ] **Paso 4: crear `CocosChallengeApplication`** (`@SpringBootApplication` y `@ConfigurationPropertiesScan`), y
  `package-info.java` con `@NullMarked`.

- [ ] **Paso 5: correr todo.** `./mvnw -B verify` tiene que dar `BUILD SUCCESS`. Después, una prueba de que los
  controles frenan:
  1. Crear una clase con `@Nullable String s` y `s.length()`: `verify` tiene que fallar por NullAway.
  2. Mal formatearla: tiene que fallar por Spotless.
  3. Borrarla. No se commitea.

- [ ] **Paso 6: commit.** `build: set up the Maven project with the quality gates and the test database`.

### Tarea 2: las migraciones del RFC y la vuelta a los datos originales

Rama `task/5-02-schema-migrations`.

**Archivos:**
- Crear en `db/migration/`, partiendo del SQL probado de `docs/mediciones/03-esquema.sql` (RFC 3.2 a 3.5). Las reglas
  y el índice llevan los nombres de ese archivo (por ejemplo, `orders_user_clientorderid_key` y
  `orders_user_datetime_idx`), porque la semilla de volumen de la tarea 19 los usa:
  - `V2__enable_unaccent.sql`;
  - `V3__add_single_row_rules.sql`, con los `NOT NULL`, los `CHECK` de valores y de precios y los `UNIQUE` de
    `instruments` y `marketdata`;
  - `V4__add_order_identifier_fingerprint_and_rejection_reason.sql`, con las tres columnas y sus reglas, incluido
    `unique (userid, clientOrderId)`;
  - `V5__store_order_time_with_time_zone.sql`, con el `using ... at time zone 'America/Argentina/Buenos_Aires'` y el
    `default clock_timestamp()`;
  - `V6__index_orders_by_user_and_time.sql`, con `(userid, datetime, id)`.
- Test: `support/OriginalData.java`, `support/OriginalDataExtension.java`, `SchemaMigrationIT.java` y
  `support/OriginalDataIT.java`.
- Modificar `@IntegrationTest`: agregar `@ExtendWith(OriginalDataExtension.class)`.

**Interfaces:**
- Produce `OriginalData.restore(JdbcClient jdbc)`. Si la base no tiene el esquema `originals`, guarda ahí una foto de
  las cuatro tablas: lo decide mirando la base, no una variable de la JVM. Cada vez, vacía las tablas, las vuelve a llenar desde la foto y deja los contadores de ids en el máximo
  de la foto. Es el SQL de `docs/mediciones/10-volver-a-los-originales.sql`.
- Produce `OriginalDataExtension`, un `BeforeEachCallback` que toma el `DataSource` del contexto de Spring y llama a
  `restore`.

- [ ] **Paso 1: escribir los tests que fallan.**
  - `SchemaMigrationIT`:
    - `appliesTheRfcMigrationsOverTheCocosBaseline`: el historial tiene la versión 1 como `BASELINE` y de la 2 a la 6
      como `SQL`, todas con `success`.
    - `keepsTheOriginalData`: hay 4 usuarios, 66 activos, 126 precios y 11 órdenes, y la orden 2 marca 2023-07-12 12:31:20
      en Buenos Aires.
    - `rejectsImpossibleOrders`, parametrizado: cada uno de estos `insert` en `orders` lanza
      `DataIntegrityViolationException`:
      - sin `userid`, sin `size` o sin `datetime` explícito en `null`;
      - `side` 'COMPRA', `type` 'STOP' o `status` 'DONE';
      - `size` 0, o `price` 0;
      - `price` vacío en una `FILLED`;
      - un `CASH_IN` con `price` 2;
      - `rejectionReason` en una `FILLED`, o con el valor 'OTHER';
      - `clientOrderId` sin `requestFingerprint`.
    - `rejectsARepeatedIdentifierForTheSameUser`, que lanza `DuplicateKeyException`, y
      `acceptsTheSameIdentifierForAnotherUser`.
    - `rejectsTwoPricesForTheSameInstrumentAndDate`, `rejectsACloseOutsideTheDayRange` y `rejectsAZeroClose`.
    - `rejectsAnInstrumentWithoutTicker` y `rejectsARepeatedTicker`.
    - `stampsTheOrderTimeWhenItIsWritten`: en una transacción se lee `now()`, se espera 200 ms (`pg_sleep`) y se
      inserta una orden sin `datetime`. Su `datetime` tiene que ser mayor que ese `now()`.
    - `cancelsAnOldOrderWithoutIdentifier`: `update orders set status = 'CANCELLED' where id = 7` funciona. Es la trampa
      de `NOT VALID`, evitada.
  - `OriginalDataIT.restoresTheOriginalDataAndTheIdCounters`: se cancela la orden 7, se inserta un usuario y una orden,
    y `restore` deja la 7 en `NEW`, 4 usuarios, 11 órdenes, y el siguiente id de orden en 12.

- [ ] **Paso 2: correrlos.** `./mvnw -B test -Dtest='SchemaMigrationIT,OriginalDataIT'` tiene que fallar porque todavía
  no hay migraciones ni `OriginalData`.

- [ ] **Paso 3: escribir las cinco migraciones y `OriginalData`.** Las migraciones son una por cambio, para que cada
  una se lea sola; con `group`, se aplican juntas (RFC 3.6).

- [ ] **Paso 4: correrlos.** El comando del paso 2 da `PASS`, y `./mvnw -B verify`, `BUILD SUCCESS`.

- [ ] **Paso 5: commit.** `feat(db): apply the RFC schema changes as Flyway migrations`.

### Tarea 3: errores, validación y el id de cada pedido

Rama `task/5-03-errors-and-request-id`.

**Archivos:**
- Crear `shared/error/` (`ProblemType`, `ApiException`, `InvalidRequestException`, `FieldViolation` y
  `ApiExceptionHandler`), `shared/web/` (`RequestIds` y `RequestIdFilter`), `shared/validation/` (`MaxScale` y
  `MaxScaleValidator`), `shared/display/` (`DisplayProperties`, `DisplayFormatter` y `DisplayConfiguration`), con sus `package-info.java`.
- Crear `messages.properties` con las claves de la tarea 3 de la sección "Textos".
- Modificar `application.yml`: los bloques de la tarea 3.
- Test: `shared/error/ApiExceptionHandlerIT.java` (con `@WebMvcTest` sobre un controller que existe solo en los tests,
  `ProblemProbeController`), `shared/web/RequestIdFilterIT.java`, `shared/validation/MaxScaleValidatorTest.java` y
  `shared/display/DisplayFormatterTest.java`.

**Interfaces:**
- Produce:
  - `enum ProblemType`: `VALIDATION_ERROR` (400), `INVALID_ORDER` (400), `USER_NOT_FOUND` (404), `ORDER_NOT_FOUND`
    (404), `ORDER_NOT_CANCELLABLE` (409), `CLIENT_ORDER_ID_REUSED` (422, `HttpStatus.UNPROCESSABLE_CONTENT`),
    `USER_BUSY` (503) e `INTERNAL_ERROR` (500). Cada uno tiene `URI uri()`, que es `/problems/<slug>` con el slug del
    RFC 7.3; `HttpStatus status()`; y `String titleKey()`, que es `problem.<slug>.title`.
  - `abstract class ApiException extends RuntimeException`. Su constructor es
    `ApiException(ProblemType problemType, String detailKey, String... detailArgs)`, y expone `problemType()`,
    `detailKey()`, `detailArgs()`, `Map<String, Object> properties()` y `Optional<Duration> retryAfter()`. Las dos
    últimas vienen vacías por defecto. El handler convierte `retryAfter` en el encabezado `Retry-After`, en
    segundos.
  - `final class InvalidRequestException extends ApiException` (`VALIDATION_ERROR`), con
    `InvalidRequestException(String field, String messageKey, String... args)`. El handler lo publica en `errors`.
  - `record FieldViolation(String field, String message)`.
  - `RequestIds`: `HEADER = "X-Request-Id"`, `MDC_KEY = "requestId"`, `MAX_LENGTH = 64`, `ALLOWED_CHARACTERS`
    (`[A-Za-z0-9-]`, como `Pattern`) y `static String current()`.
  - `@MaxScale(int value)` para `BigDecimal`. Un `null` es válido.
  - `@Validated @ConfigurationProperties("cocos.display") record DisplayProperties(@NotNull ZoneId timeZone, @NotNull Locale locale)`.
  - `DisplayFormatter(DisplayProperties)`, una clase de Java puro registrada en `DisplayConfiguration`:
    - `OffsetDateTime inDisplayZone(OffsetDateTime)`;
    - `String formatMoney(BigDecimal)`: sin decimales si es entero, y con dos si no, con separadores de `es-AR`.

- [ ] **Paso 1: escribir los tests que fallan.**
  - `ApiExceptionHandlerIT`:
    - `rejectsAnUnknownField` (`{"prise": 1}`) y `rejectsADecimalForAnInteger` (`{"quantity": 2.5}`): las dos dan 400,
      `type` `/problems/validation-error` y `errors[0].field` con el campo.
    - `rejectsMalformedJson`.
    - `reportsEachBeanValidationViolation`: un `@Positive` que falla da `errors` con el texto de `messages.properties`.
    - `putsTheOnlyViolationInTheDetail`: con una violación, el `detail` es su mensaje (RFC 7.1); con dos, es
      "El pedido tiene 2 errores: el detalle de cada uno está en errors.".
    - `rejectsAPathValueOfTheWrongType`: `GET /probe/abc`, sobre un `@PathVariable long`, da 400 `validation-error`
      con `errors[0].field` igual al nombre del parámetro.
    - `mapsAnApiExceptionToItsProblem`: una subclase de prueba con `properties` `{"currentStatus": "FILLED"}` y
      `retryAfter` de 1 segundo da su código, su `type`, su `title`, el `detail` armado con sus argumentos, esa
      propiedad y el encabezado `Retry-After: 1`.
    - `hidesTheInternalsOfAnUnexpectedError`: un `IllegalStateException("secret")` da 500, `internal-error`, el
      `detail` "Ocurrió un error inesperado." y un `requestId`, y el cuerpo no contiene "secret".
    - `leavesSpringErrorsAsAboutBlank`: `GET /nope` da 404 con `type` `about:blank`.
  - `RequestIdFilterIT`: `echoesAValidRequestId` (`abc-123`), `generatesAnIdWhenMissing` (un UUID) y
    `replacesAnUnsafeRequestId`. Este último manda un valor con un salto de línea o de más de 64 caracteres, y la API lo
    cambia por uno generado. Solo se aceptan `[A-Za-z0-9-]` y hasta 64 caracteres.
  - `MaxScaleValidatorTest`: `1.00` es válido, `1.001` no, `1E+3` sí y `null` sí.
  - `DisplayFormatterTest`:
    - `formatMoney(500)` da "500", `formatMoney(925.85)` da "925,85", `formatMoney(10000)` da "10.000" y
      `formatMoney(9258.50)` da "9.258,50";
    - `inDisplayZone(2023-07-12T15:31:20Z)` da `2023-07-12T12:31:20-03:00`.

- [ ] **Paso 2: correrlos.** `./mvnw -B test -Dtest='ApiExceptionHandlerIT,RequestIdFilterIT,MaxScaleValidatorTest,DisplayFormatterTest'`
  tiene que fallar porque las clases no existen.

- [ ] **Paso 3: implementar las clases.**
  - **`ApiExceptionHandler`** extiende `ResponseEntityExceptionHandler`, con `@RestControllerAdvice`.
    - Sobrescribe `handleMethodArgumentNotValid`, `handleHttpMessageNotReadable` y `handleTypeMismatch`. El último
      atiende un valor de la dirección o de un parámetro que no se puede convertir, como `abc` en un `long` o `FOO` en
      un estado. Los tres dan `VALIDATION_ERROR` con `errors`. Ningún controller valida parámetros con anotaciones,
      así que `handleHandlerMethodValidationException` no se sobrescribe.
    - En un `VALIDATION_ERROR`, con una sola violación el `detail` es su mensaje; con varias, `request.several-errors`.
    - Atiende `ApiException` con su tipo y sus propiedades, y con `Retry-After` si trae `retryAfter`.
    - Atiende `Exception` como `INTERNAL_ERROR`: registra el stack trace y no lo devuelve.
    - Todos agregan `requestId`.
    - Arma los títulos y los detalles con `MessageSource`.
  - **`RequestIdFilter`** es un `OncePerRequestFilter` que no puede fallar (RFC 7.3). Limpia el MDC en el `finally`.

- [ ] **Paso 4: correrlos.** El comando del paso 2 da `PASS`, y `./mvnw -B verify`, `BUILD SUCCESS`.

- [ ] **Paso 5: commit.** `feat(api): add problem details, request ids and the shared validation`.

### Tarea 4: el catálogo de activos y el último precio

Rama `task/5-04-instrument-catalog`.

**Archivos:**
- Crear `shared/domain/Decimals.java`.
- Crear en `instruments/`: `Instrument`, `MarketPrice`, `InstrumentRepository` (por ahora, `findById` y `findAllById`),
  `MarketPriceRepository`, `InstrumentCatalog` y `package-info.java`.
- Test: `shared/domain/DecimalsTest.java`, `instruments/InstrumentRepositoryIT.java` y
  `instruments/MarketPriceRepositoryIT.java`.

**Interfaces:**
- Produce:
  - **`Decimals`:** `MONEY_SCALE = 2`, `ROUNDING = RoundingMode.HALF_UP`, `PRECISION = MathContext.DECIMAL128` y
    `ZERO_MONEY = BigDecimal.ZERO.setScale(MONEY_SCALE)`, y estos métodos:
    - `BigDecimal roundMoney(BigDecimal)`, que lleva a 2 decimales con `HALF_UP`;
    - `BigDecimal divide(BigDecimal, BigDecimal)`, con `DECIMAL128`;
    - `BigDecimal percentChange(BigDecimal base, BigDecimal value)`, que es (value − base) ÷ base × 100, redondeado a
      2 decimales.
  - **`record Instrument(long id, String ticker, String name, String type)`:** con
    `CURRENCY_TYPE = "MONEDA"` y `boolean isCurrency()`. El tipo no es un enum, porque el catálogo puede sumar tipos
    (RFC 3.2).
  - **`record MarketPrice(long instrumentId, BigDecimal close, @Nullable BigDecimal previousClose)`:** con
    `@Nullable BigDecimal dailyChangePercent()`.
  - **`InstrumentCatalog`:**
    - `Optional<Instrument> find(long id)`;
    - `Map<Long, Instrument> findAll(Collection<Long> ids)`;
    - `Optional<MarketPrice> findLatestPrice(long instrumentId)`;
    - `Map<Long, MarketPrice> findLatestPrices(Collection<Long> instrumentIds)`.

- [ ] **Paso 1: escribir los tests que fallan.**
  - `DecimalsTest`:
    - `percentChange` da 0.44 entre 921.80 y 925.85; −0.87 entre 1047.17 y 1038.10; y −8.20 entre 250 y 229.50;
    - `roundMoney(new BigDecimal("100.005"))` da 100.01, por `HALF_UP`.
  - `InstrumentRepositoryIT`:
    - `findById(47)` da PAMP, "Pampa Holding S.A.", `ACCIONES`;
    - `findById(66).isCurrency()` es verdadero;
    - `findById(999)` viene vacío;
    - `findAllById([31, 47, 54])` trae 3.
  - `MarketPriceRepositoryIT`:
    - `findsTheLatestCloseAndPreviousClose`: PAMP da 925.85 y 921.80;
    - `skipsAnInstrumentWithoutPrices`: PGR no aparece;
    - `usesTheMostRecentDateNotTheLastLoadedRow`: se carga una fila de PAMP del 2023-07-10, con id más alto, y el
      último precio sigue siendo 925.85 (caso 8 de POR).
  - `MarketPrice.dailyChangePercent()` es `null` cuando no hay `previousClose`.

- [ ] **Paso 2: correrlos.** `./mvnw -B test -Dtest='DecimalsTest,InstrumentRepositoryIT,MarketPriceRepositoryIT'`
  tiene que fallar.

- [ ] **Paso 3: implementar.** `MarketPriceRepository.findLatest(Collection<Long>)` usa la consulta acción por acción
  de la sección 4.2 del RFC, con `unnest(cast(:ids as bigint[]))`. Los ids se pasan como `Long[]`.

- [ ] **Paso 4: correrlos.** Dan `PASS`, y `./mvnw -B verify`, `BUILD SUCCESS`.

- [ ] **Paso 5: commit.** `feat(instruments): read instruments and their latest price`.

### Tarea 5: la búsqueda de activos

Rama `task/5-05-instrument-search`.

**Archivos:**
- Crear `instruments/search/` (`SearchProperties`, `SearchResult` e `InstrumentSearchService`) e `instruments/web/`
  (`InstrumentController`, `InstrumentSearchResponse` e `InstrumentSummary`), con sus `package-info.java`.
- Modificar `InstrumentRepository`: agregar `List<Instrument> search(String text, int limit)`.
- Modificar `application.yml` (`cocos.search`) y `messages.properties` (las claves de la tarea 5).
- Test: `instruments/web/InstrumentSearchIT.java`, por HTTP, y `support/ApiClient.java`.

**Interfaces:**
- Produce:
  - `@Validated @ConfigurationProperties("cocos.search") record SearchProperties(@Positive int maxResults, @Positive int maxQueryLength)`.
  - `record SearchResult(Instrument instrument, @Nullable MarketPrice price)`, en `instruments/search`.
  - `InstrumentSearchService.search(@Nullable String query) → List<SearchResult>`. El texto se mide sin los espacios
    de los extremos (`strip()`). Si queda vacío (`search.query-blank`) o pasa el máximo (`search.query-too-long`),
    lanza `InvalidRequestException("query", ...)`. El service no conoce la respuesta HTTP.
  - `GET /instruments?query=` → `InstrumentSearchResponse(List<InstrumentSummary> instruments)`, con
    `InstrumentSummary(long instrumentId, String ticker, String name, @Nullable BigDecimal lastPrice, @Nullable BigDecimal dailyChangePercent)`.
    El controller lo arma con `InstrumentSummary.from(SearchResult)`.
  - `ApiClient`, para los tests: usa `RestClient` contra el puerto local y devuelve `ApiResponse(int status,
    HttpHeaders headers, String body, JsonNode json)`, sin lanzar errores en un 4xx o un 5xx. Lee los números como
    `BigDecimal` (`USE_BIG_DECIMAL_FOR_FLOATS`).

- [ ] **Paso 1: escribir los tests que fallan.** `InstrumentSearchIT`, con los casos de BUS:
  - "banco" da `[BBAR, BHIP, BMA, BPAT]`.
  - "rio" da `[BHIP, MOLI, OEST, RICH]`, y "compania" trae INTR.
  - "grupo galicia" da `[GGAL]`, y "gas sur", `[CGPA2, TGSU2]`.
  - "pa" da exactamente `[PAMP, PATA, BPAT, CGPA2, INTR]`.
  - "y" trae YPFD primero.
  - "a" trae 20 resultados.
  - "pesos" y "_" vienen vacíos.
  - "pamp" da `lastPrice` 925.85 y `dailyChangePercent` 0.44.
  - "pgr" da los dos en `null`.
  - "" y "   " dan 400 `validation-error`, con `errors[0].field` "query"; 51 caracteres también dan 400; 50 dan 200,
    también con espacios en los extremos.
  - **Foco de revisión 3:** "o'higgins", "\\" y "%" dan 200 con la lista vacía.

- [ ] **Paso 2: correrlo.** `./mvnw -B test -Dtest=InstrumentSearchIT` tiene que fallar con 404, porque no existe el
  endpoint.

- [ ] **Paso 3: implementar.**
  - **La consulta de `search`.** El orden sigue A-5 y BUS-4.

    ```sql
    with q as (select lower(unaccent(trim(:text))) as t),
         words as (select w from q, regexp_split_to_table(q.t, '\s+') w where w <> '')
    select i.id, i.ticker, i.name, i.type
    from instruments i, q
    where i.type <> :currencyType
      and not exists (select 1 from words where strpos(lower(unaccent(i.ticker || ' ' || i.name)), w) = 0)
    order by case when lower(i.ticker) = q.t then 1
                  when starts_with(lower(i.ticker), q.t) then 2
                  when strpos(lower(i.ticker), q.t) > 0 then 3
                  when starts_with(lower(unaccent(i.name)), q.t) then 4
                  else 5 end,
             i.ticker collate "C"
    limit :limit
    ```

  - **El servicio** completa cada resultado con `InstrumentCatalog.findLatestPrices`.

- [ ] **Paso 4: correrlo.** Da `PASS`, y `./mvnw -B verify`, `BUILD SUCCESS`.

- [ ] **Paso 5: commit.** `feat(instruments): search instruments by ticker or name`.

### Tarea 6: la cuenta del usuario

Rama `task/5-06-user-account`.

**Archivos:**
- Crear `shared/domain/OrderSide.java` y `OrderStatus.java`.
- Crear en `account/`: `AccountEntry`, `Position`, `Account`, `AccountCalculator`, `AccountRepository`,
  `AccountService`, `AccountProperties`, `AccountConfiguration`, `UserNotFoundException`, `UserBusyException` y
  `package-info.java`.
- Modificar `application.yml` (`cocos.account.turn-timeout`) y `messages.properties` (`user.not-found`, que dice el
  número, y `user.busy`).
- Test: `account/AccountCalculatorTest.java`, `account/AccountRepositoryIT.java`, `account/AccountServiceIT.java` y
  `support/TestData.java`.

**Interfaces:**
- Produce:
  - **Enums:**
    - `enum OrderSide { BUY, SELL, CASH_IN, CASH_OUT }`, con `boolean isTrade()`, que es verdadero para `BUY` y
      `SELL`;
    - `enum OrderStatus { NEW, FILLED, REJECTED, CANCELLED }`.
  - **Records:**
    - `record AccountEntry(long instrumentId, OrderSide side, OrderStatus status, int size, BigDecimal price)`;
    - `record Position(long instrumentId, long quantity, long reservedQuantity, @Nullable BigDecimal averagePrice)`,
      con `long availableQuantity()`. El PPC es el exacto, sin redondear. Las cantidades van en `long`: cada orden
      entra en un `INT`, pero la suma de varias puede no entrar;
    - `record Account(BigDecimal cashBalance, BigDecimal reservedCash, Map<Long, Position> positions)`, con
      `BigDecimal availableCash()`, `long availableShares(long instrumentId)` y `List<Position> heldPositions()`, que
      deja las posiciones con cantidad distinta de 0.
  - **`AccountCalculator.calculate(List<AccountEntry>) → Account`.**
  - **`AccountRepository`:**
    - `boolean exists(long userId)`;
    - `boolean lockForTurn(long userId, Duration timeout)`;
    - `List<AccountEntry> findEntries(long userId)`.
  - **`AccountService`:**
    - `Account load(long userId)`, que lanza `UserNotFoundException` si el usuario no existe;
    - `void ensureExists(long userId)`;
    - `void takeTurn(long userId)`, que lanza `UserNotFoundException` o `UserBusyException`. Exige una transacción
      abierta (`@Transactional(propagation = MANDATORY)`), porque el lock y su espera máxima duran lo que dura la
      transacción. Lo usa la tarea 10.
  - **`@Validated @ConfigurationProperties("cocos.account") record AccountProperties(@NotNull Duration turnTimeout)`.**
  - **Excepciones:** `UserBusyException` devuelve `retryAfter()` con `RETRY_AFTER = Duration.ofSeconds(1)`.
  - **`TestData`, para los tests:**
    - `long createUser()`;
    - `void deposit(long userId, int amount)`: un depósito son `size` pesos a precio 1 (D-9);
    - `void fill(long userId, long instrumentId, OrderSide side, int size, BigDecimal price)`;
    - `void pending(long userId, long instrumentId, OrderSide side, int size, BigDecimal price)`;
    - `void addPrice(long instrumentId, LocalDate date, BigDecimal close, @Nullable BigDecimal previousClose)`, que
      graba un día sin operaciones: apertura, máximo y mínimo vacíos (D-5).

- [ ] **Paso 1: escribir los tests que fallan.**
  - **`AccountCalculatorTest`.** Las entradas del usuario 1 se copian de `database.sql`: son las 9 órdenes `FILLED` y
    `NEW`.
    - `calculatesTheCashOfUserOne`: `cashBalance` 753000.00, `reservedCash` 125500.00 y `availableCash` 627500.00.
    - `calculatesThePositionsOfUserOne`:
      - METR: 500, PPC 250;
      - PAMP: 40, PPC 930;
      - BMA: −10, PPC `null`. Da −10 y no −30, porque cuenta la límite ejecutada (D-7).
      - LOMA, que solo tiene una compra pendiente, y ARS no están en `heldPositions()`.
    - `reservesTheSharesOfAPendingSale`: una venta pendiente de 30 PAMP deja `reservedQuantity` 30 y
      `availableShares` 10 (caso 11 de POR).
    - `ignoresSalesInTheAveragePrice`: comprar 10 a 100 y 10 a 200, y vender 5, da PPC 150 (caso 5 de POR).
    - `restartsTheAveragePriceWhenThePositionComesBackFromNegative`: BMA en −10 compra 15 a 1502.80 y queda en 5, con
      PPC 1502.80 (caso 4 de POR).
    - `restartsTheAveragePriceOnEveryPurchaseMadeAtOrBelowZero`: con −10, compra 5 a 1500 y después 15 a 1600, y queda
      en 10 con PPC 1600.
    - `returnsAnEmptyAccountWithoutEntries`: los pesos en 0.00, con escala 2, y sin posiciones (caso 10 de POR).
  - **`AccountRepositoryIT`:**
    - `findEntries(1)` trae 9 entradas, de las órdenes 1, 2, 4, 5, 7, 8, 9, 10 y 11, en ese orden;
    - `exists(1)` es verdadero, y `exists(99)`, falso;
    - `lockForTurn(99, ...)` da falso.
  - **`AccountServiceIT`,** con `@TestPropertySource(properties = "cocos.account.turn-timeout=200ms")`:
    - `load(99)` lanza `UserNotFoundException`;
    - `takeTurnFailsFastWhenAnotherTransactionHoldsIt`: otro hilo toma el lock del usuario 1, con una transacción
      propia abierta 2 segundos, y `takeTurn(1)`, dentro de una transacción de `TransactionTemplate`, lanza
      `UserBusyException` antes de 1 segundo;
    - `takeTurnRequiresATransaction`: llamado sin transacción, lanza `IllegalTransactionStateException`.

- [ ] **Paso 2: correrlos.** `./mvnw -B test -Dtest='AccountCalculatorTest,AccountRepositoryIT,AccountServiceIT'`
  tiene que fallar.

- [ ] **Paso 3: implementar.**
  - **`AccountCalculator` recorre las entradas en orden.** Este es el algoritmo que fija el RFC 4.1, y no sale de la
    firma:
    - los pesos y las reservas arrancan en `Decimals.ZERO_MONEY`, así una cuenta vacía da `0.00` y no `0` (RFC 6.4);
    - una `FILLED` mueve los pesos en `size × price`: suman `CASH_IN` y `SELL`, y restan `CASH_OUT` y `BUY`;
    - en una compra `FILLED`, si la cantidad antes era menor o igual a 0, el PPC pasa a ser el precio de la compra. Si
      no, PPC = (PPC × cantidad + precio × size) ÷ (cantidad + size), con `DECIMAL128`. Después se suma la cantidad;
    - en una venta `FILLED` se resta la cantidad, y el PPC no cambia;
    - una compra `NEW` suma `size × price` a `reservedCash`, y una venta `NEW` suma `size` a `reservedQuantity`;
    - las transferencias no crean posiciones;
    - al final, `averagePrice` es `null` si la cantidad es menor o igual a 0.
  - **`lockForTurn`** corre `select set_config('lock_timeout', :timeout, true)`, con el timeout en milisegundos y el
    sufijo `ms`, y después la consulta del turno del RFC 5.2.
  - **`takeTurn`** traduce `CannotAcquireLockException` (el código 55P03 de Postgres) a `UserBusyException`.
  - **`findEntries`** filtra con `status in (:statuses)`, pasando `FILLED` y `NEW` por sus nombres, y ordena por
    `datetime, id`.

- [ ] **Paso 4: correrlos.** Dan `PASS`, y `./mvnw -B verify`, `BUILD SUCCESS`.

- [ ] **Paso 5: commit.** `feat(account): calculate the user account from its orders`.

### Tarea 7: el portfolio

Rama `task/5-07-portfolio`.

**Archivos:**
- Crear en `portfolio/`: `Portfolio`, `PositionValuation`, `PortfolioValuator`, `PortfolioService`,
  `PortfolioConfiguration` y `package-info.java`.
- Crear en `portfolio/web/`: `PortfolioController`, `PortfolioResponse`, `CashResponse` y `PositionResponse`.
- Test: `portfolio/PortfolioValuatorTest.java` y `portfolio/web/PortfolioIT.java`.

**Interfaces:**
- Consume `AccountService.load`, `InstrumentCatalog.findAll`, `InstrumentCatalog.findLatestPrices` y `TestData`.
- Produce:
  - `PortfolioValuator.value(Account, Map<Long, Instrument>, Map<Long, MarketPrice>) → Portfolio`.
  - `record Portfolio(BigDecimal totalValue, BigDecimal cashBalance, BigDecimal reservedCash, BigDecimal availableCash, List<PositionValuation> positions)`.
    Las posiciones van ordenadas por ticker.
  - `PositionValuation`, con estos campos:
    - `long instrumentId`, `String ticker`, `String name`, `long quantity` y `long reservedQuantity`;
    - `@Nullable BigDecimal lastPrice` y `@Nullable BigDecimal marketValue`;
    - `@Nullable BigDecimal averagePrice`, redondeado a 2 decimales;
    - `@Nullable BigDecimal totalReturnPercent`, calculado con el PPC exacto;
    - `@Nullable BigDecimal dailyChangePercent`.
  - `GET /users/{userId}/portfolio` → `PortfolioResponse`, con la forma del RFC 6.2. Se arma con
    `PortfolioResponse.from(long userId, Portfolio)`.

- [ ] **Paso 1: escribir los tests que fallan.**
  - **`PortfolioValuatorTest`:**
    - con la cuenta, los activos y los precios del usuario 1, `totalValue` da 889756.00;
    - METR: valor 114750.00, rendimiento −8.20 y variación −1.08;
    - PAMP: 37034.00, −0.45 y 0.44;
    - BMA: −15028.00, PPC y rendimiento `null`, y variación −1.15;
    - el orden es BMA, METR y PAMP;
    - `valuesAPositionWithoutPriceAsEmpty`: una posición de PGR va sin precio ni valor, y no suma al total (caso 9 de
      POR).
  - **`PortfolioIT`, por HTTP:**
    - casos 1 y 2 de POR: la respuesta completa del RFC 6.2, valor por valor, y el JSON con `"totalValue":889756.00`
      y con `"averagePrice":null` en BMA: lo vacío aparece como campo (RFC 6.4);
    - caso 10: el usuario 2 da todo en 0.00 (el JSON dice `"available":0.00`) y la lista vacía, y el 99 da 404
      `user-not-found`;
    - caso 6: un usuario con BBAR, armado con `TestData`, ve −0.87;
    - caso 7: con `TestData.addPrice(12, 2023-07-17, 21.00, 21.00)`, un día sin operaciones con un cierre distinto
      del último de `database.sql` (20.50), un usuario con DOME ve `lastPrice` 21.00 y variación 0.00;
    - caso 9: un usuario con PGR la ve sin valor;
    - caso 11: con una venta pendiente de 30 PAMP, PAMP muestra `reservedQuantity` 30;
    - **Foco de revisión 1:** `/users/abc/portfolio` y `/users/99999999999999999999999/portfolio` dan 400
      `validation-error`.

- [ ] **Paso 2: correrlos.** `./mvnw -B test -Dtest='PortfolioValuatorTest,PortfolioIT'` tiene que fallar.

- [ ] **Paso 3: implementar.**
  - **`marketValue`** = cantidad × `close`, o `null` si no hay precio.
  - **`totalReturnPercent`** = `percentChange(PPC, close)`, o `null` si falta alguno de los dos.
  - **`dailyChangePercent`** sale de `MarketPrice`.
  - **`totalValue`** = `cashBalance` + la suma de los `marketValue` que existen (POR-1).

- [ ] **Paso 4: correrlos.** Dan `PASS`, y `./mvnw -B verify`, `BUILD SUCCESS`.

- [ ] **Paso 5: commit.** `feat(portfolio): value the user portfolio`.

### Tarea 8: el envío, parte 1: el pedido, la cantidad y la evaluación

Rama `task/5-08-order-evaluation`.

**Archivos:**
- Crear en `orders/`: `OrderType`, `RejectionReason`, `OrderLimits`, `OrdersConfiguration` y `package-info.java`.
- Crear en `orders/web/`: `SubmitOrderRequest`, `ConsistentOrderRequest` y `ConsistentOrderRequestValidator`.
- Crear en `orders/submission/`: `SubmitOrderCommand`, `QuantityResolver`, `ResolvedOrder` e `InvalidOrderException`.
- Crear en `orders/evaluation/`: `OrderCandidate`, `OrderDecision`, `OrderEvaluator`, `RejectionRule`,
  `QuotedInstrumentRule`, `EnoughCashRule` y `EnoughSharesRule`.
- Modificar `messages.properties`: las claves de la tarea 8 de la sección "Textos".
- Test: `orders/web/SubmitOrderRequestValidationTest.java`, `orders/submission/QuantityResolverTest.java` y
  `orders/evaluation/OrderEvaluatorTest.java`.

**Interfaces:**
- Produce:
  - **Enums:**
    - `enum OrderType { MARKET, LIMIT }`;
    - `enum RejectionReason { INSUFFICIENT_CASH, INSUFFICIENT_SHARES, NO_MARKET_PRICE }`, con `String messageKey()`
      (`order.rejection.<motivo en minúsculas y con guiones>`).
  - **`OrderLimits`** (RFC 3.2 y 9):
    - `MAX_QUANTITY = Integer.MAX_VALUE`, lo que entra en `INT`;
    - `MAX_PRICE = "99999999.99"`, lo que entra en `NUMERIC(10,2)`;
    - `MAX_AMOUNT = "999999999999999999.99"`, 18 dígitos enteros. Ningún monto válido llega ahí: el más grande que
      todavía da una cantidad que entra es `MAX_QUANTITY × MAX_PRICE`, unos 2,1·10¹⁷. Por encima, la cantidad nunca
      entra, y el tope corta el pedido antes de calcularla.
    - Los dos topes de plata son textos porque los usa `@DecimalMax`.
  - **`record SubmitOrderRequest`:** tiene `UUID clientOrderId`, `Long instrumentId`, `OrderSide side`,
    `OrderType type`, `@Nullable Integer quantity`, `@Nullable BigDecimal amount` y `@Nullable BigDecimal price`.
    - Sus anotaciones, cada una con su clave de la sección "Textos":
      - `@NotNull` en los cuatro primeros;
      - `@Positive` en `quantity`, `amount` y `price`;
      - `@MaxScale(Decimals.MONEY_SCALE)` en `amount` y en `price`;
      - `@DecimalMax(OrderLimits.MAX_AMOUNT)` en `amount` y `@DecimalMax(OrderLimits.MAX_PRICE)` en `price`.
        `@DecimalMax` compara con `compareTo`, así que un exponente enorme (`1e999999999`) da 400 sin armar el número
        entero. `@Digits` no sirve como tope: resta la escala en un `int`, y con `1e2147483647` la cuenta desborda
        y el número pasa.
    - **`@ConsistentOrderRequest`,** una regla sobre la clase, con `ConsistentOrderRequestValidator`. Revisa cinco
      casos, y cada uno reporta su campo (`addPropertyNode`) y su clave:
      - el lado no es `BUY` ni `SELL` → `side`, `order.side-not-trade`;
      - vienen cantidad y monto → `quantity`, `order.quantity-and-amount`;
      - no viene ninguno de los dos → `quantity`, `order.quantity-or-amount-missing`;
      - una `LIMIT` sin precio → `price`, `order.limit-needs-price`;
      - una `MARKET` con precio → `price`, `order.market-has-price`.

      Si falta el lado o el tipo, no revisa los casos que dependen de ellos: eso ya lo reporta `@NotNull`. No se usan
      `@AssertTrue`, porque reportan el campo con el nombre del método (`priceConsistentWithType`) y con un solo
      texto para dos errores distintos.
    - El método `SubmitOrderCommand toCommand(long userId)`.
  - **`record SubmitOrderCommand`:** `long userId`, `UUID clientOrderId`, `long instrumentId`, `OrderSide side`,
    `OrderType type`, `@Nullable Integer quantity`, `@Nullable BigDecimal amount` y `@Nullable BigDecimal limitPrice`.
  - **`QuantityResolver(DisplayFormatter)`, con `resolve(SubmitOrderCommand, Instrument, @Nullable MarketPrice) → ResolvedOrder(int quantity, @Nullable BigDecimal price)`.**
    Recibe el formateador para armar los textos del error. Lanza
    `InvalidOrderException(String detailKey, String... detailArgs)`, que es un `ApiException` con `INVALID_ORDER`:
    - si el activo es ARS (`order.instrument-is-currency`);
    - si por monto la cantidad da 0 (`order.quantity-zero`);
    - si la cantidad pasa `MAX_QUANTITY` (`order.quantity-too-large`);
    - si es por monto y a mercado sin precio (`order.no-price-for-amount`).
  - **`record OrderCandidate(long instrumentId, OrderSide side, OrderType type, int quantity, @Nullable BigDecimal price, boolean quoted)`.**
  - **`@FunctionalInterface interface RejectionRule`,** con `Optional<RejectionReason> check(OrderCandidate, Account)`.
    Es la única interfaz del dominio, porque es el único lugar con más de una implementación.
  - **`OrderEvaluator(List<RejectionRule> rules).evaluate(OrderCandidate, Account) → OrderDecision(OrderStatus status, @Nullable RejectionReason rejectionReason)`.**
    `OrdersConfiguration` lo arma con las reglas en este orden: `QuotedInstrumentRule`, `EnoughCashRule` y
    `EnoughSharesRule`.

- [ ] **Paso 1: escribir los tests que fallan.**
  - **`SubmitOrderRequestValidationTest`,** con el `Validator` de Jakarta. Cada caso afirma el campo y la plantilla del
    mensaje (`getMessageTemplate()`), por ejemplo `quantity` con `{order.quantity-not-positive}`:
    - un pedido válido a mercado por cantidad no tiene violaciones;
    - los casos de pedido mal armado del caso 14 del envío: falta `clientOrderId`, el lado es `CASH_IN`, la cantidad es
      0, el monto es −1, el monto es 100.555, el precio es 0, 229.555 o 100000000.00, vienen cantidad y monto juntos o
      no viene ninguno, es una `LIMIT` sin precio o es una `MARKET` con precio;
    - cada caso de `@ConsistentOrderRequest` reporta su campo: `side`, `quantity` o `price`;
    - un pedido por cantidad sin lado ni tipo da solo las dos violaciones de `@NotNull`;
    - **foco de revisión 2:** `amount` `1e999999999` da `{order.amount-too-large}`, y `price` `1e2147483647`,
      `{order.price-too-large}`.
  - **`QuantityResolverTest`:**
    - por cantidad, 10 a mercado da 10 a 925.85, y en una límite, al precio límite;
    - $10.000 a mercado en PAMP dan 10;
    - $9.258,50 dan exactamente 10;
    - $10.000 con límite 900 dan 11 a 900;
    - $500 lanzan la excepción, y su detalle tiene "500", "PAMP" y "925,85";
    - $10.000 a mercado en PGR lanzan `order.no-price-for-amount`;
    - $15.100.000.000 en CARC, a 7.00, lanzan `order.quantity-too-large`;
    - 10 PGR a mercado dan `ResolvedOrder(10, null)`;
    - ARS lanza `order.instrument-is-currency`.
  - **`OrderEvaluatorTest`,** con la cuenta del usuario 1:
    - comprar 10 PAMP a mercado a 925.85 da `FILLED`;
    - 756 a 925.85 dan `REJECTED` con `INSUFFICIENT_CASH`;
    - 680 también dan `REJECTED`;
    - 100 METR con límite 10000 dan `REJECTED`, y 10, `NEW`;
    - vender 50 PAMP da `INSUFFICIENT_SHARES`;
    - con una venta pendiente de 30, vender 20 PAMP da `INSUFFICIENT_SHARES` (caso 11);
    - PGR sin cotización da `NO_MARKET_PRICE`, a mercado y con límite;
    - el usuario 2, con la cuenta vacía, comprando 1 PAMP, da `INSUFFICIENT_CASH`;
    - una compra que cuesta exactamente lo disponible da `FILLED`.

- [ ] **Paso 2: correrlos.**
  `./mvnw -B test -Dtest='SubmitOrderRequestValidationTest,QuantityResolverTest,OrderEvaluatorTest'` tiene que fallar.

- [ ] **Paso 3: implementar.**
  - **`QuantityResolver`.** Por monto, cantidad = monto ÷ precio con `RoundingMode.DOWN`. El precio es el `close` a
    mercado y el límite en una `LIMIT` (A-6, parte 2). El precio que se graba es el `close` a mercado, o `null` si no
    hay, y el límite en una `LIMIT` (D-6).
  - **Las reglas:** gana la primera que rechaza. Si ninguna rechaza, `MARKET` da `FILLED` y `LIMIT` da `NEW`.
    - `EnoughCashRule` compara cantidad × precio con `availableCash()`, solo en una compra.
    - `EnoughSharesRule` compara la cantidad con `availableShares(instrumentId)`, solo en una venta.

- [ ] **Paso 4: correrlos.** Dan `PASS`, y `./mvnw -B verify`, `BUILD SUCCESS`.

- [ ] **Paso 5: commit.** `feat(orders): validate, size and evaluate an order`.

### Tarea 9: el envío, parte 2: grabar, reintentar y responder

Rama `task/5-09-order-submission`.

**Archivos:**
- Crear en `orders/`: `StoredOrder`, `NewOrder` y `OrderRepository` (por ahora, `insert` y `findByClientOrderId`).
- Crear en `orders/submission/`: `RequestFingerprint`, `SubmissionResult`, `OrderSubmissionTransaction`,
  `OrderSubmissionService` y `ClientOrderIdReusedException`.
- Crear en `orders/web/`: `OrderController` (por ahora, `POST`), `OrderResponse` y `OrderResponseMapper`.
- Modificar `messages.properties`: `order.client-order-id-reused` y `order.instrument-not-found`, que dice el id.
- Test: `orders/submission/RequestFingerprintTest.java`, `orders/submission/OrderSubmissionServiceTest.java`,
  `orders/OrderRepositoryIT.java` y `orders/web/OrderSubmissionIT.java`.

**Interfaces:**
- Consume `AccountService.load`, `InstrumentCatalog.find`, `InstrumentCatalog.findLatestPrice`, `QuantityResolver`,
  `OrderEvaluator`, `InvalidOrderException`, `DisplayFormatter` y `TestData`.
- Produce:
  - **`RequestFingerprint.of(SubmitOrderCommand) → RequestFingerprint(String value)`:**
    - el valor es el SHA-256 en hexadecimal de `v1|activo|lado|tipo|cantidad|monto|precio` (RFC 5.3);
    - lo que no viene queda vacío;
    - los números pasan por `stripTrailingZeros().toPlainString()`;
    - las constantes son `VERSION = "v1"` y `SEPARATOR = "|"`.
  - **Records de la orden:**
    - `record StoredOrder`, con `long id`, `long userId`, `long instrumentId`, `String ticker`, `OrderSide side`,
      `OrderType type`, `int size`, `@Nullable BigDecimal price`, `OrderStatus status`,
      `@Nullable UUID clientOrderId`, `@Nullable String requestFingerprint`,
      `@Nullable RejectionReason rejectionReason` y `OffsetDateTime createdAt`;
    - `record NewOrder`, con los mismos campos salvo el id, el ticker y la fecha. Ahí el identificador y la huella son
      obligatorios.
  - **`OrderRepository`:**
    - `Optional<StoredOrder> findByClientOrderId(long userId, UUID clientOrderId)`;
    - `StoredOrder insert(NewOrder)`, con un `with inserted as (insert ... returning *) select ...` unido a
      `instruments` para traer el ticker en un solo paso.
  - **Envío y resultado:**
    - `record SubmissionResult(StoredOrder order, boolean replayed)`;
    - `OrderSubmissionTransaction.submit(SubmitOrderCommand) → SubmissionResult`, la transacción del paso 3;
    - `OrderSubmissionService.submit(SubmitOrderCommand) → SubmissionResult`. Si salta `DuplicateKeyException`, llama
      una vez más a la transacción (RFC 5.3).
  - **`OrderResponseMapper(MessageSource, DisplayFormatter)`,** con `OrderResponse toResponse(StoredOrder)`. Calcula
    `amount`, arma `rejectionMessage` con la clave del motivo y pasa `datetime` a la zona de Buenos Aires. Lo usan las
    tareas 9, 11 y 12.
  - **`POST /users/{userId}/orders`:** responde 201, o 200 si es un reintento, con `OrderResponse`. Tiene la forma del
    RFC 6.2:
    - `id`, `clientOrderId`, `instrumentId`, `ticker`, `side`, `type`, `quantity`, `price`, `amount`, `status`,
      `rejectionReason`, `rejectionMessage` y `datetime`;
    - en un depósito o un retiro, `quantity` y `price` van en `null`, y `amount` es lo transferido;
    - `amount` es `null` si el precio es `null`.

- [ ] **Paso 1: escribir los tests que fallan.**
  - **`RequestFingerprintTest`:**
    - `v1|47|BUY|MARKET||10000|` da `7fab93192961cf0c3f0999befdff587f21b61d9a73c2ac6b49dabb7688c1a727`;
    - `10000.00` y `1E+4` dan lo mismo (**foco de revisión 4**);
    - con 20000 da `5877f5a9942d5ffea1337283f79fe0c3339a3b1a16faca05b50f56b741475a0c`;
    - la huella cambia con el precio límite o con el lado.
  - **`OrderSubmissionServiceTest`,** con Mockito:
    - si la transacción lanza `DuplicateKeyException` una vez, el servicio devuelve lo del segundo intento;
    - si la lanza dos veces, la deja pasar.
  - **`OrderRepositoryIT`:**
    - `insert` devuelve el id 12, el ticker y una fecha de la base;
    - `findByClientOrderId` encuentra la orden;
    - un identificador repetido del mismo usuario lanza `DuplicateKeyException`.
  - **`OrderSubmissionIT`, por HTTP:**
    - Caso 1: 201 `FILLED`, precio 925.85, cantidad 10, `amount` 9258.50 e id 12. El `datetime` termina en `-03:00`
      aunque la JVM esté en UTC (**foco de revisión 5**).
    - Caso 3 de POR: después, el portfolio muestra PAMP 50, PPC 929.17, rendimiento −0.36, disponibles 618241.50 y
      total 889756.00.
    - Caso 4 de POR: comprar 15 BMA a mercado deja 5 BMA, con PPC 1502.80 y rendimiento 0.00.
    - Caso 2: 10 METR con límite 10000 dan `NEW`, y el portfolio muestra 527500.00 disponibles.
    - Casos 3 a 6: $10.000 dan cantidad 10 y `amount` 9258.50; $9.258,50 dan 10; $10.000 con límite 900 dan 11, `NEW`
      y `amount` 9900.00; vender $10.000 da 10, `FILLED`.
    - Caso 7: $700.000 dan 756, `REJECTED` con `INSUFFICIENT_CASH` y el texto "No alcanzan los pesos disponibles para
      esta orden.", y `orders` tiene 12 filas.
    - Casos 8 a 13: $630.000 dan `REJECTED`; 100 METR con límite 10000 dan `REJECTED`; vender 50 PAMP da
      `INSUFFICIENT_SHARES`; vender 20 PAMP con una venta límite pendiente de 30 da `REJECTED`; 10 PGR a mercado dan
      `REJECTED`, `NO_MARKET_PRICE`, con precio y `amount` en `null`, y con límite también `REJECTED`; el usuario 2,
      comprando 1 PAMP, da `REJECTED`.
    - Caso 14, parametrizado. Cada pedido da 400 con su `type`, y `orders` sigue en 11 filas:
      - `validation-error`: falta `clientOrderId`; lado `COMPRA`; lado `CASH_IN`; `quantity` 2.5; cantidad 0; monto
        −1; precio 0; precio 229.555; cantidad y monto juntos; ninguno de los dos; `LIMIT` sin precio; `MARKET` con
        precio; un campo desconocido (`"prise"`);
      - `invalid-order`: el activo 999, que no existe; ARS; $500 de PAMP; $10.000 de PGR a mercado.

      Además, el usuario 99 da 404 `user-not-found`. **Foco de revisión 2:** `"quantity": 3000000000`,
      `"price": 1e400`, `"amount": 1e999999999` y `"price": 1e2147483647` también dan 400 `validation-error`, cada uno
      en menos de 1 segundo.
    - Caso 15: el mismo identificador con el mismo contenido da 200 y el mismo cuerpo, sin una fila más. Lo mismo con
      `"amount": 10000.00` y `1E+4` (**foco de revisión 4**).
    - Caso 17: otro contenido con el mismo identificador da 422 `client-order-id-reused`, sin una fila más.
    - Caso 18: los usuarios 1 y 2 con el mismo identificador dan 201 los dos.
    - Caso 19: un pedido con error y después el mismo, corregido, con el mismo identificador, da 201.

- [ ] **Paso 2: correrlos.**
  `./mvnw -B test -Dtest='RequestFingerprintTest,OrderSubmissionServiceTest,OrderRepositoryIT,OrderSubmissionIT'`
  tiene que fallar.

- [ ] **Paso 3: implementar.** `OrderSubmissionTransaction.submit` va con
  `@Transactional(isolation = Isolation.READ_COMMITTED)` y sigue este orden:
  1. Busca por el identificador. Si existe con la misma huella, devuelve `replayed = true`; si existe con otra, lanza
     `ClientOrderIdReusedException`.
  2. Busca el activo, que tiene que existir, y su último precio.
  3. Resuelve la cantidad.
  4. Carga la cuenta con `AccountService.load`.
  5. Evalúa la orden y la graba.

  El turno no va todavía: lo agrega la tarea 10, con el test que lo exige.

- [ ] **Paso 4: correrlos.** Dan `PASS`, y `./mvnw -B verify`, `BUILD SUCCESS`.

- [ ] **Paso 5: commit.** `feat(orders): submit orders with retry recognition`.

### Tarea 10: el envío, parte 3: órdenes simultáneas y el turno

Rama `task/5-10-user-turn`.

**Archivos:**
- Modificar `OrderSubmissionTransaction`: llamar a `AccountService.takeTurn(userId)` como primer paso.
- Test: `support/Concurrency.java`, `orders/web/OrderConcurrencyIT.java` y `orders/web/OrderTurnTimeoutIT.java`.

**Interfaces:**
- Consume `AccountService.takeTurn` (tarea 6).
- Produce `Concurrency`, para los tests:
  - `PausePoint`: sobre un método espiado con `@MockitoSpyBean`, la primera llamada corre el método real y espera a
    que el test la suelte. Cuenta las llamadas, así el test sabe si llegó una segunda. Acá espía
    `AccountRepository.findEntries`, y en la tarea 11, `OrderRepository.cancelIfNew`;
  - `awaitBlockedOnLock(Duration)`: espera a que otra conexión esté esperando un lock, según `pg_stat_activity`, con
    `wait_event_type = 'Lock'`.

- [ ] **Paso 1: escribir los tests que fallan.**
  - `twoSimultaneousSalesNeverSellTheSameShares` (caso 1 de GEN):
    1. Se crea un usuario con 20 BMA, con `TestData`.
    2. La venta A de 15 lee su cuenta y queda en pausa.
    3. Arranca la venta B. El test espera lo primero que pase: que B quede esperando el lock (`awaitBlockedOnLock`) o
       que B lea su cuenta (la segunda llamada de `PausePoint`). Sin el turno, B lee los mismos 20; con el turno, B
       queda esperando.
    4. Se suelta A.
    5. Tiene que quedar una `FILLED` y una `REJECTED`, y la posición en 5.
  - `twoSimultaneousPurchasesNeverSpendTheSameCash` (caso 2 de GEN): el usuario 1 manda dos compras de $600.000 de PAMP
    a mercado. Una queda `FILLED`, con 648 acciones y `amount` 599950.80, y la otra `REJECTED`.
  - `twoSimultaneousRequestsWithTheSameIdentifierCreateOneOrder` (caso 20 del envío): queda una fila más, y las dos
    respuestas traen el mismo id de orden.
  - Los tests 2 y 3 siguen la misma coreografía que el 1: la primera queda en pausa después de leer su cuenta, el
    test espera a que la segunda espere el lock o lea su cuenta, y después suelta la primera. Sin la pausa, el caso 2
    podría pasar sin el turno, solo porque los pedidos no se cruzaron.
  - `OrderTurnTimeoutIT.answersBusyWhenTheTurnIsNotFree`, con
    `@TestPropertySource(properties = "cocos.account.turn-timeout=200ms")`: otra transacción tiene el lock del usuario
    1, y el envío responde 503 `user-busy` con `Retry-After: 1`, sin una fila más. Va en su propia clase porque una
    propiedad vale para todo el contexto: con 200 ms, los tests con pausa podrían dar 503 en una máquina lenta.

- [ ] **Paso 2: correrlos.** `./mvnw -B test -Dtest='OrderConcurrencyIT,OrderTurnTimeoutIT'` tiene que fallar. En el
  primero, la posición queda en −10: es la orden 11, reproducida.

- [ ] **Paso 3: tomar el turno.** Agregar `accountService.takeTurn(command.userId())` como primera línea de la
  transacción.

- [ ] **Paso 4: correrlos.** Dan `PASS`, y `./mvnw -B verify`, `BUILD SUCCESS`.

- [ ] **Paso 5: commit.** `feat(orders): process the orders of a user one at a time`.

### Tarea 11: la cancelación

Rama `task/5-11-order-cancellation`.

**Archivos:**
- Crear en `orders/cancellation/`: `OrderCancellationService` y `OrderNotCancellableException`.
- Crear en `orders/`: `OrderNotFoundException`.
- Modificar `OrderRepository`: agregar `findById` y `cancelIfNew`.
- Modificar `OrderController`: agregar `POST /users/{userId}/orders/{orderId}/cancel`.
- Modificar `messages.properties`: agregar `order.not-found`.
- Test: `orders/web/OrderCancellationIT.java`.

**Interfaces:**
- Consume `AccountService.ensureExists`, `OrderResponseMapper.toResponse`, `Concurrency.PausePoint` y `TestData`.
- Produce:
  - `Optional<StoredOrder> OrderRepository.findById(long userId, long orderId)`;
  - `Optional<StoredOrder> OrderRepository.cancelIfNew(long userId, long orderId)`, con el `UPDATE` del RFC 5.4;
  - `OrderCancellationService.cancel(long userId, long orderId) → StoredOrder`, con `@Transactional`;
  - `OrderNotCancellableException(long orderId, OrderStatus current)`, con la propiedad `currentStatus`.

- [ ] **Paso 1: escribir los tests que fallan.**
  - Caso 1 de CAN: cancelar la 7 da 200 `CANCELLED`, y el portfolio muestra 717500.00 disponibles y 753000.00 de
    pesos.
  - Caso 16 del envío y caso 2 de CAN:
    1. $700.000 con el identificador "A" quedan `REJECTED`.
    2. Se cancela la 7.
    3. Con "A" vuelve el mismo `REJECTED`, con 200.
    4. Con "B", 201 `FILLED`, 756 acciones y `amount` 699942.60.
    5. Quedan 17557.40 disponibles.
  - Caso 3 de CAN: una venta límite de 30 PAMP, cancelada, deja `reservedQuantity` en 0.
  - Caso 15 del envío, con una límite (RFC 6.2): 10 METR con límite 10000 y el identificador "C" quedan `NEW`; se
    cancela; el mismo pedido con "C" da 200 con la orden en `CANCELLED`.
  - Caso 4 de CAN: cancelar la 3, la 2 o la 6 da 409 `order-not-cancellable`, con `currentStatus` `CANCELLED`, `FILLED`
    o `REJECTED`, y nada cambia.
  - Caso 5 de CAN:
    - la 999 da 404 `order-not-found`;
    - el usuario 2 cancelando la 7 da 404 `order-not-found`;
    - el usuario 99 da 404 `user-not-found`.
  - Caso 6 de CAN: dos cancelaciones de la 7 a la vez dan un 200 y un 409. Una pausa sobre `cancelIfNew` mantiene
    abierta la primera transacción hasta que la segunda espera el lock de la fila.
  - **Foco de revisión 1:** `/users/1/orders/x/cancel` da 400 `validation-error`.

- [ ] **Paso 2: correrlos.** `./mvnw -B test -Dtest=OrderCancellationIT` tiene que fallar con 404, porque no existe el
  endpoint.

- [ ] **Paso 3: implementar.** Primero `AccountService.ensureExists`. Después `cancelIfNew`; si no cambió ninguna fila,
  `findById` decide entre 404 y 409 (RFC 5.4).

- [ ] **Paso 4: correrlos.** Dan `PASS`, y `./mvnw -B verify`, `BUILD SUCCESS`.

- [ ] **Paso 5: commit.** `feat(orders): cancel a pending order`.

### Tarea 12: el listado

Rama `task/5-12-order-listing`.

**Archivos:**
- Crear en `orders/listing/`: `OrderCursor`, `OrderPage`, `OrderPageProperties` y `OrderQueryService`.
- Crear en `orders/web/`: `OrderPageResponse`.
- Modificar `OrderRepository`: agregar `findPage`.
- Modificar `OrderController`: agregar `GET /users/{userId}/orders`.
- Modificar `application.yml` (`cocos.orders.page`) y `messages.properties` (las claves de la tarea 12).
- Modificar `TestData`: agregar `void pendingAt(long userId, long instrumentId, int size, BigDecimal price, OffsetDateTime datetime)`.
- Test: `orders/listing/OrderCursorTest.java`, `orders/listing/OrderPagePropertiesTest.java` y
  `orders/web/OrderListingIT.java`.

**Interfaces:**
- Consume `AccountService.ensureExists`, `OrderResponseMapper.toResponse` y `TestData`.
- Produce:
  - `record OrderCursor(OffsetDateTime createdAt, long orderId)`:
    - `String encode()` escribe base64url sin relleno de `Instant|id`;
    - `static OrderCursor decode(String)` lanza `IllegalArgumentException` si no se puede leer;
  - `@Validated @ConfigurationProperties("cocos.orders.page") record OrderPageProperties(@Positive int defaultSize, @Positive int maxSize)`.
    Su constructor rechaza un `defaultSize` mayor que `maxSize`, y la API no arranca;
  - `OrderRepository.findPage(long userId, @Nullable OrderStatus status, @Nullable OrderCursor after, int limit) → List<StoredOrder>`,
    con la consulta del RFC 4.4. Se arma con las cláusulas que corresponden: el filtro por estado y la comparación
    del cursor van solo si vienen, y dan cuatro variantes. Las cláusulas son texto fijo del código, y los valores van
    como parámetros (RFC 9). No se escribe `(:cursor is null or ...)`: Postgres puede pasar a un plan general que no
    usa el cursor sobre el índice;
  - `OrderQueryService.list(long userId, @Nullable OrderStatus status, @Nullable Integer limit, @Nullable String cursor) → OrderPage(List<StoredOrder> orders, @Nullable String nextCursor)`;
  - `GET /users/{userId}/orders?status=&limit=&cursor=` → `OrderPageResponse(List<OrderResponse> orders, @Nullable String nextCursor)`.

- [ ] **Paso 1: escribir los tests que fallan.**
  - **`OrderCursorTest`:**
    - `2026-11-08T14:02:15.483921Z` con el id 12 se codifica y se lee igual, con los microsegundos;
    - un texto cualquiera, o un base64 válido con otro contenido, lanza `IllegalArgumentException`.
  - **`OrderPagePropertiesTest`:** `new OrderPageProperties(200, 100)` lanza `IllegalArgumentException`.
  - **`OrderListingIT`:**
    - Caso 1 de LIS: con `status=NEW`, `[7, 5]`.
    - LIS-1: dos órdenes de `pendingAt` con la misma fecha y hora salen por id, de mayor a menor, y recorrerlas con
      `limit=1` muestra cada una una sola vez.
    - Caso 2:
      - sin filtro, las 11, de la 11 a la 1, con `nextCursor` en `null`;
      - la orden 1 con `quantity` y `price` en `null` y `amount` 1000000.00, y la 8 con `amount` 100000.00;
      - las órdenes viejas, con `clientOrderId` y `rejectionReason` en `null` (LIS-4);
      - el `datetime` de la orden 2, `2023-07-12T12:31:20-03:00` (**foco de revisión 5**).
    - Caso 3:
      1. Un usuario de `TestData` tiene 30 órdenes `NEW`.
      2. Pide la primera página con `status=NEW`, sin `limit`: trae 20, el tamaño por defecto, y un `nextCursor`.
      3. Cancela 5 de esa página.
      4. La página siguiente trae las 10 que faltaban, sin saltear ninguna.
    - Caso 4: con órdenes a microsegundos de distancia, recorrer con `limit=2` muestra cada una una sola vez.
    - Caso 5:
      - el usuario 2 recibe la lista vacía, y el 99, 404;
      - un cursor mal armado da 400 con el campo `cursor`;
      - `limit=101` y `limit=0` dan 400 con el campo `limit`.
    - **Foco de revisión 1:** `status=FOO` y `limit=x` dan 400.

- [ ] **Paso 2: correrlos.** `./mvnw -B test -Dtest='OrderCursorTest,OrderListingIT'` tiene que fallar.

- [ ] **Paso 3: implementar.** Primero `AccountService.ensureExists`. Se pide `limit + 1` para saber si hay página
  siguiente (A-7), y el cursor sale de la última orden de la página.

- [ ] **Paso 4: correrlos.** Dan `PASS`, y `./mvnw -B verify`, `BUILD SUCCESS`.

- [ ] **Paso 5: commit.** `feat(orders): list the orders of a user with a cursor`.

### Tarea 13: la especificación OpenAPI

Rama `task/5-13-openapi`.

**Archivos:**
- Modificar `pom.xml`: `springdoc.version` 3.2.0 y `org.springdoc:springdoc-openapi-starter-webmvc-ui`.
- Crear `shared/web/OpenApiConfiguration.java`, con el título, la descripción y la versión.
- Modificar los tres controllers: `@Operation` y `@ApiResponse` con cada código de la sección 6.3 del RFC, y
  `@ExampleObject` con los ejemplos del usuario 1 del RFC 6.2.
- Test: `shared/web/OpenApiIT.java`.

- [ ] **Paso 1: escribir el test que falla.** `publishesTheFiveOperations`:
  - `GET /v3/api-docs` da 200, con `paths` para `/users/{userId}/portfolio` (get), `/instruments` (get),
    `/users/{userId}/orders` (get y post) y `/users/{userId}/orders/{orderId}/cancel` (post);
  - `GET /swagger-ui.html` da 200, o una redirección a la página.

- [ ] **Paso 2: correrlo.** `./mvnw -B test -Dtest=OpenApiIT` tiene que fallar con 404.

- [ ] **Paso 3: agregar springdoc y las anotaciones.** Las anotaciones se limitan a descripciones y ejemplos (ADR 0008).

- [ ] **Paso 4: correrlo.** Da `PASS`, y `./mvnw -B verify`, `BUILD SUCCESS`.

- [ ] **Paso 5: commit.** `docs(api): publish the OpenAPI specification`.

### Tarea 14: Docker y `docker compose up`

Rama `task/5-14-docker-compose`.

**Archivos:**
- Crear `api/Dockerfile`, `api/.dockerignore`, `docker-compose.yml`, `scripts/wait-for-api.sh`,
  `scripts/smoke-test.sh` y `README.md`. El README
  es mínimo por ahora: requisitos, cómo levantarlo, las direcciones y cómo correr los tests. Se completa en la fase 8.

**Interfaces:**
- Produce el compose, con `name: cocos-challenge`, así la red es `cocos-challenge_default`. Tiene dos servicios:
  - **`postgres`:**
    - imagen `postgres:18`, con `database.sql` montado en `/docker-entrypoint-initdb.d/01-database.sql:ro`;
    - `tmpfs` en `/var/lib/postgresql`;
    - el chequeo de salud es `pg_isready -h 127.0.0.1 -U postgres`;
    - puerto `127.0.0.1:5433:5432`;
    - la clave es solo para la copia local, y lo dice un comentario.
  - **`api`:**
    - se construye desde `./api` y espera a que `postgres` esté sano;
    - recibe `SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/postgres`, el usuario, la clave y
      `SPRING_FLYWAY_ENABLED=true`;
    - puerto `8080:8080`;
    - no tiene chequeo de salud en el compose. La imagen `eclipse-temurin:25-jre` no trae `curl` ni `wget`
      (verificado), y no se agrega una herramienta a la imagen solo para eso. Por eso `--wait` espera a Postgres, y
      los scripts esperan a la API con `wait-for-api.sh`.
- Produce `scripts/wait-for-api.sh`, que espera hasta 60 segundos a que `localhost:8080/actuator/health` dé `UP`, con
  el `curl` de la máquina, y termina con 1 si no llega. Cuando da `UP`, Flyway ya migró, porque migra antes de que la
  API atienda. Lo usan `smoke-test.sh` y los scripts de las tareas 15 y 19.
- Produce `scripts/smoke-test.sh`, que primero llama a `wait-for-api.sh`. Después verifica con `jq` que el portfolio
  del usuario 1 tenga `cash.available` 627500 y `totalValue` 889756, y devuelve 0 si está todo bien.

- [ ] **Paso 1: escribir la prueba que falla.** Desde la raíz, `./scripts/smoke-test.sh` falla porque no hay nada
  escuchando en el 8080.

- [ ] **Paso 2: escribir el `Dockerfile`.**
  - **Primera etapa:** `maven:3.9-eclipse-temurin-25`. Copia `mvnw`, `.mvn` y `pom.xml`, baja las dependencias
    (`./mvnw -B dependency:go-offline`), copia `src` y corre `./mvnw -B package -Dmaven.test.skip=true`, que ni
    compila los tests. Los tests corren en CI, porque dentro de la imagen no hay Docker para Testcontainers.
  - **Segunda etapa:** `eclipse-temurin:25-jre`. Extrae las capas del jar (`java -Djarmode=tools -jar app.jar extract
    --layers --launcher`), corre con un usuario sin privilegios y expone el 8080.

- [ ] **Paso 3: escribir el compose.**

- [ ] **Paso 4: verificar.**
  1. `docker compose up -d --build --wait`.
  2. `./scripts/smoke-test.sh` termina con 0.
  3. `docker compose down`.

  Un segundo `up` vuelve a dar los mismos números, porque los datos viven en memoria.

- [ ] **Paso 5: commit.** `build: run the API and its database with docker compose`.

### Tarea 15: la colección de Postman

Rama `task/5-15-postman-collection`.

**Archivos:**
- Crear `postman/cocos-challenge.postman_collection.json`, `postman/local.postman_environment.json` y
  `scripts/run-postman.sh`. El script primero llama a `wait-for-api.sh`, y después corre `postman/newman:6.1.3` en la
  red `cocos-challenge_default`, con `baseUrl=http://api:8080`.

- [ ] **Paso 1: escribir la colección con sus tests (`pm.test`).** Son los nueve pasos del RFC 11.5, sobre los datos
  originales:
  1. Portfolio del usuario 1: 200 y 627500 disponibles.
  2. Búsqueda de "pamp": PAMP primero.
  3. Compra de 10 PAMP a mercado: 201 `FILLED`. Guarda el identificador y el id.
  4. Compra de 10 METR con límite 10000: 201 `NEW`. Guarda su id.
  5. Listado con `status=NEW`: aparece la del paso 4.
  6. Cancelación de la del paso 4: 200 `CANCELLED`.
  7. El pedido del paso 3, con el mismo identificador: 200, con el mismo id.
  8. El identificador del paso 3 con otro contenido: 422.
  9. Compra de $700.000 de PAMP: 201 `REJECTED`.

  Cada identificador nuevo sale de `{{$guid}}`.

- [ ] **Paso 2: verificar.** Con `docker compose up -d --build --wait`, `./scripts/run-postman.sh` termina con 9
  pedidos y 0 fallas. Después, `docker compose down`.

- [ ] **Paso 3: commit.** `test: add the Postman collection with the full tour`.

### Tarea 16: CI en GitHub Actions

Rama `task/5-16-ci`.

**Archivos:**
- Crear `.github/workflows/ci.yml`. Corre en `push` y en `pull_request`, con dos jobs:
  - **`build`:**
    - en `ubuntu-latest`, con `actions/checkout@v7` y `actions/setup-java@v6` (`distribution: temurin`,
      `java-version: 25` y `cache: maven`);
    - corre `./mvnw -B verify` en `api/`.
  - **`smoke`:**
    - necesita `build`;
    - corre `docker compose up -d --build --wait`, `./scripts/smoke-test.sh` y `./scripts/run-postman.sh`;
    - al final, `docker compose down`, aunque algo falle (`if: always()`).

- [ ] **Paso 1: escribir el workflow.**

- [ ] **Paso 2: verificar.**
  1. Push de la rama.
  2. `env -u GITHUB_TOKEN gh run watch` termina en `success`, con los dos jobs.
  3. El PR muestra los controles en verde.

- [ ] **Paso 3: commit.** `ci: build, test and smoke test on every push`.

Al terminar la tarea 16: PR de `feat/phase-5-implementation` a `develop`, con merge commit.

---

## Parte B — Fase 6: observabilidad

Antes de la tarea 17: `git checkout develop && git pull && git checkout -b feat/phase-6-observability`, y push.

### Tarea 17: logs estructurados y métricas

Rama `task/6-17-logs-and-metrics`.

**Archivos:**
- Modificar `pom.xml`: agregar `io.micrometer:micrometer-registry-prometheus`.
- Modificar `application.yml`: los bloques de la tarea 17.
- Crear `orders/OrderMetrics.java`, `orders/OrderEventLogger.java` y `account/TurnMetrics.java`.
- Modificar `OrderSubmissionService` y `OrderCancellationService`, que registran las métricas y las líneas de log, y
  `AccountService`, que mide la espera del turno.
- Modificar `ApiExceptionHandler`: cada 4xx escribe una línea `WARN` con el `type` y el código, sin stack trace. Los
  5xx ya escriben el stack trace (tarea 3).
- Test: `shared/web/MetricsIT.java`, `shared/web/ActuatorExposureIT.java` y `shared/web/LoggingIT.java`.

**Interfaces:**
- Produce:
  - `OrderMetrics`:
    - `recordCreated(StoredOrder)` cuenta en `orders.created`, con las etiquetas `side`, `type`, `status` y `reason`
      (`none` si no hay motivo);
    - `recordReplayed()` cuenta en `orders.replayed`;
    - `recordCancelled()` cuenta en `orders.cancelled`;
  - `TurnMetrics`, con `<T> T time(Supplier<T>)`, que mide en el timer `orders.turn.wait`. Envuelve
    `lockForTurn`, que devuelve si el usuario existe;
  - `OrderEventLogger`, con `logProcessed(StoredOrder, boolean replayed)` y `logCancelled(StoredOrder)`. Cada uno pone
    en el MDC las claves `userId`, `orderId`, `clientOrderId`, `side`, `type`, `status` y `rejectionReason`, escribe
    la línea y las saca en un `finally`. Las claves son constantes de la clase.
- En Prometheus se ven como `orders_created_total`, `orders_replayed_total`, `orders_cancelled_total` y
  `orders_turn_wait_seconds` (RFC 8.2).

- [ ] **Paso 1: escribir los tests que fallan.**
  - **`MetricsIT`.** Lee `/actuator/prometheus` antes y después de cada acción, y compara las diferencias:
    - una compra `FILLED` suma 1 a `orders_created_total{reason="none",side="BUY",status="FILLED",type="MARKET"}`;
    - una `REJECTED` por pesos suma 1 con `reason="INSUFFICIENT_CASH"`;
    - un reintento suma 1 a `orders_replayed_total`;
    - una cancelación suma 1 a `orders_cancelled_total`;
    - `orders_turn_wait_seconds_count` crece;
    - existe `http_server_requests_seconds_bucket` con `uri="/users/{userId}/portfolio"`.
  - **`ActuatorExposureIT`:** `/actuator/health` da 200, y `/actuator/env` y `/actuator/heapdump` dan 404.
  - **`LoggingIT`,** con `OutputCaptureExtension` (caso 5 de GEN):
    - al mandar una orden y pedir el portfolio del usuario 1, la salida tiene líneas JSON con `requestId` y `orderId`,
      y no contiene `@test.com`;
    - una cancelación escribe una línea con su `orderId` y `status` `CANCELLED`;
    - un 404 escribe una línea `WARN` sin stack trace (sin líneas `\tat `).

- [ ] **Paso 2: correrlos.** `./mvnw -B test -Dtest='MetricsIT,ActuatorExposureIT,LoggingIT'` tiene que fallar.

- [ ] **Paso 3: implementar.**
  - **Las métricas y las líneas de log** se registran después de confirmar la transacción, así no se cuenta una orden
    que después no se confirmó:
    - el envío y el reintento, en `OrderSubmissionService`, que está fuera de la transacción;
    - la cancelación, que es una sola transacción, con `TransactionSynchronizationManager.registerSynchronization`,
      en `afterCommit`.

- [ ] **Paso 4: correrlos.** Dan `PASS`, y `./mvnw -B verify`, `BUILD SUCCESS`.

- [ ] **Paso 5: commit.** `feat(observability): add structured logs and business metrics`.

### Tarea 18: Prometheus y Grafana en el compose, con su tablero

Rama `task/6-18-grafana-dashboard`.

**Archivos:**
- Crear `observability/prometheus/prometheus.yml`. Lee `api:8080/actuator/prometheus` cada 15 segundos.
- Crear `observability/grafana/provisioning/datasources/prometheus.yml` y
  `observability/grafana/provisioning/dashboards/dashboards.yml`.
- Crear `observability/grafana/dashboards/cocos-api.json`, con el título "Cocos challenge API" y estos paneles:
  - órdenes por estado, tipo y lado: `sum by (status, type, side) (increase(orders_created_total[$__range]))`;
  - rechazos por motivo: `sum by (reason) (increase(orders_created_total{status="REJECTED"}[$__range]))`;
  - latencia p95 por función:
    `histogram_quantile(0.95, sum by (le, uri) (rate(http_server_requests_seconds_bucket[5m])))`;
  - errores por función: `sum by (uri, status) (rate(http_server_requests_seconds_count{status=~"4..|5.."}[5m]))`;
  - espera del turno, p95: `histogram_quantile(0.95, sum by (le) (rate(orders_turn_wait_seconds_bucket[5m])))`.
- Modificar `docker-compose.yml` para agregar dos servicios:
  - `prometheus` (`prom/prometheus:v3.16.0`, puerto 9090);
  - `grafana` (`grafana/grafana:13.3.0`, puerto 3000), con `GF_AUTH_ANONYMOUS_ENABLED=true` y
    `GF_AUTH_ANONYMOUS_ORG_ROLE=Viewer`.
- Modificar `application.yml`: `management.metrics.distribution.percentiles-histogram.orders.turn.wait: true`, para
  que el tablero pueda calcular el percentil 95 de la espera.
- Modificar `README.md`: agregar las direcciones de Prometheus y Grafana.

- [ ] **Paso 1: escribir la verificación que falla.** `scripts/smoke-test.sh` también verifica dos cosas:
  - que `curl -fsS localhost:9090/api/v1/targets` muestre el objetivo `api` en `up`. Reintenta hasta 30 segundos,
    porque Prometheus marca el objetivo recién después de su primera lectura, y lee cada 15;
  - que `curl -fsS 'localhost:3000/api/search?query=Cocos'` encuentre el tablero.

  Con el compose actual, falla.

- [ ] **Paso 2: escribir la configuración, el tablero y los servicios.**

- [ ] **Paso 3: verificar.**
  1. `docker compose up -d --build --wait`, `./scripts/smoke-test.sh` y `./scripts/run-postman.sh`.
  2. En Grafana, el tablero muestra las órdenes de la colección.
  3. `docker compose down`.

- [ ] **Paso 4: commit.** `feat(observability): show the metrics in Prometheus and Grafana`.

### Tarea 19: el tiempo de respuesta, medido con volumen (GEN-8)

Rama `task/6-19-load-test`.

**Archivos:**
- Crear `perf/seed-volume.sql`: la carga de datos de `docs/mediciones/03-indices.sql`, para el esquema ya migrado. Son
  10.000 usuarios de 100 órdenes, uno de 10.000 y uno de 1.000.000, con `setseed(0.2026)`. Como ese script, borra
  `orders_user_clientorderid_key` y `orders_user_datetime_idx` antes de cargar, y los vuelve a crear después, con los
  nombres de V4 y V6.
- Crear `perf/load-test.js`, para k6. Tiene un escenario por función para usuarios típicos y para el pesado, con el
  umbral `http_req_duration{scenario:...}: ['p(95)<100']`. Las compras van repartidas entre los usuarios típicos.
- Crear `scripts/run-load-test.sh`:
  1. Levanta el compose, espera a la API con `wait-for-api.sh`, así Flyway ya migró, y carga `seed-volume.sql` en el
     contenedor de Postgres.
  2. Corre `grafana/k6:2.3.0` en la red `cocos-challenge_default`.
  3. Al final, `docker compose down`.
- Crear `docs/mediciones/06-tiempo-de-respuesta.md`, con el resultado de la corrida y la máquina en la que corrió.

- [ ] **Paso 1: escribir el script de k6 con los umbrales.** Correrlo contra el compose sin la carga de volumen: tiene
  que pasar. Es la línea de base.

- [ ] **Paso 2: verificar con volumen.** `./scripts/run-load-test.sh` termina con 0, con todos los umbrales cumplidos.
  Se guarda el resumen de k6 en `06-tiempo-de-respuesta.md`. Si un umbral no se cumple, se registra el número y se
  revisa contra el RFC 3.5 antes de tocar nada.

- [ ] **Paso 3: commit.** `perf: measure the response time with two million orders`.

Al terminar la tarea 19: PR de `feat/phase-6-observability` a `develop`, con merge commit.

---

## Fuera de este plan

- **El portal HTML de la fase 7,** con Zensical (RFC 11.6).
- **El README completo, la revisión final, el repo público y el mail de la fase 8.**
- **Si a Neon se le aplican las migraciones,** que se decide en la fase 8 (ADR 0006).

## Cobertura del RFC y del PRD

| Qué | Tareas |
|---|---|
| RFC 3: reglas, columnas, fecha y hora, índice, extensiones y migraciones | 2 |
| RFC 4.1: la cuenta | 6 |
| RFC 4.2: el último precio | 4 |
| RFC 4.3: la búsqueda | 5 |
| RFC 4.4: el listado | 12 |
| RFC 4.5: precisión y redondeo | 4, 6, 7 y 8 |
| RFC 5.2: el turno | 6 y 10 |
| RFC 5.3: los reintentos | 9 y 10 |
| RFC 5.4: la cancelación | 11 |
| RFC 6: los contratos | 5, 7, 9, 11, 12 y 13 |
| RFC 7: errores y validación | 3, y los casos de error de cada función |
| RFC 8: logs, métricas, tablero y tiempo de respuesta | 3 (id de pedido), 17, 18 y 19 |
| RFC 9: seguridad | 3, 14 y 17 |
| RFC 10: tests | 1, 2 y 10 |
| RFC 11: build, CI, Docker, Postman | 1, 14, 15 y 16 |
| POR, casos 1 a 11 | 6, 7, 9 y 11 |
| BUS, casos 1 a 9 | 5 |
| ENV, casos 1 a 20 | 8, 9, 10 y 11 |
| CAN, casos 1 a 6 | 11 |
| LIS, casos 1 a 5 | 12 |
| GEN, casos 1 a 5 | 10 (1 y 2), 14 (3), 17 (4 y 5) |
