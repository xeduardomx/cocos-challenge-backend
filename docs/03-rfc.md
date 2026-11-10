# 03 — RFC: cómo se construye la API

> Fase 3 · 2026-11-07 · Estado: **aprobado por el autor el 2026-11-07** · Revisado el 2026-11-10 (fase 8): se agregó
> la sección 5.5
>
> El PRD ([`02-prd.md`](02-prd.md)) dice qué tiene que hacer la API; este documento dice cómo se construye. Cubre lo
> que el PRD dejó para esta fase (su sección 6) y los cambios a la base que quedaron pendientes en
> [`01-decisiones.md`](01-decisiones.md). Los códigos entre paréntesis remiten a esos documentos: A-4 o R-2, al
> análisis; ENV-9 o GEN-1, al PRD. Cada decisión grande tiene además un ADR en la carpeta [`adr/`](adr/), con las
> opciones que se evaluaron. La sección 12 reúne las decisiones de esta fase.

## Resumen

Una API en Java 25 y Spring Boot 4.1, en capas agrupadas por función, sobre la base de Cocos con cambios mínimos y
medidos. Lo central:

- **La plata y las acciones nunca se gastan dos veces.** Cada envío toma el turno de su usuario, un lock sobre su
  fila, y cada orden trae un identificador que convierte un reintento en la misma orden. Probado en la copia local:
  sin turno, dos ventas simultáneas dejan el mismo −10 que la orden 11 (sección 5). Como segunda red, una orden igual
  a otra recién aceptada, pero con otro identificador, se frena como posible duplicado (sección 5.5).
- **La cuenta se calcula desde las órdenes, en un solo lugar y en Java,** con un solo índice nuevo para las consultas:
  1,2 ms en la base para un usuario de 10.000 órdenes, con 2 millones en la tabla (secciones 3.5 y 4).
- **La base no acepta datos imposibles:** reglas que miran una fila, verificadas contra los datos de hoy, y tres
  columnas nuevas en `orders`. Los cambios se aplican con Flyway, por el mismo camino en la copia local y en Neon
  (sección 3).
- **Los contratos son simples y explícitos:** cinco direcciones, una orden rechazada es una orden (201), los errores
  van en Problem Details con un catálogo fijo y la especificación OpenAPI se publica (secciones 6 y 7).
- **Todo se ve y se prueba:** logs en JSON con el id de cada pedido, métricas en Prometheus y Grafana, tests en tres
  niveles con los casos del PRD, y un `docker compose up` que levanta todo (secciones 8, 10 y 11).

No simula el mercado ni autentica usuarios, como pide el enunciado (sección 1).

<!-- --8<-- [start:sistema] -->
```mermaid
flowchart LR
    app([App o Postman]) -->|las 5 funciones, por HTTP| api["API<br/>Java 25 y Spring Boot 4.1"]
    api -->|SQL con JdbcClient, con el turno del usuario| pg[("Postgres 18<br/>datos de Cocos y migraciones")]
    prom["Prometheus"] -->|lee las métricas cada 15 s| api
    graf["Grafana<br/>tablero"] --> prom
```
<!-- --8<-- [end:sistema] -->

## 1. Contexto y principios

**Qué se construye.** Una API HTTP en Java con cinco funciones: portfolio, búsqueda de activos y envío de órdenes,
que pide el enunciado, más cancelación y listado de órdenes, que se agregaron en el análisis (A-7). Trabaja sobre la
base que mandó Cocos: usuarios, instrumentos, precios y órdenes. Qué hace cada función, y con qué casos se prueba,
está en el PRD. Esta sección junta lo que condiciona el cómo y los criterios para decidirlo.

**Lo que condiciona el diseño**

| Condición | De dónde sale | Qué implica |
|---|---|---|
| **Java** | el mail de Cocos, que pisa el Node.js del enunciado (C-1) | el lenguaje está dado; el framework y las librerías se eligen en la sección 2 |
| **Se ejecuta localmente con un comando** | el mail, GEN-2 | todo se levanta con `docker compose up`, y alcanza con tener Docker |
| **La base ya existe y tiene datos** | el enunciado; `database.sql`, idéntico a Neon: 4 usuarios, 66 instrumentos, 126 precios y 11 órdenes | se cambia con migraciones y solo donde se justifica. Los datos heredados no se corrigen (D-1, D-4, D-6), así que cada regla nueva tiene que convivir con los que ya están |
| **Neon se trata como producción** | "Base de Neon" | la API y los tests corren contra una copia local en Postgres 18, la misma versión mayor que Neon; los tests nunca tocan Neon. La dirección de la base se configura, y en la fase 8 se le aplicaron las migraciones, una sola vez (sección 3.6) |
| **Sin autenticación** | el enunciado (S-5) | el número de usuario viaja en cada pedido; en producción saldría de la sesión |
| **No se simula el mercado** | el enunciado | una orden a mercado se resuelve en el momento, al último precio; una límite aceptada queda pendiente hasta que el usuario la cancela. No hay horario de mercado ni vencimientos |
| **Hoy hay 11 órdenes, pero el diseño tiene que aguantar volumen** | "Esquema: se mejora", A-4, A-7, GEN-8 | cada consulta se piensa para mucho volumen y se mide con 2 millones de órdenes (sección 3.5), como en el análisis |
| **Test funcional del envío** | el enunciado, GEN-3 | el envío se prueba de punta a punta contra una base real, con los casos del PRD |

**Principios**

Cuando hay que elegir entre opciones que cumplen el PRD, se decide con estos criterios. El orden importa: si dos
chocan, gana el de más arriba.

1. **La plata y las acciones nunca se gastan dos veces,** ni con dos órdenes al mismo tiempo (R-1) ni con un
   reintento (R-2). Es el riesgo más serio del challenge: la orden 11 vendió 30 BMA con 20 en la cuenta y dejó la
   posición en −10, y dos ventas de 15 que llegan juntas y ven las dos "tiene 20" dejarían el mismo resultado (D-1).
   Una opción más simple que deje abierta esa puerta no sirve.
2. **Nunca se cambia lo que el usuario autorizó.** Un precio con más de 2 decimales se rechaza en vez de redondearse
   (D-4), y en una orden por monto la cantidad se corta para abajo, así nunca supera lo pedido (A-6).
3. **La base no acepta datos imposibles.** Las reglas que se ven en una sola fila, como una orden sin usuario o un
   lado que no existe (`side = 'COMPRA'`), van en la base. Las que necesitan sumar muchas filas, como "no vender más
   de lo que se tiene", van en el código (D-1).
4. **Cada dato vive en un solo lugar.** Las posiciones se calculan desde las órdenes y no se copian a otra tabla, así
   no hay dos versiones que puedan diferir (A-4).
5. **Lo que se afirma, se mide o se prueba.** Un índice entra con su medición antes y después (A-4, A-7), y los casos
   del PRD son la base de los tests (sección 10).
6. **Lo simple gana.** Entre dos opciones que cumplen lo anterior, va la más simple. Cada capa, patrón o librería
   extra se justifica en un ADR, o no entra (`CLAUDE.md`).

## 2. Arquitectura

**Versiones**

| Pieza | Versión | Por qué |
|---|---|---|
| Java | 25, de soporte largo (LTS) | Es la LTS vigente, con soporte hasta septiembre de 2031. La anterior, 21, también serviría, pero su soporte termina en diciembre de 2029. Las que salieron después, 26 y 27, no son LTS y duran 6 meses |
| Spring Boot | 4.1 (la 4.1.2 al 2026-11-07) | Es la línea estable, con soporte gratuito hasta julio de 2027. La 3.5 lo perdió en junio de 2026, la 4.0 lo pierde en diciembre y la 4.2 todavía está en prueba |
| Postgres | 18 | La misma versión mayor que Neon (18.6) |

Encajan entre sí: Spring Boot 4.1.2 pide Java 17 o más y soporta hasta Java 27, y JaCoCo, que mide la cobertura
(sección 10), soporta Java 25 desde su versión 0.8.14. Verificado el 2026-11-07 en endoflife.date
([Spring Boot](https://endoflife.date/spring-boot), [Temurin](https://endoflife.date/eclipse-temurin)), en los
[requisitos de Spring Boot](https://docs.spring.io/spring-boot/system-requirements.html), en los anuncios de la 4.2
([M2](https://spring.io/blog/2026/09/25/spring-boot-4-2-0-M2-available-now/),
[RC1](https://infoq.com/news/2026/10/spring-news-roundup-oct19-2026)) y en los
[cambios de JaCoCo](https://www.jacoco.org/jacoco/trunk/doc/changes.html).

**Por qué Spring Boot.** Trae resuelto casi todo lo que pide la vara de calidad de `CLAUDE.md`: validación de lo que
llega (Bean Validation), errores con formato estándar (Problem Details), métricas (Micrometer y Actuator), logs
estructurados, transacciones, acceso a la base con `JdbcClient` y tests contra un Postgres real con Testcontainers.
Quarkus y Micronaut arrancan más rápido y usan menos memoria: son ventajas cuando se levantan y apagan muchas
instancias por minuto, no en una API que queda corriendo.

**Estilo: capas, agrupadas por función** ([ADR 0001](adr/0001-capas-por-funcion.md))

Cada función tiene tres capas, y cada capa hace una sola cosa:

| Capa | Qué hace | Qué no hace |
|---|---|---|
| controller | recibe el pedido HTTP, valida su forma y arma la respuesta | no aplica reglas de negocio ni toca la base |
| service | abre la transacción, aplica las reglas y decide el resultado, por ejemplo `FILLED` o `REJECTED` | no escribe SQL ni conoce HTTP |
| repository | ejecuta el SQL y convierte cada fila en un record de Java | no decide nada |

- **Los cálculos van en clases de Java puro,** sin Spring ni base: pesos disponibles, PPC, rendimiento, cantidad por
  monto y reservas. Se prueban con tests unitarios, rápido y caso por caso.
- **La cuenta del usuario se calcula en un solo lugar,** el paquete `account`: pesos, reservas, disponibles y
  posiciones. La usan el portfolio y el envío (principio 4). Si cada uno tuviera su fórmula, el portfolio podría
  mostrar $627.500 disponibles y el envío validar contra otro número. Si se calcula en la base o en Java se decide en
  la sección 4.
- **El catálogo de activos y sus precios viven en `instruments`,** junto con la búsqueda: el portfolio, el envío y la
  búsqueda leen el último precio del mismo lugar.
- **Lo común** (el formato de los errores, el id de cada pedido y la configuración) va en `shared`.
- **Las dependencias van en un solo sentido:** controller → service → repository, y de cada función hacia `account` e
  `instruments`, nunca al revés. Los cálculos no dependen de nada.

<!-- --8<-- [start:funciones] -->
```mermaid
flowchart LR
    app([App])
    subgraph api["API · Spring Boot"]
        portfolio["portfolio"]
        orders["orders<br/>envío, cancelación y listado"]
        instruments["instruments<br/>búsqueda, activos y precios"]
        account["account<br/>pesos, reservas y posiciones"]
    end
    db[("Postgres")]
    app --> portfolio
    app --> orders
    app --> instruments
    portfolio --> account
    portfolio --> instruments
    orders --> account
    orders --> instruments
    orders --> db
    account --> db
    instruments --> db
```
<!-- --8<-- [end:funciones] -->

`shared` no aparece en el diagrama porque lo usan todos.

**Adentro de cada función, un paquete por capa.** Los nombres son los mismos en todas, así se sabe dónde buscar:

| Paquete | Qué tiene | Ejemplo en `orders` |
|---|---|---|
| `web` | el controller, con el pedido y la respuesta en JSON y su validación | `OrderController`, `SubmitOrderRequest`, `OrderResponse` |
| `service` | los services: los pasos de cada función y su transacción | `OrderSubmissionService`, `OrderCancellationService`, `OrderQueryService` |
| `domain` | los records, los enums y los cálculos en Java puro | `StoredOrder`, `OrderType`, `OrderEvaluator` y sus reglas |
| `repository` | el SQL | `OrderRepository` |
| `config` | los beans y los números configurables | `OrdersConfiguration`, `OrderPageProperties` |
| `error` | los errores de la función | `OrderNotFoundException` |
| `observability` | las métricas y los logs de negocio | `OrderMetrics`, `OrderEventLogger` |

- **Cada función tiene solo los paquetes que usa.** `portfolio` no tiene `repository`: lee de la base a través de
  `account` e `instruments`. `account` no tiene `web`: no tiene una dirección propia.
- **En `orders`, `domain` se divide además por tema:** `submission`, `evaluation` y `listing`.
- **`shared` se ordena por tema** (`domain`, `error`, `web`, `validation` y `display`), porque no es una función.

**Por qué no hexagonal** (ni Clean Architecture, que sigue la misma idea). Sus interfaces ("puertos") sirven para
cambiar lo de afuera sin tocar el núcleo, y acá lo único de afuera es la base. Lo más delicado, la transacción del envío, se prueba contra Postgres real de todas formas.
Cambiaría si la API mandara órdenes a un mercado real: ahí se agrega un puerto para el mercado, no toda la
arquitectura.

**Acceso a datos: SQL directo con `JdbcClient`** ([ADR 0002](adr/0002-sql-directo-con-jdbcclient.md))

Cada repository escribe su SQL con `JdbcClient`, que viene con Spring: Spring maneja las conexiones, los parámetros y
las transacciones, y convierte cada fila en un record. Las transacciones se marcan en los services. El SQL verificado
en el análisis es el punto de partida.

**Por qué no JPA.** Las consultas importantes de esta API (`unaccent` en la búsqueda, el cursor del listado, el
último precio con `LATERAL`, el turno con `FOR UPDATE`) terminarían en SQL nativo igual, y JPA agrega comportamiento
escondido justo en el camino más delicado. Por ejemplo, cancelar en un solo paso (A-7) es un
`UPDATE ... WHERE status = 'NEW'`; con JPA, lo habitual es leer la orden, cambiarla en memoria y grabarla al
confirmar. jOOQ validaría el SQL al compilar, pero obliga a generar código desde el esquema y a regenerarlo cada vez
que cambia: para unas 10 consultas no se justifica.

**Recorrido de un pedido**

El envío de una orden es el recorrido más completo:

1. **Llega el pedido.** Un filtro le asigna un id, que acompaña a todos los logs de ese pedido (sección 8).
2. **El controller valida la forma:** que estén los datos y con valores válidos, que venga la cantidad o el monto (uno
   de los dos), que la cantidad sea entera y mayor a 0, que montos y precios sean mayores a 0 y con hasta 2 decimales,
   y que la combinación tenga sentido (una límite lleva precio; una a mercado, no). Si algo falla, responde 400 con el
   error en formato estándar (sección 7), y no se graba nada.
3. **El service abre una transacción** y, dentro de ella:
   1. toma el turno del usuario, para que sus órdenes se procesen de a una (sección 5);
   2. se fija si el identificador de la orden ya existe: si es un reintento con el mismo contenido, devuelve la orden
      original, y con otro contenido, da error (sección 5);
   3. le pide la cuenta del usuario a `account` y el activo con su último precio a `instruments`;
   4. si con esos datos no se puede armar la orden (el activo no existe o es ARS; por monto, la cantidad da 0 o no
      entra en `size`; o por monto y a mercado, el activo no cotiza), da error y no graba nada (A-6). Si se puede, los cálculos deciden el resultado: `FILLED`, `NEW` o `REJECTED`, con
      su motivo;
   5. si iba a quedar `FILLED` o `NEW`, se fija si el usuario tiene otra orden igual, aceptada hace menos de 5 segundos:
      si la tiene, da error y no graba nada (sección 5.5);
   6. graba la orden.
4. **Los repositories** ejecutan cada paso en SQL, dentro de esa misma transacción.
5. **El controller responde** con la orden y su estado (sección 6), y las métricas la cuentan por tipo, lado y estado
   (sección 8).

**Qué queda para el plan.** Este RFC fija los paquetes, qué hace cada capa y las reglas que el código tiene que
cumplir. Las clases y los archivos concretos los define el plan de implementación (fase 4), tarea por tarea y con el
test de cada una primero.

## 3. Modelo de datos y cambios a la base

El modelo de Cocos se mantiene: las cuatro tablas, sus columnas y sus nombres (C-2). Los cambios son de tres tipos:

- **reglas,** para que la base no acepte datos imposibles (principio 3);
- **tres columnas nuevas** en `orders`, para el identificador de la orden y el motivo del rechazo (R-2, A-6);
- **un índice nuevo para las consultas,** con su medición antes y después (principio 5). Las reglas de unicidad crean
  los suyos.

**Los cambios, migración por migración.** Cada uno es un archivo de `api/src/main/resources/db/migration/` (sección
3.6), y ninguno borra datos de Cocos.

| Migración | Qué cambia | Tipo | Para qué | Detalle |
|---|---|---|---|---|
| V1 | `database.sql` de Cocos | se anota, no se ejecuta | marca el punto de partida, que Neon ya tenía | 3.6 |
| V2 | `unaccent` | extensión | buscar sin acentos: "compania" encuentra "Compañía" (A-5) | 3.5 |
| V3 | datos obligatorios, valores permitidos y unicidad en `orders`, `instruments` y `marketdata` | reglas: `not null`, `check` y `unique` | que la base no acepte datos imposibles | 3.2 |
| V4 | `clientOrderId`, `requestFingerprint` y `rejectionReason`, con sus reglas | columnas nuevas, `unique` y `check` | que un reintento no cree otra orden, y que una `REJECTED` diga por qué | 3.3 y 3.2 |
| V5 | `datetime` | cambio de tipo, valor por defecto y `not null` | hora con zona, puesta por la base al grabar | 3.4 |
| V6 | `orders_user_datetime_idx` | índice | portfolio, listado y paginación rápidos para cualquier usuario | 3.5 |

Todo se probó en la copia local (Postgres 18.6, con `database.sql`). La forma exacta está en
[`mediciones/03-esquema.sql`](mediciones/03-esquema.sql), el borrador verificado del que salieron las migraciones de la
fase 5. Cómo se aplican las migraciones, y qué pasa con Neon, está en la sección 3.6. Lo que esta sección marca como
probado tiene su script y su salida en [`mediciones/`](mediciones/); las trampas y Flyway, en
[`03-pruebas-resultados.md`](mediciones/03-pruebas-resultados.md).

### 3.1 El modelo, con los cambios

<!-- --8<-- [start:modelo] -->
```mermaid
erDiagram
    users ||--o{ orders : "manda"
    instruments ||--o{ orders : "se opera en"
    instruments ||--o{ marketdata : "tiene precios en"
    users {
        int id PK
        varchar email "sin cambios"
        varchar accountnumber "sin cambios"
    }
    instruments {
        int id PK
        varchar ticker UK "obligatorio y único"
        varchar name "obligatorio"
        varchar type "obligatorio"
    }
    orders {
        int id PK
        int instrumentid FK "obligatorio"
        int userid FK "obligatorio"
        int size "obligatorio, 1 o más"
        numeric price "mayor a 0, vacío solo en una REJECTED a mercado"
        varchar type "MARKET o LIMIT"
        varchar side "BUY, SELL, CASH_IN o CASH_OUT"
        varchar status "NEW, FILLED, REJECTED o CANCELLED"
        timestamptz datetime "con zona, la pone la base al grabar"
        uuid clientorderid "nueva, única por usuario"
        varchar requestfingerprint "nueva, huella del pedido"
        varchar rejectionreason "nueva, código del motivo"
    }
    marketdata {
        int id PK
        int instrumentid FK "obligatorio"
        numeric high
        numeric low
        numeric open
        numeric close "obligatorio"
        numeric previousclose
        date date "obligatorio, una fila por acción y fecha"
    }
```
<!-- --8<-- [end:modelo] -->

### 3.2 Reglas

Cada regla mira una sola fila. Las que necesitan sumar muchas filas, o mirar otra tabla, van en el código (principio
3, D-1).

| Tabla | Regla | Cómo queda en la base | Por qué |
|---|---|---|---|
| `orders` | Obligatorios: activo, usuario, cantidad, tipo, lado, estado y fecha | `not null` en las siete columnas (la fecha, en V5) | una orden sin usuario o sin cantidad no se puede calcular (E-1) |
| `orders` | El lado, el tipo y el estado, solo con los valores del enunciado | un `check` por columna, con la lista de valores: los mismos enums que usa Java | `side = 'COMPRA'` es un dato imposible (E-2) |
| `orders` | Cantidad de 1 o más | `check (size >= 1)` | una orden de 0 acciones nunca se graba (A-6) |
| `orders` | Precio mayor a 0, y vacío solo en una `REJECTED` a mercado | dos `check`: `price > 0`, y `price is not null or (status = 'REJECTED' and type = 'MARKET')` | una orden a mercado sobre un activo sin cotización queda sin precio (A-6). Con precio mayor a 0, el costo nunca es cero (A-2, R-4) |
| `orders` | En depósitos y retiros (`CASH_IN`, `CASH_OUT`), precio 1 | `check`: si el lado es `CASH_IN` o `CASH_OUT`, `price = 1` | `size × price` es el monto en pesos: con precio 2, un depósito de $1.000 valdría $2.000 (D-9) |
| `orders` | El identificador es único por usuario, y viaja con su huella: están las dos o ninguna | `unique (userid, clientOrderId)`, y un `check`: las dos vacías o las dos con valor (V4) | un reintento no crea otra orden, ni aunque lleguen dos a la vez, y dos usuarios pueden usar el mismo identificador (R-2) |
| `orders` | El motivo del rechazo: uno de los tres códigos, y solo en una `REJECTED` | dos `check`: la lista de códigos, y vacío salvo en una `REJECTED` (V4) | un motivo de rechazo en una orden ejecutada no tiene sentido (A-6) |
| `instruments` | Ticker, nombre y tipo obligatorios; el ticker, único | `not null` en las tres, y `unique (ticker)`, que crea su índice | la búsqueda y el portfolio muestran el ticker, y dos activos con el mismo serían ambiguos (E-1, E-3) |
| `marketdata` | Activo, fecha y cierre obligatorios; una fila por acción y fecha | `not null` en las tres, y `unique (instrumentid, date)`, que crea el índice que usa el último precio (3.5) | el último precio es el cierre de la fecha más reciente, y tiene que haber uno solo (D-3) |
| `marketdata` | Precios mayores a 0; mínimo menor o igual al máximo; apertura y cierre entre los dos | dos `check`: los cinco precios mayores a 0, y el rango | un precio de $0, o un mínimo mayor al máximo, son imposibles (D-5) |

`users` no cambia: la API solo verifica que el usuario exista. El tipo de activo no se limita a `ACCIONES` y
`MONEDA`: el catálogo podría sumar bonos o CEDEAR, y la API solo distingue la moneda del resto.

**Conviven con los datos de hoy.** Cada regla se probó contra las 11 órdenes, los 66 activos y los 126 precios, y los
datos cumplen todas. Por eso se agregan verificadas, sin `NOT VALID`, la opción de Postgres que agrega una regla sin
revisar las filas que ya existen. La única regla que los datos rompen, "la posición no puede quedar negativa", no va
en la base (D-1).

**Lo que queda en el código**

| Regla | Por qué no va en la base |
|---|---|
| No vender más de lo que se tiene | depende de la suma de muchas filas, y BMA ya está en −10 (D-1) |
| Toda orden nueva trae identificador | las 11 viejas no lo tienen, y `NOT VALID` no sirve: Postgres vuelve a verificar la regla cuando se modifica una fila vieja, así que cancelar la orden 7 fallaría (probado en la copia local). La API responde 400 si falta (R-2) |
| Toda `REJECTED` tiene motivo | la orden 6, vieja, no lo tiene. Lo asegura la API, con un test |
| Solo se cancela una orden `NEW` | compara la fila antes y después del cambio. Va en el `UPDATE` de la cancelación, que solo cambia la orden si sigue `NEW` (A-7) |
| Depósitos y retiros solo sobre ARS, y ARS no se compra ni se vende | hay que mirar otra tabla (D-9, A-6) |

**Lo que no entra en las columnas.** Un precio de más de $99.999.999,99 no entra en `price` (`NUMERIC(10,2)`), y una
cantidad de más de 2.147.483.647 no entra en `size` (`INT`). Por ejemplo, $15.100 millones por monto en CARC, a $7,
dan 2.157 millones de acciones. Sin validación, esa orden no queda `REJECTED`: la base falla y la respuesta sería un
500. La API valida esos topes antes de grabar y responde 400 (sección 7). Los tipos no se amplían: ninguna orden real
se acerca a esos números, y los precios se quedan en 2 decimales (D-4).

### 3.3 Columnas nuevas en `orders`

| Columna | Tipo | Qué guarda | Para qué | En las 11 órdenes viejas |
|---|---|---|---|---|
| `clientOrderId` | `uuid` | el identificador que pone la app en cada orden (R-2) | si la app reintenta el mismo pedido, la API devuelve la orden ya grabada (200) en lugar de crear otra | vacía |
| `requestFingerprint` | `varchar(64)` | la huella del pedido: un resumen de sus datos (SHA-256, en hexadecimal) | si llega el mismo identificador con otro contenido, la API responde 422 en lugar de devolver una orden que no es la pedida | vacía |
| `rejectionReason` | `varchar(30)` | el motivo del rechazo: `INSUFFICIENT_CASH`, `INSUFFICIENT_SHARES` o `NO_MARKET_PRICE` | el usuario sabe por qué se rechazó, y la métrica de rechazos cuenta por motivo (GEN-4) | vacía, también en la orden 6 (LIS-4) |

Siguen la convención de las columnas de Cocos: se escriben `clientOrderId` y Postgres las guarda en minúsculas
(`clientorderid`), igual que `instrumentId`. Con `client_order_id` habría dos convenciones en la misma tabla.

- **El identificador es un UUID,** por ejemplo `3f2b8c1e-9d4a-4c7b-8e21-5a6f0b9c2d13`. Lo genera la app y el usuario
  nunca lo ve (R-2). En Postman sale con `{{$guid}}`, y desde una terminal, con `uuidgen`. Tiene largo fijo, así que
  cierra el "largo máximo" de ENV-9. Un texto libre, como el `ClOrdID` de FIX, sería más flexible, pero obligaría a
  fijar un largo y qué caracteres valen, y "abc" y "ABC" serían dos identificadores distintos.
- **La huella compara el contenido de un reintento.** El mismo pedido da la misma huella, aunque el precio haya
  cambiado entre el envío y el reintento. Guardar el pedido tal cual, en JSON, se leería mejor, pero copiaría el
  activo, el lado y el tipo, que ya están en la orden (principio 4), y guardaría el monto pedido, que A-6 decidió no
  guardar. Qué datos entran en la huella se define en la sección 5.
- **El motivo es un código, no un texto.** El texto que ve el usuario ("no alcanzan los pesos") lo arma la API
  (sección 7), y la métrica de rechazos por motivo (GEN-4) cuenta por código. Con texto libre, "no alcanzan los pesos"
  y "fondos insuficientes" contarían como dos motivos distintos.
- **No se guarda cuándo cambió el estado** (A-7 lo dejaba como candidato). El PRD no lo muestra, la orden 3, que está
  cancelada, quedaría con la fecha vacía, y una columna guarda solo el último cambio. Para auditar haría falta un
  historial de cambios, que queda como mejora posible.

### 3.4 La fecha y hora de las órdenes

| Cambio a `datetime` | Tipo | Qué hace | Por qué |
|---|---|---|---|
| `timestamp` a `timestamptz` | cambio de tipo | guarda la hora con zona, y las horas existentes se leen como hora argentina | sin zona, la hora es ambigua, y la sesión de Neon está en GMT |
| `default clock_timestamp()` | valor por defecto | la base pone la hora en el momento en que graba la orden | `now()` daría la hora en que empezó la transacción, antes de esperar el turno |
| obligatoria | `not null` | ninguna orden queda sin hora | el portfolio, el listado y el cursor ordenan por hora |

- **Pasa a fecha y hora con zona** (`timestamptz`), leyendo las fechas existentes como hora argentina (D-10). La
  conversión nombra la zona (`AT TIME ZONE 'America/Argentina/Buenos_Aires'`), y la orden 2 sigue a las 12:31 de
  Buenos Aires. Con la conversión simple, la base usaría la zona de la sesión, que en Neon es GMT, y la orden 2
  quedaría a las 9:31, con el mercado cerrado (probado en la copia local).
- **La pone la base al grabar la orden, con `clock_timestamp()`.** Es un solo reloj aunque haya varias instancias de
  la API. Y como la orden se graba dentro del turno del usuario (sección 5), el orden por fecha es el orden en que se
  procesaron sus órdenes, del que depende el PPC (A-2). `now()`, lo más común, no sirve: es la hora en que empezó la
  transacción, antes de esperar el turno (probado en la copia local: quedó fija mientras pasaban 1,5 segundos). Esto precisa D-10, que
  decía "el momento en que se recibe": es el momento en que se graba, milisegundos después.
- **Guarda microsegundos.** La respuesta del envío y el cursor del listado usan la fecha tal como quedó grabada
  (sección 6), así no se pierde precisión al pasar de página (A-7).

### 3.5 Índices

| Qué | Tipo | Qué hace | Por qué |
|---|---|---|---|
| `orders_user_datetime_idx`, sobre `(userid, datetime, id)` | índice nuevo (V6) | encuentra las órdenes de un usuario ya ordenadas por hora | lo usan el portfolio, la cuenta del envío, el listado y su cursor; las mediciones están abajo |
| `unique (ticker)` en `instruments` | índice que crea la regla (V3) | impide dos activos con el mismo ticker | viene con la regla de unicidad; la búsqueda no lo usa, porque recorre los 65 activos (4.3) |
| `unique (instrumentid, date)` en `marketdata` | índice que crea la regla (V3) | encuentra el precio más reciente de cada acción | viene con la regla; lo usa la búsqueda del último precio (4.2) |
| `unique (userid, clientOrderId)` en `orders` | índice que crea la regla (V4) | encuentra una orden por su identificador | viene con la regla; lo usa cada envío para detectar un reintento |
| `unaccent` | extensión (V2) | saca los acentos | la búsqueda sin acentos (A-5); `pg_trgm` no hace falta |

Por eso se dice "un solo índice nuevo": es el único que se creó a propósito con `create index`. Los otros tres
vienen con su regla de unicidad, porque así la asegura Postgres.

**Cómo se midió.** En la copia local, con este esquema y 2.010.011 órdenes: las 11 de Cocos y 2.010.000 inventadas,
repartidas así:

- 10.000 usuarios típicos, de 100 órdenes cada uno;
- uno pesado, de 10.000;
- uno extremo, de 1.000.000, que sirve para ver dónde está el límite: ningún inversor común llega a eso.

Cada consulta corrió 7 veces (el portfolio del extremo, 3) y se toma la mediana. Son números de una PC con los
datos en memoria: sirven como orden de magnitud. Los scripts están en [`mediciones/`](mediciones/); la salida
completa de la corrida, en [`mediciones/03-resultados.md`](mediciones/03-resultados.md); y al final de esta parte
está cómo repetirla.

**Hoy, sin índices, cualquier consulta lee las 2 millones de órdenes:** tarda entre 33 y 54 ms aunque el usuario
tenga 100, y el tiempo crece con la tabla. Con un índice por **(usuario, fecha, id)**:

| Consulta | Típico (100) | Pesado (10.000) | Extremo (1.000.000) |
|---|---|---|---|
| Portfolio y cuenta del envío: las órdenes `FILLED` y `NEW`, en orden | 54 → 0,017 ms | 55 → 1,2 ms | 304 → 175 ms |
| Listado, primera página | 33 → 0,006 ms | 35 → 0,006 ms | 91 → 0,007 ms |
| Listado, última página (cursor) | 40 → 0,007 ms | 41 → 0,007 ms | 42 → 0,007 ms |
| Pendientes (filtro `NEW`) | 40 → 0,012 ms | 40 → 0,7 ms | 42 → 131 ms |
| Rechazadas (filtro `REJECTED`) | 40 → 0,012 ms | 39 → 0,026 ms | 48 → 0,029 ms |

- **El listado queda instantáneo para cualquier usuario, en cualquier página.** El índice tiene el orden que
  necesita el cursor de A-7 para ir directo a donde quedó. El índice del identificador (R-2) también empieza por el
  usuario y ya ayuda algo (el portfolio del pesado, 3,2 ms), pero no está ordenado por fecha: con él, el listado del
  extremo tarda 91 ms.
- **Las pendientes del extremo empeoran,** de 42 a 131 ms. Con el índice, la base recorre en orden su millón de
  órdenes buscando sus 8 pendientes, y eso es más lento que leer de corrido toda la tabla de esta prueba. Con una
  tabla más grande, ganaría el índice.
- **El portfolio es la consulta más cara de cada usuario, y ningún índice lo baja de ahí.** El del extremo lee unas
  850.000 órdenes ejecutadas y pendientes. Es el límite conocido de calcular desde las órdenes (A-4), y la salida es
  la foto a una fecha, documentada como evolución posible.
- **El índice cuesta en las altas:** en una carga masiva de 100.000 órdenes, cada una pasa de 12 a 19 microsegundos
  con él.

**Alternativas medidas.** A-7 había decidido un índice por (usuario, estado, fecha, id) para el filtro por estado.
Se descarta, igual que las otras dos opciones:

| Alternativa | Qué mejora | Qué cuesta |
|---|---|---|
| Sumar (usuario, estado, fecha, id), el de A-7 | pendientes del extremo: 131 → 0,006 ms | es el índice más grande: 95 MB, contra 78 del de fecha, en una tabla de 342. En la carga masiva, cada alta pasa de 19 a 25 microsegundos, un 30 % más |
| Usar solo (usuario, estado, fecha, id), sin el de fecha | lo mismo | el listado sin filtro del extremo vuelve a 83 ms |
| Sumar un índice parcial, que guarda solo las pendientes | pendientes del extremo: 131 → 0,005 ms, con 2 MB y casi sin costo en las altas (20 microsegundos, contra 19) | solo se usa si la consulta lleva `'NEW'` escrito en el SQL. Cuando una consulta se repite mucho, Postgres puede pasar a un plan general, que no sabe qué estado se pide, y ese plan no lo usa (probado: 128 ms) |

Ninguna amplía los usuarios que la API aguanta. **Las pendientes siempre cuestan menos que el portfolio del mismo
usuario** (0,7 contra 1,2 ms en el pesado; 131 contra 175 ms en el extremo), y ninguna de las tres mejora el
portfolio. Esto cambia la decisión 7 de A-7. **Cuándo revisarlo:** si se hace la foto a una fecha, el portfolio deja
de leer todo el historial y las pendientes pasan a ser la consulta más lenta. Ahí se suma el índice parcial, con
`'NEW'` escrito en el SQL.

**Las otras tablas**

- **`marketdata`:** el índice viene con la regla "una fila por acción y fecha". Con 10 años de precios diarios
  (164.430 filas), el último precio de 20 acciones, buscado acción por acción, tarda 0,06 ms con el índice y 91 ms
  sin él. Para usar el índice, la consulta tiene que buscar acción por acción: la sección 4.2 compara las formas de
  escribirla.
- **Búsqueda:** sin índice, como anticipaba A-5. Tarda 0,06 ms con los 65 activos y entre 11 y 17 ms con un catálogo
  inventado de 10.000. Si el catálogo creciera tanto, la mejora sería un índice de trigramas (`pg_trgm`) sobre el
  ticker y el nombre sin acentos. `unaccent` no puede ir directo en un índice, porque Postgres no la marca como
  inmutable: haría falta una función propia que la envuelva. Queda como evolución posible.
- **`orders.instrumentid`:** sin índice. Postgres no crea solo los índices de las claves foráneas, y este solo
  serviría para borrar activos o para buscar las órdenes de un activo entre todos los usuarios, y la API no hace
  ninguna de las dos cosas. Con 2 millones de órdenes, borrar un activo tarda 73 ms, porque revisa todas.

**Extensiones.** Se instala `unaccent`, para buscar sin acentos (A-5). `pg_trgm` no hace falta.

**Cómo repetir la medición.** En una copia descartable, nunca en Neon. Tarda unos minutos. La espera pregunta por
red (`-h 127.0.0.1`) porque, al arrancar, la imagen de Postgres levanta un servidor provisorio que solo escucha
localmente y después lo reinicia: preguntando sin red, el script puede encontrarlo y fallar.

```bash
docker run --rm -d --name cocos-medicion-pg18 -e POSTGRES_PASSWORD=medicion postgres:18
until docker exec cocos-medicion-pg18 pg_isready -h 127.0.0.1 -U postgres -q; do sleep 1; done
docker exec -i cocos-medicion-pg18 psql -U postgres -X -q -v ON_ERROR_STOP=1 < docs/00-fuentes/database.sql
docker exec -i cocos-medicion-pg18 psql -U postgres -X -q -v ON_ERROR_STOP=1 < docs/mediciones/03-esquema.sql
docker exec -i cocos-medicion-pg18 psql -U postgres -X < docs/mediciones/03-indices.sql
docker exec -i cocos-medicion-pg18 psql -U postgres -X < docs/mediciones/03-precios-y-busqueda.sql
docker stop cocos-medicion-pg18
```

Las demás pruebas corren cada una en una copia nueva, sin los datos de `03-indices.sql`:

- `03-trampas.sh` y `03-flyway.sh`, con las tres primeras líneas de arriba, o sea solo con `database.sql`.
  `03-flyway.sh` le aplica `03-esquema.sql` como la versión 2;
- `04-consultas.sql`, `05-turno.sh` y `10-volver-a-los-originales.sql`, con las cuatro primeras, que también aplican
  `03-esquema.sql`.

Los `.sql` se pasan a `psql` como arriba, y los `.sh` se corren directo, por ejemplo `./docs/mediciones/05-turno.sh`.

### 3.6 Migraciones y Neon

**La herramienta: Flyway** ([ADR 0006](adr/0006-migraciones-con-flyway.md)). Viene integrada con Spring Boot: con
`spring-boot-starter-flyway` y el módulo `flyway-database-postgresql`, Spring Boot 4.1.2 trae Flyway 12.4.0. Cada
migración es un archivo SQL numerado (`V2__....sql`) que se lee tal cual, y sale del SQL probado en
[`mediciones/03-esquema.sql`](mediciones/03-esquema.sql). Flyway anota en una tabla propia, `flyway_schema_history`, qué migraciones se aplicaron, y detecta si alguna se editó
después.

- **Liquibase** también serviría. Acepta cambios en SQL, XML o YAML, y su fuerte es describir un cambio una vez para
  cualquier motor de base y poder deshacerlo (en Flyway, deshacer es de la edición paga). Acá hay un solo motor, y los
  cambios van siempre hacia adelante.
- **Scripts a mano, sin herramienta,** no dejan registro de qué se aplicó: el pase a Neon dependería de acordarse.

**Cómo se arranca sobre una base con datos y sin historial.** `database.sql` es la versión 1, y nuestras migraciones
empiezan en la 2. Con `baseline-on-migrate`, Flyway encuentra una base con tablas y sin historial, la anota como
versión 1 sin tocarla ("database.sql de Cocos") y aplica de la 2 en adelante. La copia local se arma igual que Neon:
Postgres carga `database.sql` al crear la base, y después la API migra. Así, el camino que recorrió Neon es el que
se prueba todos los días.

- **Probado** con Flyway 12.4.0 en la copia local (Postgres 18.6). Anotó la versión 1, aplicó la 2, y una segunda
  corrida no hizo nada. `unaccent` quedó instalada, y la orden 2 siguió a las 12:31 de Buenos Aires.
- **Neon, revisado en solo lectura:** no tiene historial de migraciones y `unaccent` está disponible, sin instalar.
  Sigue con 11 órdenes y el contador de ids en 11. El rol de la URL (`neondb_owner`) es miembro de `neon_superuser`,
  el grupo con el que Neon da permisos a los usuarios del proyecto. Su documentación dice que sus roles solo pueden
  instalar las extensiones que Neon soporta, y `unaccent` es una; no dice explícitamente qué rol puede. Si no
  pudiera, la migración fallaría y se desharía entera (ver "Todo o nada"). **Confirmado en la fase 8:** `unaccent` es una
  extensión de confianza, así que la puede instalar quien tenga permiso de crear en la base, y ese rol la instaló.
- **Una base vacía, sin `database.sql`, no se puede migrar:** la primera migración que toca una tabla falla porque no existen, y se
  deshace todo (probado en la copia local, con el borrador como una sola versión). Copiar `database.sql` como versión 1 lo resolvería, pero la copia local seguiría otro camino que
  Neon.

**Todo o nada.** Postgres deshace entera una migración que falla. Se probó con una regla que los datos rompen: no
quedó ni la columna que la migración había agregado antes ni una entrada en el historial. Pero con varias migraciones
pendientes, Flyway las aplica de a una: si falla la cuarta, la segunda y la tercera quedan aplicadas. Con `group`, todas las
pendientes van en una sola transacción, así que Neon recibiría todos los cambios o ninguno. Las dos cosas se probaron
en la copia local. Se activa.

**La API migra sola solo cuando se le pide.** Spring Boot prende Flyway por defecto; acá se apaga
(`spring.flyway.enabled: false`), y lo prenden el `docker compose` de la copia local y los tests. Si alguien apunta la API a Neon, no lo migra por
accidente: las migraciones se le aplicaron a propósito, una vez, en la fase 8, como un pase a producción, con el
mismo Flyway y las mismas migraciones. Antes se verificó en solo lectura que Neon seguía igual a `database.sql`, como
en el análisis. La alternativa, migrar siempre al arrancar, es lo más común y lo más simple, pero convierte "probar la
API contra Neon" en "migrar Neon".

```yaml
spring:
  flyway:
    enabled: false                # el compose de la copia local y los tests lo prenden
    baseline-on-migrate: true
    baseline-version: 1
    baseline-description: database.sql de Cocos
    group: true                   # todas las migraciones pendientes en una sola transacción
```

**El pase a Neon (fase 8, 2026-11-09).** El enunciado da libertad para "modificar, agregar o ajustar tablas" si se
justifica, y la base que se entrega a cada candidato es propia. Se aplicó con `scripts/run-on-neon.sh --migrate`, probado
antes sobre un Postgres local cargado solo con `database.sql`, incluida una segunda corrida, que no hace nada. Las
migraciones van por la conexión directa, porque Neon pide no hacer cambios de estructura por su pooler; la API, por la URL
tal como viene. Sin `--migrate`, el script solo apunta la API a Neon. El detalle y la salida están en
[`mediciones/08-migracion-neon.md`](mediciones/08-migracion-neon.md).

**Consecuencias**

- Neon recibe una tabla más, `flyway_schema_history`.
- Con 11 órdenes, los cambios tardan milisegundos. En una tabla grande habría que planificarlos aparte: cambiar el
  tipo de `datetime` reescribe la tabla y la bloquea mientras dura, y un índice se crearía con
  `CREATE INDEX CONCURRENTLY`, que no puede ir dentro de una transacción.
- En producción, las migraciones las corre un usuario de la base con permiso para cambiar tablas, y la API usa otro
  que solo lee y escribe datos. Acá hay un solo usuario, el de la URL (sección 9).

## 4. Cálculos y consultas

### 4.1 La cuenta del usuario: la base trae las órdenes y Java calcula

Una sola consulta trae las órdenes `FILLED` y `NEW` del usuario, en orden cronológico (por fecha, con desempate por
id: D-10), con lo que los cálculos necesitan: activo, lado, estado, cantidad y precio. Es la consulta medida en la
sección 3.5: 1,2 ms para el usuario pesado. Con esas filas, `account`, en Java puro, calcula en una sola pasada:

- **los pesos de la cuenta,** con las órdenes `FILLED`: depósitos y ventas suman, retiros y compras restan, todo como
  `size × price` (POR-1, D-9);
- **las reservas:** las compras pendientes reservan pesos, y las ventas pendientes, acciones (A-1);
- **lo disponible:** pesos menos reservas, y acciones menos ventas pendientes;
- **las posiciones:** compras menos ventas ejecutadas, por activo;
- **el PPC, en orden:** cada compra lo actualiza y las ventas no lo cambian. Con la posición en 0 o negativa queda
  vacío, y cada compra hecha así lo vuelve a arrancar desde su precio: se muestra recién cuando la posición vuelve a
  ser positiva (A-2, POR-5).

Con los últimos precios (4.2) salen el valor de cada posición, el rendimiento, la variación diaria y el valor total.
El portfolio y el envío usan el mismo cálculo (principio 4); el envío lo corre dentro del turno del usuario
(sección 5).

**Por qué no en SQL.** Las sumas serían fáciles en SQL, pero el PPC depende del orden de las órdenes y se reinicia:
en SQL necesita funciones de ventana en varios pasos, difíciles de leer y de probar caso por caso. Partir el cálculo,
con las sumas en SQL y el PPC en Java, pondría la cuenta en dos lugares, y la parte en SQL solo se podría probar
contra una base. El ADR 0001 ya puso los cálculos en Java puro.

**El límite.** Java recibe todas las órdenes ejecutadas y pendientes del usuario: 8.597 en el pesado, unas 850.000 en
el extremo, que es el límite ya documentado en A-4 ([ADR 0003](adr/0003-posiciones-calculadas.md)). El tiempo de punta a punta se mide en la fase 6, contra el
objetivo de la sección 8.

**Necesita todo el historial.** El cálculo parte de la primera orden del usuario: si se borraran o archivaran órdenes
viejas, los pesos y las posiciones darían mal, sin ningún error. Hoy la API no borra órdenes. Antes de hacerlo habría
que guardar una foto a una fecha. También se volvió a evaluar una tabla de posiciones, y el
[ADR 0003](adr/0003-posiciones-calculadas.md) explica por qué se descartó.

### 4.2 El último precio, acción por acción

```sql
select i.id, m.close, m.previousclose
from unnest(:instrumentIds) as i(id)
cross join lateral (
  select close, previousclose from marketdata
  where instrumentid = i.id
  order by date desc
  limit 1
) m
```

Por cada acción, el índice de la regla "una fila por acción y fecha" va directo a la fecha más reciente. Medido con
10 años de precios (164.430 filas), para 20 acciones
([`mediciones/04-resultados.md`](mediciones/04-resultados.md)):

| Forma de la consulta | Tiempo |
|---|---|
| Acción por acción, como arriba | **0,05 ms** |
| `DISTINCT ON` solo de esas 20 acciones | 26 ms: lee los 10 años de cada una |
| `DISTINCT ON` sobre toda la tabla, como en el anexo del análisis | 80 ms |

La usan el portfolio (los activos con posición), la búsqueda (los resultados) y el envío (un activo), y vive en
`instruments`. Una acción sin filas en `marketdata` no tiene cotización (D-2, D-3): la consulta no devuelve nada para
ella. El envío la graba `REJECTED` (`NO_MARKET_PRICE`), salvo una orden por monto a mercado, que da error 400
porque no hay precio para calcular la cantidad (ENV-6). La búsqueda la muestra con el precio vacío.

### 4.3 La búsqueda

Una consulta, con las reglas de A-5:

- **El texto y los datos se normalizan igual, en la base:** `lower(unaccent(...))` de los dos lados.
- **Cada palabra tiene que aparecer** en el ticker o en el nombre, en cualquier orden.
- **Un texto que queda vacío al normalizarlo,** por ejemplo uno hecho solo de marcas de acento, pasa la validación y no
  encuentra nada: la lista viene vacía (encontrado en la revisión final, fase 8).
- **Sin comodines:** se busca con `strpos` y `starts_with` en lugar de `LIKE`, así `%` y `_` son texto común sin
  tener que escaparlos (A-5, decisión 10).
- **El orden:** ticker igual, ticker que empieza así, ticker que lo contiene, nombre que empieza así y el resto. Los
  empates van por ticker con `COLLATE "C"`, para que Neon y la copia local den el mismo orden (E-7).
- **Afuera la moneda** (ARS), y como mucho el tope de resultados (BUS-5). El largo del texto se valida antes de
  llegar a la base (BUS-6, sección 6). También antes de la base: sin el parámetro `query`, o con un carácter nulo
  (que Postgres no acepta en un texto), la respuesta es 400 en `query`, igual que con un texto vacío. Precisado al
  implementar (fase 5).
- **Cada resultado lleva su último precio** y su variación diaria (4.2).

Se midió con la versión de verificación del análisis, que ordena con `LIKE`: 0,06 ms con los 65 activos y entre 11 y
17 ms con 10.000 (sección 3.5). Cambiar `LIKE` por `starts_with` y `strpos` no cambia cuántas filas lee: sin índice,
las dos recorren todo el catálogo.

### 4.4 El listado

```sql
select ... from orders
where userid = :userId
  and status = :status                      -- solo si se filtra por estado
  and (datetime, id) < (:cursorDatetime, :cursorId)   -- solo desde la segunda página
order by datetime desc, id desc
limit :pageSize + 1
```

- Usa el índice de la sección 3.5. Se pide una orden más que el tamaño de página: si llega, hay página siguiente
  (A-7).
- **El cursor** es la fecha, con sus microsegundos, y el id de la última orden de la página, codificados en base64url
  (un texto opaco para el cliente). Uno que no se puede leer da 400 (LIS-6). También uno con una fecha fuera de los
  años 1 a 9999: un cursor válido sale siempre de una orden grabada, y Postgres no acepta todas las fechas que se
  pueden escribir. Un cursor vacío (`cursor=`) cuenta como ausente, igual que `limit=` y `status=`. Precisado al
  implementar (fase 5).
- Los depósitos y retiros se traducen a monto en pesos en Java (LIS-5, D-9).

### 4.5 Precisión y redondeo (GEN-7)

- **`BigDecimal` siempre,** nunca `double` (`CLAUDE.md`).
- **Lo exacto no se redondea:** pesos, reservas, valores y montos son sumas y productos de precios con 2 decimales
  por cantidades enteras, así que dan resultados exactos, con 2 decimales.
- **La cantidad por monto se corta hacia abajo** (`RoundingMode.DOWN`): monto ÷ precio, sin fracciones (A-6).
- **El PPC, el rendimiento y la variación diaria** son divisiones. Se calculan con 34 dígitos (`DECIMAL128`, que
  redondea en el dígito 34 con `HALF_EVEN`) y se redondean a 2 decimales una sola vez, al final, con `HALF_UP`: la
  mitad redondea hacia arriba (100,005 → 100,01). El rendimiento usa el PPC sin redondear a 2 decimales.
- **Por qué `HALF_UP` y no `HALF_EVEN`** (el "redondeo del banquero", que en la mitad va al par: 100,005 → 100,00).
  `HALF_EVEN` evita un sesgo cuando se suman muchos números ya redondeados. Acá solo se redondea el número final que
  se muestra, y nunca se suma un número redondeado. `HALF_UP` es además lo que cualquiera calcula a mano. Verificado:
  los números del PRD que salen de una división (los PPC, rendimientos y variaciones diarias de los casos 2 a 7
  de POR) dan igual con los dos modos, porque ninguno cae justo en la mitad.
- **Lo que manda el usuario nunca se redondea:** un precio o un monto con más de 2 decimales da 400 (D-4).

## 5. Órdenes simultáneas y reintentos

Las pruebas de esta sección corren en la copia local con [`mediciones/05-turno.sh`](mediciones/05-turno.sh), que
simula en SQL lo que hace la API: dos pedidos casi a la vez, que leen la cuenta, esperan un segundo y graban. La
salida está en [`mediciones/05-resultados.md`](mediciones/05-resultados.md).

### 5.1 Los estados de una orden

<!-- --8<-- [start:estados] -->
```mermaid
stateDiagram-v2
    [*] --> FILLED: a mercado, si alcanza
    [*] --> NEW: límite, si alcanza
    [*] --> REJECTED: si no alcanza o no cotiza
    NEW --> CANCELLED: el usuario la cancela
    NEW --> FILLED: el mercado llega al precio (no se simula)
    FILLED --> [*]
    REJECTED --> [*]
    CANCELLED --> [*]
```
<!-- --8<-- [end:estados] -->

Solo una orden `NEW` cambia de estado; las otras tres son finales (D-7). Nuestro sistema nunca pasa una `NEW` a
`FILLED`: si un proceso externo lo hiciera, el portfolio la contaría bien, porque cuenta por estado.

### 5.2 El turno del usuario

La transacción del envío empieza tomando el turno del usuario, con un lock sobre su fila de `users` (un bloqueo pesimista)
([ADR 0004](adr/0004-turno-del-usuario.md)):

```sql
select id from users where id = :userId for update
```

La segunda orden del mismo usuario espera en esa línea hasta que la primera confirma. Recién ahí lee la cuenta, y ya
ve la primera orden. Las órdenes de usuarios distintos no se esperan, porque son filas distintas. Dos ventas de 15
BMA a la vez, teniendo 20 (el caso 1 de GEN):

| Prueba | Venta 1 | Venta 2 | Posición final |
|---|---|---|---|
| A. Sin turno | `FILLED` (vio 20) | `FILLED` (vio 20) | **−10**: el mismo resultado que la orden 11 |
| B. Con turno | `FILLED` (vio 20) | `REJECTED` (vio 5) | **5**, lo correcto |
| C. Con turno, en `REPEATABLE READ` | `FILLED` (vio 20) | `FILLED` (vio 20) | **−10** |
| G. Sin turno, en un solo comando (`insert ... select`) | `FILLED` (vio 20) | `FILLED` (vio 20) | **−10** |

<!-- --8<-- [start:turno] -->
```mermaid
sequenceDiagram
    participant V1 as Venta 1 (15 BMA)
    participant DB as Postgres
    participant V2 as Venta 2 (15 BMA)
    V1->>DB: toma el turno del usuario
    V2->>DB: pide el turno y espera
    V1->>DB: lee la cuenta: 20 BMA
    V1->>DB: graba FILLED y confirma
    DB-->>V2: turno concedido
    V2->>DB: lee la cuenta: 5 BMA
    V2->>DB: graba REJECTED y confirma
```
<!-- --8<-- [end:turno] -->

**La trampa de la prueba C.** El turno funciona porque Postgres, en su nivel de aislamiento por defecto
(`READ COMMITTED`), hace que cada consulta vea lo confirmado antes de que empiece. En `REPEATABLE READ`, la
transacción conserva la foto de la base de su primera consulta: la segunda venta espera su turno, pero después sigue
sin ver la primera, y vende lo mismo. Por eso la transacción del envío se queda en `READ COMMITTED`, y el caso 1 de
GEN se prueba con dos órdenes simultáneas de verdad (sección 10).

**Lo que da el turno, además:**

- **Verifica que el usuario exista:** si la consulta no devuelve fila, es un 404 (está en la dirección del pedido).
- **No bloquea las lecturas:** portfolio, búsqueda y listado no lo toman, y no esperan.
- **No puede trabar dos órdenes entre sí:** cada envío bloquea una sola fila, la de su usuario, y es lo primero que
  hace.
- **Ordena las fechas:** la hora de la orden la pone la base al grabar (sección 3.4), dentro del turno.

**Espera máxima.** El turno dura milisegundos. Si un envío espera más de 2 segundos (`lock_timeout`, configurable),
la API responde 503 con "intentá de nuevo" (sección 7). Reintentar con el mismo identificador es seguro (5.3).

**Alternativas evaluadas**

| Opción | Por qué no |
|---|---|
| Un lock de Postgres sin fila (`pg_advisory_xact_lock`) | hace lo mismo, pero no verifica que el usuario exista, y sus números se comparten con cualquier otro uso de esos locks en la base |
| Un bloqueo optimista: nivel `SERIALIZABLE`, o una columna `version` en `users` que se revisa al grabar | si otra orden se adelantó, la transacción se deshace: la API tendría que reintentar la orden entera, con más código y una orden evaluada dos veces. La columna, además, cambia una tabla de Cocos |
| Un lock en Java | solo funciona con una instancia de la API |
| Un `UPDATE` condicionado sobre una tabla de saldos y posiciones, o la regla "cantidad ≥ 0" | es lo más simple cuando la regla mira una sola fila, y así se cancela (5.4). Acá no hay una fila con el número: la tenencia y los pesos son la suma de las órdenes. La tabla se descartó en A-4 y D-1: BMA ya está en −10 |
| Un solo comando que lee la cuenta y graba (`insert ... select`), sin turno | graba todo o nada, pero no pone los pedidos en fila: dos a la vez ven la misma cuenta, y la posición queda en −10 (prueba G) |

### 5.3 Reintentos

Dentro del turno, antes de evaluar la orden (R-2, ENV-9, [ADR 0005](adr/0005-reintentos-con-identificador-y-huella.md)):

1. Se busca una orden del usuario con ese identificador.
2. **Si no existe,** se evalúa y se graba (A-6).
3. **Si existe con la misma huella,** se devuelve tal cual, sin volver a evaluarla, aunque haya quedado `REJECTED`.
   Responde 200, no 201, porque no se creó nada (sección 6).
4. **Si existe con otra huella,** es otra intención con un identificador repetido: 422, y no se graba nada.

Un pedido que da error (400) no graba nada, así que no gasta el identificador.

<!-- --8<-- [start:reintento] -->
```mermaid
sequenceDiagram
    participant App
    participant API
    participant DB as Postgres
    App->>API: envía la orden con el identificador A
    API->>DB: toma el turno y busca A: no existe
    API->>DB: evalúa y graba la orden 12 (FILLED)
    API--xApp: la respuesta se pierde
    App->>API: reintenta: mismo identificador, mismo contenido
    API->>DB: toma el turno y busca A: existe, con la misma huella
    API-->>App: 200, la orden 12 tal cual
```
<!-- --8<-- [end:reintento] -->

**Dos pedidos simultáneos con el mismo identificador** (el caso 20 del envío): el segundo espera su turno y encuentra
la orden del primero (prueba E: una sola orden). Sin el turno, lo frena la regla de unicidad (prueba D: error de
clave duplicada, una sola orden). Esa regla queda como red: si la API alguna vez recibe ese error, deshace la transacción
(Postgres no acepta más comandos en una transacción que tuvo un error) y vuelve a empezar desde el paso 1, como un
reintento.

**Con el turno tomado, nadie más puede grabar una orden de ese usuario** (encontrado al implementar, fase 5). El chequeo de
la clave foránea de `orders` hacia `users` pide un lock `FOR KEY SHARE` sobre la fila del usuario, que choca con el
`FOR UPDATE` del turno. Así, todo `insert` en `orders` de ese usuario espera a que termine la orden en curso, venga de
la API o de otro sistema. La red de unicidad solo se alcanza por un camino que no tome el turno, y el test la prueba
así: apaga el turno con un espía y verifica el choque y el reintento contra Postgres. Se evaluó un lock más liviano,
`FOR NO KEY UPDATE`, que no frenaría esos `insert`. Se mantiene `FOR UPDATE` (decisión del autor, 2026-11-08): la API no
tiene otro camino que grabe órdenes, la espera sería de milisegundos, y el lock fuerte también pone en fila a un camino
futuro que se olvide de tomar el turno.

**La huella** es el SHA-256 de una forma fija del pedido: `v1`, el activo, el lado, el tipo, la cantidad, el monto y el
precio límite, separados por `|`. Lo que no viene queda vacío, y los números se escriben sin ceros de más, así
`10000` y `10000.00` dan la misma huella:

| Pedido | Forma fija | Huella |
|---|---|---|
| $10.000 en PAMP a mercado | `v1\|47\|BUY\|MARKET\|\|10000\|` | `7fab9319…c1a727` |
| el mismo, con `10000.00` | `v1\|47\|BUY\|MARKET\|\|10000\|` | `7fab9319…c1a727` |
| $20.000 en PAMP a mercado | `v1\|47\|BUY\|MARKET\|\|20000\|` | `5877f5a9…475a0c` |

No entran el usuario, porque el identificador ya es por usuario, ni el identificador mismo. El `v1` permite cambiar
la forma más adelante sin confundir huellas viejas con nuevas.

### 5.4 La cancelación, sin turno

```sql
update orders set status = 'CANCELLED'
where id = :orderId and userid = :userId and status = 'NEW'
returning ...
```

- **Si no cambió ninguna fila,** se lee la orden: si no existe o es de otro usuario, 404 (CAN-4); si existe, 409 con
  su estado actual (CAN-3).
- **Dos cancelaciones a la vez:** la segunda espera a que la primera confirme, y cuando vuelve a mirar la orden ya no
  está `NEW`: no cambia ninguna fila y recibe 409 (prueba F, CAN-5).
- **Por qué no toma el turno:** cancelar solo libera reservas. Si una orden se evalúa mientras otra se cancela, ve la
  reserva todavía activa: puede rechazar de más, nunca gastar dos veces. Y cancelar bloquea la fila de la orden, no la
  del usuario, así que no espera a los envíos ni los traba.

### 5.5 La misma orden con otro identificador

El identificador protege un reintento solo si la app lo repite (5.3). Si la misma orden llega con un identificador
nuevo, para la API son dos pedidos distintos y los dos válidos. Una segunda red frena el caso más común, el duplicado
que llega enseguida (R-6, ENV-10, [ADR 0009](adr/0009-ordenes-iguales-con-otro-identificador.md)).

Dentro del turno, después de evaluar la orden y solo si iba a quedar `FILLED` o `NEW`, se busca otra orden del mismo
usuario con la misma huella, también `FILLED` o `NEW`, grabada hace menos de 5 segundos:

```sql
select id from orders
where userid = :userId
  and datetime > statement_timestamp() - :ventana
  and requestfingerprint = :huella
  and clientorderid <> :clientOrderId
  and status in ('FILLED', 'NEW')
order by datetime desc, id desc
limit 1
```

- **Si existe,** la API responde 409 con el número de esa orden y no graba nada (sección 7.3).
- **Si no existe,** graba la orden.

<!-- --8<-- [start:duplicado] -->
```mermaid
sequenceDiagram
    participant App
    participant API
    participant DB as Postgres
    App->>API: compra 10 PAMP, con el identificador A
    API->>DB: toma el turno, evalúa y graba la orden 12 (FILLED)
    API-->>App: 201, la orden 12
    App->>API: a los 2 segundos, la misma compra con el identificador B
    API->>DB: toma el turno y busca B: no existe
    API->>DB: evalúa: quedaría FILLED
    API->>DB: busca la misma huella, aceptada hace menos de 5 segundos: la orden 12
    API-->>App: 409, posible duplicado de la orden 12
```
<!-- --8<-- [end:duplicado] -->

| Caso | Resultado |
|---|---|
| La misma orden, con otro identificador, dentro de los 5 segundos | 409, y no se graba |
| La misma, pasados los 5 segundos | se acepta: se toma como una decisión nueva |
| La misma, con el mismo identificador | 200, con la orden original (5.3) |
| La primera quedó `REJECTED`, o era una límite y se canceló | la segunda se evalúa normalmente |
| La segunda igual iba a quedar `REJECTED` | se graba `REJECTED`, como siempre |
| Otro usuario manda la misma orden | se acepta: el control es por usuario |

- **Por qué se mira después de evaluar.** Una orden que igual iba a quedar `REJECTED` no hace daño, y el enunciado
  pide grabar los rechazos. Así el caso 2 de GEN no cambia: dos compras iguales de $600.000 que llegan juntas dejan una
  `FILLED` y una `REJECTED`, no un posible duplicado.
- **Por qué no hay carrera.** Las dos órdenes son del mismo usuario, así que pasan por el turno de a una (5.2): la
  segunda siempre ve a la primera, ya confirmada. Con un cache en memoria habría que resolver eso a mano, y con dos
  instancias de la API no funcionaría.
- **Un solo reloj.** La hora de la orden anterior la puso la base al grabarla (sección 3.4), y "ahora" es
  `statement_timestamp()`, el momento en que arranca la consulta: no importa si los relojes de dos instancias de la API
  difieren.
- **Por qué `statement_timestamp()` y no `clock_timestamp()`** (encontrado al medir, fase 8). `clock_timestamp()` es
  volátil: puede cambiar en cada fila, así que Postgres no la usa como límite del recorrido del índice. La primera
  versión buscaba por usuario y descartaba después, una por una, las 10.000 órdenes del usuario pesado, y el percentil
  95 de su envío subió de unos 57 ms a 72,9 ms. `statement_timestamp()` es estable durante la consulta, y el índice
  recorre solo la ventana ([`mediciones/05-duplicado-resultados.md`](mediciones/05-duplicado-resultados.md)).
- **Qué es "igual".** La misma huella (5.3): el activo, el lado, el tipo, la cantidad o el monto y el precio límite, tal
  como se mandaron. "10 PAMP" y "$9.258,50 de PAMP" son dos pedidos distintos. Las órdenes viejas no tienen huella, así
  que nunca cuentan como la primera.
- **Por qué `clientorderid <> :clientOrderId`** (encontrado al implementar, fase 8). Una orden con el mismo
  identificador no es otra orden: es un reintento, y lo contestan la búsqueda por identificador o la regla de unicidad
  (5.3). Dentro del turno nunca aparece, porque la búsqueda por identificador va antes. En el camino sin turno que
  cubre la regla de unicidad sí: sin esa condición, el test de ese camino recibía un 409 en vez de reintentar.
- **Cuánto cuesta.** Una consulta más por orden aceptada, que usa el índice por (usuario, fecha, id) de la sección 3.5
  y lee solo las órdenes de ese usuario de los últimos 5 segundos: 3 páginas y 0,011 ms en la base para el usuario de
  10.000 órdenes (medido).
- **Lo que no frena.** Un reintento con otro identificador que llega pasados los 5 segundos: la API no lo puede
  distinguir de una orden nueva. Contra eso la app tiene que repetir el identificador (5.3). Y si la app, ante el 409,
  espera y reenvía sola, el duplicado entra igual: tiene que mostrarle el aviso a la persona.

**Alternativas evaluadas**

| Opción | Por qué no |
|---|---|
| Un cache en la memoria de la API | cada instancia tiene su memoria: con dos, el segundo pedido puede caer en la que no vio el primero |
| Un almacén compartido con vencimiento, como Redis | una pieza más para levantar y operar, separada de la transacción que graba la orden |
| Mirar antes de evaluar | cambia el caso 2 de GEN, y frena órdenes que igual iban a quedar rechazadas |
| Aceptar la segunda si trae una confirmación explícita | cambia el contrato del envío y necesita su pantalla en la app: queda como evolución |
| Una ventana de 2 segundos | deja pasar un reintento automático con esperas de 1 y 2 segundos, y a la persona que toca de nuevo porque la app tarda |
| Una ventana de 30 o 120 segundos, como en los pagos | frena a quien repite una compra a propósito |
| Responder 429 | es el código de los límites de frecuencia, y muchos clientes lo reintentan solos |

## 6. Contratos de la API

### 6.1 Las direcciones

| Función | Pedido | Respuesta |
|---|---|---|
| Portfolio | `GET /users/{userId}/portfolio` | 200 |
| Búsqueda | `GET /instruments?query={texto}` | 200, también sin resultados |
| Envío | `POST /users/{userId}/orders` | 201 si crea la orden, aunque quede `REJECTED`; 200 si es un reintento |
| Listado | `GET /users/{userId}/orders?status={estado}&limit={n}&cursor={marca}` | 200 |
| Cancelación | `POST /users/{userId}/orders/{orderId}/cancel` | 200, con la orden ya `CANCELLED` |

- **El usuario va en la dirección** porque no hay autenticación (S-5). En producción saldría de la sesión, y la
  dirección perdería el `{userId}`. Lo que no existe en la dirección da 404, y en los datos, 400 (A-6, decisión 4).
- **Cancelar es un `POST` a `/cancel`,** no un `DELETE`. Algunas APIs de brokers cancelan con `DELETE`, pero la orden
  no se borra: sigue en el listado, cancelada. Y un `PATCH` del estado invitaría a pedir cualquier estado, cuando lo
  único permitido es cancelar.
- **Sin versión en la dirección** (`/v1`): hay una sola versión y un solo cliente. Si el contrato cambiara de una
  forma que rompe a los clientes, se agrega entonces.

### 6.2 Pedidos y respuestas, con el usuario 1

Cada ejemplo parte de los datos originales (sección 10.2), así que la orden nueva siempre es la 12.

**Portfolio** (casos 1 y 2 de POR). Las posiciones van ordenadas por ticker.

```http
GET /users/1/portfolio
```

```json
{
  "userId": 1,
  "totalValue": 889756.00,
  "cash": { "balance": 753000.00, "reserved": 125500.00, "available": 627500.00 },
  "positions": [
    { "instrumentId": 31, "ticker": "BMA", "name": "Banco Macro S.A.",
      "quantity": -10, "reservedQuantity": 0, "lastPrice": 1502.80, "marketValue": -15028.00,
      "averagePrice": null, "totalReturnPercent": null, "dailyChangePercent": -1.15 },
    { "instrumentId": 54, "ticker": "METR", "name": "MetroGAS S.A.",
      "quantity": 500, "reservedQuantity": 0, "lastPrice": 229.50, "marketValue": 114750.00,
      "averagePrice": 250.00, "totalReturnPercent": -8.20, "dailyChangePercent": -1.08 },
    { "instrumentId": 47, "ticker": "PAMP", "name": "Pampa Holding S.A.",
      "quantity": 40, "reservedQuantity": 0, "lastPrice": 925.85, "marketValue": 37034.00,
      "averagePrice": 930.00, "totalReturnPercent": -0.45, "dailyChangePercent": 0.44 }
  ]
}
```

**De dónde sale cada número.** Ninguno está guardado: en cada pedido se calculan desde `orders` y `marketdata`
(sección 4.1). Un campo "se copia" cuando es el valor de una columna tal cual, y "se calcula" cuando sale de una
cuenta.

La plata:

| Campo | Valor | Cómo se calcula |
|---|---|---|
| `cash.balance` | 753.000 | órdenes `FILLED`: depósitos 1.000.000 − retiros 100.000 − compras 202.300 + ventas 55.300 |
| `cash.reserved` | 125.500 | compras `NEW`: LOMA 50 × 710 = 35.500, más BMA 60 × 1.500 = 90.000 |
| `cash.available` | 627.500 | `balance` − `reserved` = 753.000 − 125.500 |
| `totalValue` | 889.756 | `balance` + el `marketValue` de cada posición = 753.000 + 37.034 + 114.750 − 15.028 |

- **El total usa `balance`, no `available`:** lo reservado sigue siendo del usuario (A-1).
- **Las compras pendientes no se suman aparte:** su plata ya está dentro del `balance`. LOMA no aparece como posición
  porque todavía no tiene acciones.

Las tres posiciones:

| Posición | `quantity` | `lastPrice` | `marketValue` | `averagePrice` | `totalReturnPercent` |
|---|---|---|---|---|---|
| BMA | 20 − 30 = −10 | 1.502,80 | −15.028 | `null` | `null` |
| METR | 500 | 229,50 | 114.750 | 125.000 ÷ 500 = 250 | −8,20 |
| PAMP | 50 − 10 = 40 | 925,85 | 37.034 | 46.500 ÷ 50 = 930 | −0,45 |

BMA vendió más de lo que tenía (D-1): sin acciones, no hay contra qué medir el rendimiento, y el PPC queda vacío.

Cada campo, con PAMP:

| Campo | PAMP | Se copia o se calcula | Columnas que usa |
|---|---|---|---|
| `ticker`, `name` | PAMP, Pampa Holding S.A. | se copia | `instruments.ticker`, `instruments.name` |
| `quantity` | 40 | se calcula: compras − ventas `FILLED` = 50 − 10 | `orders.size` |
| `reservedQuantity` | 0 | se calcula: suma de las ventas `NEW`, que no hay | `orders.size` |
| `lastPrice` | 925,85 | se copia: es el `close` del último día | `marketdata.close` |
| `marketValue` | 37.034 | se calcula: `quantity` × `lastPrice` = 40 × 925,85 | `orders.size`, `marketdata.close` |
| `averagePrice` (PPC) | 930 | se calcula: monto de las compras ÷ acciones compradas = 46.500 ÷ 50 | `orders.size`, `orders.price` |
| `totalReturnPercent` | −0,45 | se calcula: (`lastPrice` − PPC) ÷ PPC × 100 = (925,85 − 930) ÷ 930 × 100 = −0,4462… | `marketdata.close`, y por el PPC `orders.size` y `orders.price` |
| `dailyChangePercent` | 0,44 | se calcula: (`lastPrice` − `previousClose`) ÷ `previousClose` × 100 = (925,85 − 921,80) ÷ 921,80 × 100 = 0,4393… | `marketdata.close`, `marketdata.previousClose` |

**Nota sobre `reservedQuantity`.** Solo se carga cuando el usuario tiene una venta pendiente (`SELL` en `NEW`) de un
activo que tiene: son las acciones que esa venta aparta. Las compras pendientes apartan pesos y van a `cash.reserved`.
Por eso da 0 en las tres posiciones: el usuario 1 solo tiene compras pendientes, y BMA, con una de 60 acciones, también
da 0. Con una venta LIMIT de 15 PAMP, PAMP daría `quantity` 40 y `reservedQuantity` 15.

Los porcentajes se redondean a 2 decimales una sola vez, al final: −0,4462… da −0,45 (sección 4.5).

Las órdenes de PAMP, de donde salen `quantity` y el PPC:

| Orden | Lado | Estado | `size` | `price` | Monto (`size × price`) | Qué hace |
|---|---|---|---|---|---|---|
| 2 | `BUY` | `FILLED` | 50 | 930 | 46.500 | suma 50 acciones y entra en el PPC |
| 3 | `BUY` | `CANCELLED` | 50 | 920 | 46.000 | no cuenta |
| 4 | `SELL` | `FILLED` | 10 | 940 | 9.400 | resta 10 acciones y suma $9.400 a los pesos; el PPC no cambia |
| 6 | `SELL` | `REJECTED` | 100 | 950 | 95.000 | no cuenta |

Queda `quantity` = 50 − 10 = 40 y PPC = 46.500 ÷ 50 = 930. Si hubiera una venta antes de otra compra, el PPC se
calcula orden por orden (sección 4.1).

**Búsqueda** (caso 8 de BUS). Una acción que no cotiza, como PGR, viene con `lastPrice` y `dailyChangePercent` en
`null`.

```http
GET /instruments?query=pamp
```

```json
{
  "instruments": [
    { "instrumentId": 47, "ticker": "PAMP", "name": "Pampa Holding S.A.", "lastPrice": 925.85, "dailyChangePercent": 0.44 }
  ]
}
```

**Envío: comprar 10 PAMP a mercado** (caso 1 del envío).

```http
POST /users/1/orders
```

```json
{ "clientOrderId": "3f2b8c1e-9d4a-4c7b-8e21-5a6f0b9c2d13", "instrumentId": 47, "side": "BUY", "type": "MARKET", "quantity": 10 }
```

```json
{
  "id": 12,
  "clientOrderId": "3f2b8c1e-9d4a-4c7b-8e21-5a6f0b9c2d13",
  "instrumentId": 47,
  "ticker": "PAMP",
  "side": "BUY",
  "type": "MARKET",
  "quantity": 10,
  "price": 925.85,
  "amount": 9258.50,
  "status": "FILLED",
  "rejectionReason": null,
  "rejectionMessage": null,
  "datetime": "2026-11-08T11:02:15.483921-03:00"
}
```

**`amount` es siempre el monto en pesos de la orden,** cantidad × precio, como en `size × price` (D-9). Si el precio
está vacío, como en una `REJECTED` a mercado sobre PGR, `amount` también. En una orden por monto muestra lo que de verdad se usa: con $10.000 en PAMP salen 10 acciones y `amount` es 9258.50, así se ve que
sobran $741,50. En un depósito o un retiro, `amount` es lo transferido, y `quantity` y `price` van en `null`, para que
no aparezca "1.000.000 acciones a $1" (LIS-5).

**Envío por monto, con precio límite** (caso 5): `{ ..., "type": "LIMIT", "amount": 10000.00, "price": 900.00 }`.
Responde 201 con `"quantity": 11`, `"price": 900.00`, `"amount": 9900.00` y `"status": "NEW"`.

**Envío rechazado** (caso 7): $700.000 de PAMP a mercado. Responde 201, porque la orden existe, rechazada (A-6,
decisión 2):

```json
{
  "id": 12, "clientOrderId": "9b4e2f71-0c3d-4a8b-b5e6-7f1a2c3d4e5f", "instrumentId": 47, "ticker": "PAMP",
  "side": "BUY", "type": "MARKET", "quantity": 756, "price": 925.85, "amount": 699942.60,
  "status": "REJECTED", "rejectionReason": "INSUFFICIENT_CASH",
  "rejectionMessage": "No alcanzan los pesos disponibles para esta orden.",
  "datetime": "2026-11-08T11:03:40.120734-03:00"
}
```

**Reintento** (caso 15): el mismo pedido, con el mismo identificador, responde 200 con la orden original, en su estado
actual: una límite que después se canceló vuelve `CANCELLED`.

**Posible duplicado** (caso 21): la compra de 10 PAMP de arriba, mandada otra vez enseguida con otro identificador.
Responde 409 y no graba nada (sección 5.5):

```json
{
  "type": "/problems/possible-duplicate-order",
  "title": "Posible orden duplicada",
  "status": 409,
  "detail": "Acaba de entrar una orden igual, la 12. Si querés otra igual, esperá unos segundos y volvé a enviarla.",
  "instance": "/users/1/orders",
  "existingOrderId": 12,
  "requestId": "0f8d6c3e-2b1a-4e7d-9c5f-6a4b3d2e1f00"
}
```

**Listado** de las pendientes (caso 1 de LIS):

```http
GET /users/1/orders?status=NEW
```

```json
{
  "orders": [
    { "id": 7, "clientOrderId": null, "instrumentId": 31, "ticker": "BMA", "side": "BUY", "type": "LIMIT",
      "quantity": 60, "price": 1500.00, "amount": 90000.00, "status": "NEW",
      "rejectionReason": null, "rejectionMessage": null, "datetime": "2023-07-13T11:13:20-03:00" },
    { "id": 5, "clientOrderId": null, "instrumentId": 45, "ticker": "LOMA", "side": "BUY", "type": "LIMIT",
      "quantity": 50, "price": 710.00, "amount": 35500.00, "status": "NEW",
      "rejectionReason": null, "rejectionMessage": null, "datetime": "2023-07-12T15:14:20-03:00" }
  ],
  "nextCursor": null
}
```

Las órdenes viejas no tienen identificador ni motivo de rechazo: van en `null` (LIS-4). El depósito (orden 1) aparece
con `"quantity": null`, `"price": null` y `"amount": 1000000.00`.

**Cancelación** (caso 1 de CAN): `POST /users/1/orders/7/cancel` responde 200 con la orden 7 en `"status": "CANCELLED"`.

**Qué se valida en el envío** (ENV-1, ENV-7 y los límites de la sección 3.2). Si algo falla, 400 y no se graba nada:

| Campo | Regla |
|---|---|
| `clientOrderId` | obligatorio, un UUID |
| `instrumentId` | obligatorio. El activo tiene que existir y no ser ARS |
| `side` | `BUY` o `SELL`. `CASH_IN` y `CASH_OUT` dan 400 |
| `type` | `MARKET` o `LIMIT` |
| `quantity` o `amount` | exactamente uno de los dos. `quantity`, un entero de 1 a 2.147.483.647; `amount`, mayor a 0, con hasta 2 decimales y hasta 18 dígitos enteros |
| `price` | obligatorio en `LIMIT` y prohibido en `MARKET`. Mayor a 0, con hasta 2 decimales y hasta 99.999.999,99 |
| por monto | la cantidad calculada tiene que dar de 1 a 2.147.483.647, y una a mercado necesita que el activo cotice |
| cualquier otro campo | 400: un campo mal escrito, como `prise`, no se ignora en silencio |

### 6.3 Códigos HTTP

| Código | Cuándo |
|---|---|
| 200 | lecturas, cancelación, y un reintento que devuelve la orden original |
| 201 | el envío creó una orden: `FILLED`, `NEW` o `REJECTED` |
| 400 | el pedido no se puede armar: falta un dato, un valor no es válido, el activo no existe, el cursor no se puede leer |
| 404 | no existe lo que está en la dirección: el usuario, o la orden (o es de otro usuario) |
| 409 | se quiere cancelar una orden que ya no está `NEW` (CAN-3); o el envío es igual a otra orden recién aceptada del usuario, con otro identificador (sección 5.5) |
| 422 | el identificador ya se usó con otro contenido. Es el código que propone el borrador del IETF para `Idempotency-Key` (R-2) |
| 503 | el envío no consiguió el turno del usuario en 2 segundos: "intentá de nuevo" (sección 5.2) |
| 500 | un error inesperado, sin detalles internos en la respuesta |

Todos los errores tienen el mismo formato (sección 7).

### 6.4 Formatos

- **Fechas:** ISO 8601, en hora de Buenos Aires y con la zona escrita: `2023-07-12T12:31:20-03:00`. Las órdenes nuevas
  muestran sus microsegundos (`.483921`), tal como quedaron grabadas (sección 3.4).
- **Plata y precios:** números JSON con sus decimales (`925.85`, `889756.00`), que la API lee y escribe como
  `BigDecimal`. Un precio que llega como `229.555` se lee entero, sin redondear, y da 400.
- **Porcentajes:** números con 2 decimales, en puntos porcentuales: `-0.45` es −0,45 %. El nombre del campo lo dice
  (`totalReturnPercent`).
- **Estados, lados, tipos y motivos:** los textos del enunciado (`BUY`, `LIMIT`, `FILLED`) y los códigos de la sección
  3.3.
- **Lo vacío va en `null`,** y el campo aparece igual: el PPC vacío de BMA es `"averagePrice": null`. Así la forma de
  la respuesta no cambia según el caso.
- **Nombres de campos** en inglés, en camelCase, como el código (`CLAUDE.md`).

### 6.5 El identificador de la orden

Va en el cuerpo, como `clientOrderId`, y es un UUID (sección 3.3). No va en un encabezado `Idempotency-Key` (el
borrador del IETF), porque es un dato de la orden: se graba, vuelve en la respuesta y en el listado, y el cliente lo
usa para encontrar su orden si perdió la respuesta (R-2, decisión 4). Es el mismo lugar que le dan el `ClOrdID` de FIX
y el `client_order_id` de Coinbase.

Jackson también lee un UUID escrito en Base64, con 24 caracteres. Es el mismo identificador en otra forma: no cambia nada
y se deja así (encontrado en la revisión final, fase 8). Cualquier otro texto da 400.

### 6.6 Los números configurables

Van en `application.yml`, con `@ConfigurationProperties` (`CLAUDE.md`):

| Qué | Valor | Por qué |
|---|---|---|
| Tope de resultados de la búsqueda (BUS-5) | 20 | con "a" coinciden 59 de los 65 activos |
| Largo máximo del texto de búsqueda (BUS-6) | 50 caracteres | deja pasar el ticker más largo, un espacio y el nombre más largo (5 + 1 + 42 = 48) |
| Tamaño de página del listado, por defecto (LIS-3) | 20 | una pantalla de órdenes |
| Tamaño de página máximo (LIS-3) | 100 | un pedido más grande da 400 |
| Espera máxima del turno (sección 5.2) | 2 segundos | el turno dura milisegundos |
| Ventana del posible duplicado (sección 5.5) | 5 segundos | frena el doble clic y el reintento inmediato, y una persona casi no puede repetir una orden a propósito en menos tiempo. Con 0, el control se apaga |

La espera del turno tiene un mínimo de 1 ms, que se valida al arrancar: Postgres toma un `lock_timeout` de 0 como
"sin límite" (precisado al implementar, fase 5).

### 6.7 La especificación OpenAPI

Se publica, generada desde el código con springdoc-openapi: la especificación en `/v3/api-docs` y una página para
probar la API desde el navegador en `/swagger-ui.html`. Quien evalúa puede recorrer las 5 funciones sin instalar nada,
y la especificación no se desactualiza, porque sale del código. La versión 3.2.0 de springdoc está construida sobre
Spring Boot 4.1.2, la misma versión que usa la API.

- **En contra:** es una librería más, así que tiene su ADR ([0008](adr/0008-openapi-con-springdoc.md), como pide
  `CLAUDE.md`), y documentar los ejemplos agrega anotaciones en los controllers.
- **La alternativa,** solo la colección de Postman (ya decidida en el PRD), muestra el recorrido, pero no un contrato
  para consultar.

## 7. Errores y validación

### 7.1 El formato

Todos los errores usan Problem Details (RFC 9457), con `Content-Type: application/problem+json`. Spring lo trae, y se
prende con `spring.mvc.problemdetails.enabled: true` (por defecto viene apagado). A los campos estándar se suman
cuatro: `requestId`, el id del pedido que también está en los logs (sección 8); `errors`, la lista de campos con
problemas, en un `validation-error` que tiene un campo concreto (un parámetro de la dirección que no se puede leer no lo
tiene, y va sin `errors`); `currentStatus`, el estado actual de la orden, en el 409 de la cancelación; y
`existingOrderId`, el número de la orden igual que ya entró, en el 409 de un posible duplicado.

```json
{
  "type": "/problems/validation-error",
  "title": "El pedido no es válido",
  "status": 400,
  "detail": "Una orden LIMIT necesita precio límite.",
  "instance": "/users/1/orders",
  "requestId": "4b7e9c2a-1f3d-4e8a-9b6c-0d2e5f7a8c91",
  "errors": [ { "field": "price", "message": "Una orden LIMIT necesita precio límite." } ]
}
```

- **`type`** identifica el error y no cambia: el cliente decide con él, no con el texto. Es una dirección relativa
  (`/problems/...`): el RFC 9457 la permite, aunque recomienda una absoluta cuando se puede, y la API no tiene un
  dominio propio. Lo que significa cada una está en el catálogo de abajo.
- **`detail`** explica, en español, qué está mal y cómo arreglarlo (GEN-6).
- **Un 500 no cuenta nada interno:** ni la consulta, ni la clase, ni el stack trace. Solo el `requestId`, para
  buscarlo en los logs.

### 7.2 Dónde se valida

Siguen los dos tipos de error del recorrido de un pedido (sección 2, pasos 2 y 3):

- **El controller valida la forma** con Bean Validation: que estén los datos, con valores válidos y en rango, y que
  la combinación tenga sentido (sección 6.2). Si falla, `validation-error`, con un elemento en `errors` por campo.
- **El service valida contra los datos:** que el activo exista y no sea ARS, y que por monto se pueda calcular la
  cantidad. Si falla, `invalid-order`. Pasa antes de grabar, así que no se graba nada (A-6).

### 7.3 El catálogo

| Código | `type` | Cuándo | Ejemplo de `detail` |
|---|---|---|---|
| 400 | `/problems/validation-error` | falta un dato, un valor no es válido o la combinación no tiene sentido; también un texto de búsqueda vacío, largo o con un carácter nulo, un cursor que no se puede leer, un JSON mal armado, un campo desconocido o repetido, un número escrito como texto o en lugar del valor de un enum, o un parámetro de la dirección que no se puede leer | "Mandá la cantidad o el monto, no los dos." |
| 400 | `/problems/invalid-order` | el activo no existe o es ARS; por monto, la cantidad da 0 o no entra en `size`; por monto y a mercado, el activo no cotiza | "Con $500 no alcanza para una acción de PAMP ($925,85)." |
| 404 | `/problems/user-not-found` | el usuario de la dirección no existe | "No existe el usuario 99." |
| 404 | `/problems/order-not-found` | la orden no existe, o es de otro usuario: la respuesta es la misma, para no confirmar órdenes ajenas (CAN-4) | "No existe la orden 999." |
| 409 | `/problems/order-not-cancellable` | la orden ya no está `NEW`. Trae el estado actual en `currentStatus` (CAN-3) | "La orden 3 ya está CANCELLED: solo se cancela una orden NEW." |
| 409 | `/problems/possible-duplicate-order` | el envío iba a quedar aceptado y es igual a otra orden aceptada del usuario hace menos de 5 segundos, con otro identificador. Trae el número de esa orden en `existingOrderId` (R-6) | "Acaba de entrar una orden igual, la 12. Si querés otra igual, esperá unos segundos y volvé a enviarla." |
| 422 | `/problems/client-order-id-reused` | el identificador ya se usó con otro contenido (R-2) | "Ese identificador ya se usó en otra orden. Para una orden nueva, generá uno nuevo." |
| 503 | `/problems/user-busy` | el envío no consiguió el turno del usuario en 2 segundos. Trae el encabezado `Retry-After: 1` | "Tu cuenta está procesando otra orden. Intentá de nuevo." |
| 500 | `/problems/internal-error` | cualquier error inesperado | "Ocurrió un error inesperado." |
| 404, 405, 406 y 415 | `about:blank` | los errores de HTTP que resuelve Spring antes de llegar a la API: una dirección que no existe, un método no permitido, un formato de respuesta que no puede dar o uno de pedido que no acepta | lo arma Spring, en inglés y sin el campo `type` (el RFC 9457 dice que, si falta, vale `about:blank`); la API le suma el `requestId` |

Un error que no pasa por Spring MVC, por ejemplo dentro de un filtro, sale por la página de error de Spring Boot, con
otro formato. Por eso el único filtro, el que pone el id del pedido, no hace nada que pueda fallar.

### 7.4 Un `REJECTED` no es un error

Una orden rechazada existe: se grabó, rechazada, y responde 201 con la orden, igual que una `FILLED` o una `NEW` (A-6,
decisión 2). El motivo viaja en la orden, con el código de la sección 3.3 y un texto:

| `rejectionReason` | `rejectionMessage` |
|---|---|
| `INSUFFICIENT_CASH` | "No alcanzan los pesos disponibles para esta orden." |
| `INSUFFICIENT_SHARES` | "No alcanzan las acciones disponibles para esta venta." |
| `NO_MARKET_PRICE` | "El activo no cotiza." |

La diferencia importa para el cliente: un error dice "corregí el pedido"; un rechazo dice "el pedido estaba bien, pero
tu cuenta o el mercado no lo dejaron".

## 8. Observabilidad

### 8.1 Logs

- **En JSON, uno por línea,** con el formato ECS que trae Spring Boot (`logging.structured.format.console: ecs`). Se
  pueden filtrar por campo sin parsear texto.
- **Cada pedido tiene un id.** Si el cliente manda `X-Request-Id` con letras, números y guiones, y hasta 64
  caracteres, se usa ese, así un valor del cliente no puede romper los logs; si no, la API genera un UUID. Un filtro
  lo pone en el contexto de los logs (MDC), así sale en cada línea de ese pedido, y vuelve en el encabezado de la
  respuesta y en los errores (`requestId`, sección 7).
- **Qué se registra:** una línea por orden procesada, con el usuario, el número de orden, el identificador, el lado,
  el tipo, el estado y el motivo; las cancelaciones; los errores 4xx, sin stack trace; y los 5xx, con stack trace.
- **Cada error escribe una sola línea** (precisado al implementar, fase 6): `WARN` en un 4xx y `ERROR` en un 5xx,
  también el 503 de turno ocupado, con el `type` (`problemType`) y el código (`httpStatus`) como campos propios. En
  ECS el stack trace va adentro de esa línea, en `error.stack_trace`. El aviso que Spring escribe por su cuenta en un
  405 está apagado, para que no haya dos líneas. Un pedido que Tomcat rechaza antes de llegar a Spring (por ejemplo,
  una dirección con `%2F`) no pasa por la API: no tiene línea ni `requestId`.
- **Sin email** (GEN-5): la API nunca lo lee. La consulta de usuarios trae solo el id, así que no lo puede registrar
  por error. Un test lo verifica (caso 5 de GEN).

### 8.2 Métricas

Con Micrometer, que viene con Spring Boot, expuestas para Prometheus en `/actuator/prometheus`.

<!-- --8<-- [start:metricas] -->

| Métrica | Etiquetas | Para qué |
|---|---|---|
| `orders_recorded_total` | lado, tipo, estado y motivo del rechazo | cuántas órdenes se ejecutan, quedan pendientes o se rechazan, y por qué (GEN-4) |
| `orders_cancelled_total` | — | cuántas órdenes se cancelan |
| `orders_replayed_total` | — | cuántos reintentos devolvieron una orden existente (R-2) |
| `orders_turn_wait_seconds` | — | cuánto espera un envío el turno del usuario (sección 5.2): si crece, hay órdenes del mismo usuario chocando. También con histograma, para el percentil 95 del tablero |
| `http_server_requests_seconds` | dirección, método, código y resultado | cuánto tarda y cuántos errores da cada función (GEN-4). Lo mide Spring solo. El histograma, que hace falta para calcular el percentil 95, se prende con `management.metrics.distribution.percentiles-histogram.http.server.requests: true` |

<!-- --8<-- [end:metricas] -->

Las etiquetas toman pocos valores (2 lados × 2 tipos × 3 estados × 4 motivos, incluido "ninguno"), así que no
multiplican las series. El usuario y la orden no van como etiqueta: tendrían un valor por usuario.

Los posibles duplicados frenados (sección 5.5) no tienen contador propio: son las respuestas 409 del envío, que
`http_server_requests_seconds` ya cuenta por dirección, método y código. Ese número dice cada cuánto actúa el control.

Dos cosas encontradas al implementar (fase 6):

- **El contador se llama `orders_recorded_total`, no `orders_created_total`.** OpenMetrics, el formato que lee
  Prometheus, reserva el sufijo `_created`, y el cliente lo saca: `orders.created` salía como `orders_total`.
  `orders.recorded` nombra mejor lo que cuenta: las órdenes grabadas, también las rechazadas.
- **Los contadores existen en 0 desde que arranca la API.** `increase()` necesita dos muestras de una serie. Un contador
  que nace con la primera orden arranca en 1, y esa orden nunca se ve en el tablero. Por eso la API registra al arrancar
  las 20 combinaciones de lado, tipo, estado y motivo, además de los reintentos y las cancelaciones.

### 8.3 Cómo se ven: Prometheus y Grafana en el mismo `docker compose`

La decisión, con sus alternativas, está en el [ADR 0007](adr/0007-metricas-con-prometheus-y-grafana.md).

- **Prometheus** lee `/actuator/prometheus` cada 15 segundos.
- **Grafana** arranca con la fuente de datos y un tablero ya cargados desde archivos del repo: al levantar el compose,
  el tablero está listo, sin configurar nada a mano, y es la página de inicio. Muestra las órdenes por estado, tipo y
  lado, los rechazos por motivo, la latencia (percentil 95) y los errores por función, y la espera del turno. Los
  paneles por función no incluyen `/actuator/*`, porque se llenarían con la lectura de Prometheus, y distinguen el
  método: el envío y el listado comparten dirección. Las órdenes y la espera del turno se ven desde el primer pedido.
  La latencia y los errores, desde el segundo de cada función y código, con una lectura de Prometheus (15 segundos) en
  el medio: Spring crea esas series con el primer pedido, y `rate()` necesita dos muestras (precisado al implementar,
  fase 6).
  Los dos paneles que cuentan órdenes van en un solo color, porque por defecto Grafana pinta de rojo todo lo que pasa de
  80, y una barra de órdenes ejecutadas parecía un problema (encontrado al armar el portal, fase 7).
- **En producción con New Relic,** se cambia el registro de Micrometer (`micrometer-registry-new-relic`, con la clave
  y la cuenta por variables de entorno). Las métricas y su código no cambian.
- **Alternativas:** New Relic de verdad, con una cuenta gratuita, obligaría a quien evalúa a tener una cuenta y una
  clave para verlo; un tablero simulado mostraría números inventados. Prometheus y Grafana muestran las métricas
  reales de la API corriendo en la máquina de quien evalúa.

### 8.4 El objetivo de tiempo de respuesta (GEN-8)

**Percentil 95 por debajo de 100 ms en las cinco funciones,** con la API y la base locales, 2 millones de órdenes en
la tabla (el juego de datos de la sección 3.5) y usuarios de hasta 10.000 órdenes. En la base, la consulta más cara del
usuario pesado tarda 1,2 ms (sección 3.5); el resto del margen es para Java, el JSON y la red. Se mide en la fase 6
con k6, una herramienta de carga que corre en Docker y no entra en el código de la API. El usuario extremo queda fuera
del objetivo, como límite documentado (A-4): su portfolio lee unas 850.000 órdenes.

**Resultado (fase 6): se cumple.** Con 2.010.011 órdenes y 10 pedidos por segundo en cada escenario, el peor percentil
95 es el envío del usuario pesado, 61,15 ms. Le sigue su portfolio, con 35,01 ms; el resto queda por debajo de 9 ms. El
usuario extremo tarda entre 0,66 y 0,94 segundos por portfolio. Los números, la máquina y cómo repetirlo están en
[`mediciones/06-tiempo-de-respuesta.md`](mediciones/06-tiempo-de-respuesta.md).

**Medido de nuevo en la fase 8,** con el control de posibles duplicados (sección 5.5): se cumple igual. El peor
percentil 95 sigue siendo el envío del usuario pesado, 59,58 ms. Esa medición encontró que la primera versión de la
consulta nueva leía todas las órdenes del usuario, y se corrigió (72,9 ms antes de corregirla).

## 9. Seguridad

- **Sin autenticación,** porque el enunciado dice que no hace falta (S-5). La consecuencia está documentada:
  cualquiera puede ver el portfolio de otro usuario o mandar órdenes en su nombre cambiando el número. **Cómo se
  agregaría:** con Spring Security como servidor de recursos OAuth2, el usuario sale del token y no de la dirección
  (`/users/{userId}` pasa a `/me`). Las consultas ya filtran siempre por usuario, así que no habría que cambiarlas.
- **Validación en el borde:** el pedido se valida antes de llegar a la lógica (secciones 6.2 y 7.2), con topes para
  cada número (el monto, con el suyo y por la cantidad que da). Jackson 3, el que trae Spring Boot 4, acepta por defecto campos
  desconocidos y convierte un `2.5` en `2` sin avisar si el campo es entero. Se apagan las dos cosas:
  `spring.jackson.deserialization.fail-on-unknown-properties: true` y `accept-float-as-int: false`. Así, 2,5 acciones
  dan 400 (caso 14 del envío), en lugar de comprar 2 (principio 2). Con el mismo principio, al implementar (fase 5) se
  apagaron tres cosas más que Jackson acepta por defecto: una clave repetida (`{"quantity": 1, "quantity": 3}`
  compraba 3), un número escrito como texto (`"quantity": "10"`) y un número en lugar del valor de un enum
  (`"side": 1` grababa una venta). Las tres dan 400. Un número que no entra en su tipo da 400 con su campo, y un
  parámetro de la dirección que no se puede leer (mal codificado o sin nombre), 400 con un texto que lo explica.
- **SQL siempre con parámetros:** `JdbcClient` con parámetros con nombre. Ninguna consulta se arma pegando texto que
  viene del pedido.
- **Secretos por variables de entorno,** nunca en el repo: `.env` está ignorado por git. La API recibe la dirección
  de la base en formato JDBC, con el usuario y la clave en variables aparte (`SPRING_DATASOURCE_URL`, `_USERNAME` y
  `_PASSWORD`). La URL de Neon viene en otro formato (`postgresql://usuario:clave@host/base`), con la clave adentro:
  separarla evita que una dirección con la clave termine en un log o en un mensaje de error.
- **Actuator expone solo `health` y `prometheus`.** Los otros endpoints, como `env` o `heapdump`, pueden mostrar
  configuración o memoria con secretos.
- **Datos personales:** la API no lee el email (sección 8.1), y ninguna respuesta lo trae.
- **El contenedor de la API corre sin permisos de administrador** (un usuario sin privilegios dentro de la imagen),
  y la imagen no lleva secretos.
- **La base:** en producción, las migraciones las corre un usuario de la base y la API usa otro, que solo lee y escribe
  datos (sección 3.6). La URL de Neon es la de su dueño, y cualquiera que la tenga puede modificarla (R-5): por eso
  Neon solo se lee mientras se desarrolla.
- **Fuera de alcance, documentado:** límite de pedidos por cliente (rate limiting), HTTPS (en producción lo pone el
  balanceador delante de la API) y CORS, porque no hay un frontend en otro dominio.

## 10. Estrategia de tests

### 10.1 Los niveles

Primero el test que falla, después el código (`CLAUDE.md`). Los casos del PRD son la base de los tests (principio 5).

| Nivel | Qué prueba | Con qué | Por ejemplo |
|---|---|---|---|
| Unitarios | los cálculos en Java puro: la cuenta, el PPC, la cantidad por monto, el redondeo, la huella y el cursor | JUnit 6 (el que trae Spring Boot 4.1.2) y AssertJ, sin Spring ni base: milisegundos | PPC $150 (caso 5 de POR); $9.258,50 en PAMP dan 10 acciones (caso 4 del envío); `10000` y `10000.00` dan la misma huella |
| Integración | cada consulta y cada migración, contra Postgres 18 real | Testcontainers, con `database.sql` y las migraciones: el mismo camino que Neon (sección 3.6) | el último precio sale por fecha y no por orden de carga (caso 8 de POR); la búsqueda; el cursor con microsegundos (caso 4 de LIS) |
| Funcionales | las cinco funciones de punta a punta, por HTTP, con la API levantada | Spring Boot sobre la base de Testcontainers y un cliente HTTP | los casos del PRD: los 27 del envío, que son el test funcional que pide el enunciado (GEN-3), y los de portfolio, búsqueda, cancelación y listado |

Neon nunca se toca en los tests (`CLAUDE.md`). El tiempo de respuesta no se prueba acá: se mide en la fase 6 (sección
8.4), porque los tiempos en CI varían.

### 10.2 Cada caso arranca de los datos originales

Esto resuelve el choque que quedó abierto: los casos del PRD están escritos sobre los datos originales del usuario 1, y
varios escriben (el envío, la cancelación), mientras D-8.4 pedía que cada test arme sus datos para no pisarse.

- **Antes de cada test, la base vuelve a los datos originales.** Después de las migraciones se guarda una foto de las
  cuatro tablas, una sola vez. Antes de cada test, las tablas se vacían y se vuelven a llenar desde la foto, con los
  contadores de ids incluidos: la próxima orden vuelve a ser la 12, como en los ejemplos. Tarda 4 ms (medido:
  [`mediciones/10-resultados.md`](mediciones/10-resultados.md)).
- **Un test que necesita datos propios los agrega encima,** y la vuelta siguiente los borra. Por ejemplo, el usuario
  con 20 BMA del caso 1 de GEN, o la acción con un día sin operaciones del caso 7 de POR. Así se cumple D-8.4, cada
  test controla sus datos, y los casos del PRD corren tal como están escritos.
- **Un solo Postgres para toda la corrida,** y los tests corren de a uno, porque comparten la base.

| Alternativa | Por qué no |
|---|---|
| Una transacción que se deshace al final de cada test | no sirve para los tests por HTTP, donde la API confirma en su propia transacción, ni para los simultáneos, que necesitan datos confirmados desde conexiones separadas |
| Un contenedor nuevo por test | tarda segundos por test |
| Copiar una base modelo por test (`CREATE DATABASE ... TEMPLATE`) | la API tendría que cambiar de base en cada test |

### 10.3 Los casos simultáneos

Son el caso 1 y el 2 de GEN, los casos 20 y 27 del envío y CAN-5. Dos hilos mandan sus pedidos a la vez. Para que el test
falle siempre sin el turno, y no solo cuando los hilos se cruzan por casualidad, el test inyecta una pausa: la primera
orden espera, después de leer su cuenta, a que la segunda haya arrancado. La pausa va en el test (un espía sobre el
repository), sin cambiar el código de la API. Sin el turno, las dos leen la misma cuenta y el test falla; con el turno,
la segunda espera, y el test verifica que esté esperando su turno en Postgres antes de soltar a la primera. Es lo
mismo que las pruebas A y B de la sección 5, pero con la API real. Si alguien cambiara el nivel de aislamiento a
`REPEATABLE READ`, este mismo test fallaría, como la prueba C.

**Los casos del posible duplicado no esperan 5 segundos.** Para probar "pasada la ventana", el test le cambia la hora a
la primera orden en la base, en vez de dormir: corre en milisegundos y no depende de la velocidad de la máquina.

### 10.4 Cobertura

JaCoCo, con un piso que frena el build: **85 % de las líneas y 80 % de las ramas** del proyecto, sin contar la clase
que arranca la aplicación. Con TDD la cobertura suele dar más: el piso está para que no baje sin que nadie lo note. La
cobertura no dice si los tests son buenos; eso lo dicen los casos del PRD, cada uno con su test.

## 11. Construcción, ejecución y entrega

### 11.1 Maven y las herramientas que frenan el build

Maven, con su wrapper (`mvnw`): quien construye no necesita tener Maven instalado, y con Docker ni siquiera Java
(11.3). `./mvnw verify` corre todo y falla si cualquier control falla:

| Herramienta | Qué hace cumplir | Versión |
|---|---|---|
| Spotless, con google-java-format | un solo formato, sin discusiones; `spotless:apply` lo arregla solo | 3.11.0, con 2.0.0 |
| Error Prone | errores comunes de Java, al compilar: por ejemplo, comparar textos con `==` | 2.52.0 |
| NullAway, con las anotaciones de JSpecify | ningún `null` sin chequear. Lo vacío (el PPC de BMA, el precio de una `REJECTED` a mercado) se marca, y el compilador obliga a tratarlo | 0.14.3 |
| JaCoCo | el piso de cobertura (sección 10.4) | 0.8.16 |

- **Probadas juntas con Java 25,** en un proyecto descartable fuera del repo, con la imagen de Maven sobre Temurin 25.
  Compilan un record, un `switch` con patrones y una variable sin nombre (`_`). Un `null` sin chequear frena el build,
  por NullAway, y un archivo mal formateado también, por Spotless. Error Prone necesita que Maven abra partes internas
  del compilador de Java: van en `.mvn/jvm.config`.
- **NullAway encaja con Spring:** Spring Framework 7 marca con JSpecify qué puede ser `null` en su propia API.
- **Por qué no más herramientas:** Checkstyle repite lo que ya hace el formateador. SpotBugs y PMD buscan en buena
  parte los mismos errores que Error Prone, y cada herramienta suma reglas para ajustar. SonarCloud necesita una
  cuenta externa, y cuando se decidió el repo iba a ser privado hasta la fase 8.
- **Lo que ninguna herramienta controla** (sin números mágicos, nombres claros, métodos cortos) se cuida en la
  revisión de cada PR, con la vara de `CLAUDE.md`.

### 11.2 CI en GitHub Actions

En cada push y en cada PR:

1. `./mvnw verify` con Temurin 25: formato, compilación con Error Prone y NullAway, todos los tests (Testcontainers
   usa el Docker de la máquina de GitHub) y el piso de cobertura.
2. La imagen de Docker de la API se construye.
3. **Prueba de humo:** `docker compose up`, esperar a que la API esté lista, verificar que Prometheus la lea y que
   Grafana tenga el tablero y su fuente de datos, y pedir el portfolio del usuario 1, que tiene que dar sus pesos
   disponibles y su valor total (caso 3 de GEN). Después corre la colección de Postman con Newman, la herramienta
   de línea de comandos de Postman (11.5), y al final `docker compose down`.

Un PR se mergea solo con todo en verde.

**Desvío (fases 5 a 8).** El workflow está escrito (`.github/workflows/ci.yml`), pero GitHub Actions está bloqueado en
esta cuenta y sus jobs no llegaron a arrancar. Los PR de las tareas se mergearon sin esa marca verde, con los mismos pasos
corridos en local: `./mvnw verify`, `docker compose up`, la prueba de humo y la colección de Postman. El resultado, sobre
un clon limpio, está en [`mediciones/08-verificacion-final.md`](mediciones/08-verificacion-final.md).

### 11.3 Docker y `docker compose up`

<!-- --8<-- [start:compose] -->
```mermaid
flowchart LR
    quien([Quien evalúa]) -->|docker compose up| api
    subgraph compose["docker compose"]
        pg[("postgres:18<br/>carga database.sql al crearse")]
        api["api<br/>migra con Flyway y atiende en :8080"]
        prom["prometheus<br/>lee las métricas de la API"]
        graf["grafana<br/>tablero en :3000"]
        api -->|espera a que esté lista| pg
        prom -->|cada 15 segundos| api
        graf --> prom
    end
```
<!-- --8<-- [end:compose] -->

- **La API:** un `Dockerfile` en dos etapas, que compila con `maven:3.9-eclipse-temurin-25` y corre con
  `eclipse-temurin:25-jre`, con un usuario sin privilegios (sección 9). Quien evalúa solo necesita Docker (GEN-2).
- **Postgres** carga `database.sql` cuando se crea la base (carpeta `docker-entrypoint-initdb.d`), y la API migra al
  arrancar (sección 3.6). Sus datos viven en memoria: un `tmpfs` en `/var/lib/postgresql`, donde la imagen de
  Postgres 18 los guarda (la ruta cambió respecto de versiones anteriores). Cada `docker compose up` arranca de los
  datos originales, y los casos del PRD se pueden repetir a mano.
- **La API espera a que Postgres esté listo de verdad.** El chequeo de salud pregunta por red
  (`pg_isready -h 127.0.0.1`): al crear la base, la imagen levanta un servidor provisorio que solo escucha localmente
  mientras carga `database.sql`, y un chequeo sin red lo daría por listo antes de tiempo. Se encontró al armar las
  mediciones (sección 3.5).
- **Prometheus y Grafana** se configuran con archivos del repo: qué leer, la fuente de datos y el tablero (sección
  8.3). Grafana deja ver el tablero sin usuario y contraseña, porque es solo local. Sus datos también viven en memoria
  (`tmpfs`): cada `up` arranca de cero y `down` no deja volúmenes. Un `tmpfs` es de root y no deja ejecutar programas,
  así que Prometheus lo monta con `uid=65534` y Grafana con `exec,uid=472`, porque corre desde ahí su plugin de
  Prometheus (encontrado al implementar, fase 6).
- **Puertos:** la API en 8080 (con la página de OpenAPI), Grafana en 3000 y Prometheus en 9090. Postgres, en el 5433
  de la máquina, para mirarlo con `psql` sin chocar con un Postgres instalado. Los cuatro servicios escuchan solo en
  `127.0.0.1`, porque ni la API ni Grafana piden autenticación (sección 9), y sus puertos se cambian con `API_PORT`,
  `POSTGRES_PORT`, `PROMETHEUS_PORT` y `GRAFANA_PORT` si ya están ocupados (precisado al implementar, fases 5 y 6).
- **Solo la API y la base:** `docker compose up api` levanta la API con lo que necesita.
- **Contra Neon:** `scripts/run-on-neon.sh` levanta los mismos servicios sin la base local, con la dirección de `.env`
  (sección 3.6).

### 11.4 La configuración

`application.yml` trae los valores para correr en local, y todo lo que cambia según dónde corre llega por variables de
entorno: la base (sección 9), si migra al arrancar (sección 3.6) y los números de la sección 6.6. No hay perfiles de
Spring: una sola configuración, y las variables la pisan. Para apuntar a Neon, `scripts/run-on-neon.sh` llena las variables de
la base desde `.env`, que nunca se sube al repo.

El compose también deja cambiar la ventana del posible duplicado (sección 5.5), con `DUPLICATE_ORDER_WINDOW`: por
ejemplo, `DUPLICATE_ORDER_WINDOW=0s docker compose up --build` apaga el control. Un valor negativo no deja arrancar la
API (agregado en la fase 8).

### 11.5 La colección de Postman

En `postman/`, con la colección y un entorno local (la dirección de la API). Recorre todo:

1. el portfolio del usuario 1;
2. la búsqueda de "pamp";
3. una compra a mercado, que queda `FILLED`;
4. una compra límite, que queda `NEW`;
5. una venta límite de 5 PAMP, que también queda `NEW`;
6. el portfolio con las dos reservadas: la compra suma $100.000 a `cash.reserved` y la venta suma 5 a
   `reservedQuantity` de PAMP;
7. el listado de las pendientes;
8. la cancelación de la compra;
9. la cancelación de la venta;
10. el portfolio otra vez, con lo reservado como al principio;
11. un reintento de la compra del paso 3, con el mismo identificador, que devuelve la misma orden (200);
12. el identificador del paso 3 con otro contenido (422);
13. una compra de $700.000, que queda `REJECTED`.

Cada paso que necesita un identificador nuevo lo genera con `{{$guid}}`, y cada paso verifica su respuesta con tests
de la colección: por eso CI la puede correr con Newman (11.2).

### 11.6 El portal (fase 7)

Se arma desde los mismos archivos Markdown, como se decidió al empezar el RFC, con **Zensical**: el sucesor de Material
for MkDocs, del mismo equipo y con licencia MIT. Dibuja los diagramas de Mermaid con una sola línea de configuración
(según su documentación).

- **Por qué no Material for MkDocs,** que es lo más usado: está en mantenimiento desde noviembre de 2025, y sus
  correcciones terminaron el 2026-11-05.
- **El riesgo:** Zensical está en beta (0.1.0). La fase 7 arranca con una prueba corta, armando el portal con estos
  documentos. Si algo no se ve bien, la alternativa es Docusaurus (3.10.2, con un tema oficial para Mermaid): es
  estable, pero necesita Node y más configuración.
- Se publica en GitHub Pages, construido por CI desde `main` (`.github/workflows/portal.yml`; el repo es público desde el
  2026-11-08).

**Dónde quedó (fase 7):** <https://xeduardomx.github.io/cocos-challenge-backend/>. GitHub Actions está bloqueado en
esta cuenta (sección 11.2), así que ese workflow todavía no corrió. Mientras tanto, el sitio generado se sube
a mano a la rama `gh-pages`, y GitHub Pages lo publica desde ahí. Cuando Actions vuelva a correr, Pages se cambia para
publicar desde el workflow.

**Resultado de la prueba (fase 7): pasó, y Docusaurus no hizo falta.** Con estos documentos, Zensical 0.1.0 dibuja los
siete diagramas sin errores, el buscador encuentra con y sin tilde, y en un celular no hay scroll horizontal.

**Cómo quedó armado (fase 7):**

- **Alcanza con Docker,** como para la API. `scripts/portal.sh` corre la imagen `zensical/zensical:0.1.0`, que levanta
  el portal en la máquina o lo genera en `site/`, que no se sube. `zensical.toml`, en la raíz, dice cómo mostrar `docs/`.
- **Un enlace a una sección funciona en GitHub y en el portal,** porque las anclas se generan como las de GitHub, con
  acentos. Los enlaces a archivos de afuera de `docs/` apuntan a GitHub. Al generarlo, el portal se frena si un enlace
  lleva a una página o a una sección que no existe (`--strict`). Los enlaces a carpetas y a archivos de afuera no los
  mira: esos se revisaron en el sitio generado.
- **Cada página nueva vive donde se la ve.** La portada y los índices de `adr/` y `mediciones/` son archivos
  `README.md`, que GitHub también muestra al entrar a esas carpetas. Lo que existe solo en el portal (diagramas, runbook
  y métricas) está en `docs/portal/`.
- **Los diagramas y las tablas de métricas no se copian.** Una extensión del generador los toma del RFC y de la
  medición, de secciones marcadas con un comentario de HTML, que GitHub no muestra. Si una sección desaparece, el portal
  no se genera.
- **El CSS propio es una sola regla** (`docs/portal/portal.css`): más ancho para las tablas y los diagramas del RFC.

## 12. Decisiones de esta fase y ADR

**Decisiones de esta fase**

| Decisión | Por qué | Dónde |
|---|---|---|
| Los documentos se escriben en Markdown, que es la única fuente, con los diagramas en Mermaid. El portal HTML de la fase 7 se genera desde esos archivos | GitHub muestra el Markdown formateado y dibuja los diagramas, y cada cambio se lee en su PR. Un HTML escrito a mano se vería como código en GitHub y se desincronizaría del Markdown | `CLAUDE.md`, "Decisiones de diseño" |
| Java 25 y Spring Boot 4.1 | son las versiones estables con más soporte, y funcionan juntas | sección 2 |
| Capas agrupadas por función, no hexagonal | lo único de afuera es la base, y lo delicado se prueba contra Postgres real igual | sección 2, ADR 0001 |
| SQL directo con `JdbcClient`, no JPA ni jOOQ | las consultas importantes son SQL propio de Postgres, y el camino más delicado no puede tener comportamiento escondido | sección 2, ADR 0002 |
| La cuenta del usuario se calcula en un solo lugar (`account`), que usan el portfolio y el envío | si cada uno tuviera su fórmula, podrían mostrar y validar números distintos (principio 4) | sección 2, ADR 0001 |
| Las reglas que miran una sola fila van en la base, verificadas contra los datos de hoy; las que suman filas o miran otra tabla, en el código | la base no acepta datos imposibles y los datos heredados no se rompen. El identificador obligatorio también va en el código, porque `NOT VALID` haría fallar la cancelación de las órdenes viejas | sección 3.2 |
| Tres columnas nuevas en `orders`: el identificador (UUID), la huella del pedido y el motivo del rechazo como código, con la convención de nombres de Cocos | el identificador lo genera la app y tiene largo fijo; la huella no copia datos de la orden; el código sirve para el texto y para la métrica | sección 3.3 |
| No se guarda cuándo cambió el estado de una orden | el PRD no lo muestra, y auditar pediría un historial | sección 3.3 |
| La hora de una orden la pone la base al grabarla, con `clock_timestamp()` | un solo reloj, tomado dentro del turno del usuario. `now()` es la hora en que empezó la transacción | sección 3.4 |
| Un solo índice nuevo para las consultas, por (usuario, fecha, id), además de los que crean las reglas de unicidad. Cambia la decisión 7 de A-7 | medido con 2 millones de órdenes: el filtro por estado nunca cuesta más que el portfolio del mismo usuario, y el índice de A-7 suma 95 MB y encarece las altas un 30 % | sección 3.5 |
| Migraciones con Flyway; `database.sql` es la versión 1, y la copia local se arma igual que Neon: con los datos y sin historial | el camino que recorrió Neon es el que se prueba todos los días (probado con Flyway 12.4.0) | sección 3.6, ADR 0006 |
| Todas las migraciones pendientes en una sola transacción (`group`) | Neon recibiría todos los cambios o ninguno (probado en la copia local: sin `group`, una falla deja aplicadas las anteriores) | sección 3.6 |
| La API migra sola solo cuando se le pide: en la copia local y en los tests; a Neon, a propósito, en la fase 8 | apuntar la API a Neon no lo migra por accidente | sección 3.6 |
| La base trae las órdenes del usuario y `account` calcula todo en Java | el PPC depende del orden de las órdenes; partir el cálculo lo pondría en dos lugares | sección 4.1, ADR 0003 |
| El último precio se busca acción por acción | 0,05 ms, contra 26 y 80 ms de las otras formas (medido) | sección 4.2 |
| La búsqueda usa `strpos` y `starts_with`, sin `LIKE` | `%` y `_` quedan como texto común sin escaparlos | sección 4.3 |
| El cursor es la fecha con microsegundos y el id, en base64url | no pierde precisión al pasar de página, y el cliente no depende de su forma | sección 4.4 |
| Divisiones con 34 dígitos y un solo redondeo al final, con `HALF_UP` | se redondea solo el número que se muestra; los números del PRD dan igual con los dos modos | sección 4.5 |
| El turno del usuario es un lock sobre su fila de `users`, en `READ COMMITTED`, con espera máxima de 2 segundos | probado: sin turno, dos ventas dejan el mismo −10 que la orden 11; en `REPEATABLE READ`, también | sección 5.2, ADR 0004 |
| El reintento se reconoce dentro del turno, con la huella `v1` del pedido; la regla de unicidad queda como red | probado: dos pedidos con el mismo identificador crean una sola orden | sección 5.3, ADR 0005 |
| La cancelación no toma el turno: es un solo `UPDATE` condicionado | solo libera reservas, y dos cancelaciones simultáneas no ganan las dos (probado) | sección 5.4 |
| Una orden igual a otra aceptada hace menos de 5 segundos, con otro identificador, se frena con 409: una consulta a `orders` dentro del turno, con la huella que ya se guarda (agregado en la fase 8) | el identificador no protege si la app genera uno nuevo en cada intento. El turno evita la carrera, y la base ya tiene el dato: no hace falta un cache | sección 5.5, ADR 0009 |
| Direcciones con el usuario en la ruta, cancelación con `POST .../cancel` y sin versión en la ruta | no hay autenticación (S-5), y la orden cancelada no se borra | sección 6.1 |
| `REJECTED` y orden nueva responden 201; el reintento, 200; el identificador repetido con otro contenido, 422 | la orden rechazada existe; el reintento no crea nada | sección 6.3 |
| `amount` es siempre cantidad × precio, y en depósitos y retiros reemplaza a cantidad y precio | muestra lo que de verdad se usa en una orden por monto, y cumple LIS-5 | sección 6.2 |
| El identificador va en el cuerpo (`clientOrderId`), no en un encabezado | es un dato de la orden, como el `ClOrdID` de FIX | sección 6.5 |
| Los números configurables: 20 resultados, 50 caracteres, páginas de 20 (máximo 100), turno de 2 segundos, ventana del posible duplicado de 5 segundos | cada uno con su porqué | sección 6.6 |
| Se publica la especificación OpenAPI con springdoc-openapi | quien evalúa recorre la API desde el navegador, y la especificación sale del código | sección 6.7, ADR 0008 |
| Errores en Problem Details con `type` fijo, `requestId` y lista de campos; catálogo de 9 tipos, con textos en español | el cliente decide por el `type`, la persona lee el `detail`, y un 500 no muestra nada interno | sección 7 |
| Logs en JSON (ECS) con el id de cada pedido; la API nunca lee el email | se filtran por campo, y el email no se puede registrar por error | sección 8.1 |
| Cinco métricas: órdenes creadas por lado, tipo, estado y motivo; cancelaciones; reintentos; espera del turno; y las de HTTP | responden GEN-4 sin etiquetas que multipliquen las series | sección 8.2 |
| Prometheus y Grafana en el compose, con el tablero cargado desde el repo; New Relic, documentado como el cambio de registro | quien evalúa ve las métricas reales sin crear cuentas | sección 8.3, ADR 0007 |
| Objetivo: percentil 95 por debajo de 100 ms, con 2 millones de órdenes y usuarios de hasta 10.000, medido con k6 en la fase 6 | la consulta más cara del usuario pesado tarda 1,2 ms en la base | sección 8.4 |
| La base se configura en formato JDBC con usuario y clave aparte; Actuator expone solo `health` y `prometheus`; sin campos desconocidos ni cantidades decimales convertidas en enteras | la clave no viaja dentro de una URL, los endpoints con configuración no quedan abiertos, y 2,5 acciones no se compran como 2 | sección 9 |
| Tres niveles de test: unitarios, de integración contra Postgres real y funcionales por HTTP con los casos del PRD | cada nivel prueba lo suyo: los cálculos solos, el SQL contra la base real, y el recorrido completo | sección 10.1 |
| Antes de cada test, la base vuelve a los datos originales desde una foto (4 ms); los datos propios van encima | resuelve el choque con D-8.4: los casos del PRD corren tal cual y cada test controla sus datos | sección 10.2 |
| Los casos simultáneos inyectan una pausa desde el test, sin tocar el código de la API | el test falla siempre sin el turno, no solo por casualidad | sección 10.3 |
| Piso de cobertura: 85 % de líneas y 80 % de ramas | que no baje sin que nadie lo note | sección 10.4 |
| Spotless con google-java-format, Error Prone y NullAway con JSpecify, y JaCoCo, todos frenando el build | probados juntos con Java 25; Checkstyle, SpotBugs, PMD y SonarCloud repiten o necesitan cuentas externas | sección 11.1 |
| CI con build, tests, imagen y una prueba de humo con el compose y la colección de Postman | el caso 3 de GEN se prueba solo en cada push | sección 11.2, con su desvío |
| Compose con Postgres (datos en memoria), API, Prometheus y Grafana; el chequeo de salud de Postgres pregunta por red | cada `up` arranca de los datos originales, y la API no arranca antes de tiempo | sección 11.3 |
| El portal se arma con Zensical, con Docusaurus como alternativa si la prueba de la fase 7 fallaba. La prueba pasó | Material for MkDocs dejó de recibir correcciones el 2026-11-05 | sección 11.6 |

**ADR**

En la carpeta [`adr/`](adr/), un archivo corto por decisión, con contexto, opciones, decisión y consecuencias:

- [ADR 0001 — Capas agrupadas por función, no hexagonal](adr/0001-capas-por-funcion.md)
- [ADR 0002 — SQL directo con `JdbcClient`, no JPA](adr/0002-sql-directo-con-jdbcclient.md)
- [ADR 0003 — Las posiciones se calculan desde `orders`, sin tabla de posiciones](adr/0003-posiciones-calculadas.md)
- [ADR 0004 — El turno del usuario: un lock sobre su fila de `users`, en `READ COMMITTED`](adr/0004-turno-del-usuario.md)
- [ADR 0005 — Reintentos: identificador del cliente y huella del pedido](adr/0005-reintentos-con-identificador-y-huella.md)
- [ADR 0006 — Migraciones con Flyway, sobre una base con datos y sin historial](adr/0006-migraciones-con-flyway.md)
- [ADR 0007 — Métricas con Micrometer, y Prometheus y Grafana en el `docker compose`](adr/0007-metricas-con-prometheus-y-grafana.md)
- [ADR 0008 — Especificación OpenAPI con springdoc-openapi](adr/0008-openapi-con-springdoc.md)
- [ADR 0009 — Órdenes iguales con otro identificador: una segunda red, con la huella y una ventana de 5 segundos](adr/0009-ordenes-iguales-con-otro-identificador.md)
