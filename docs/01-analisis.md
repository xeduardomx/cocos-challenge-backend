# 01 — Análisis del enunciado y de los datos

> Fase 1 · 2026-11-06 · Estado: **aprobado por el autor el 2026-11-07**
>
> Fuentes: [`00-fuentes/backend-challenge.md`](00-fuentes/backend-challenge.md) (enunciado) y
> [`00-fuentes/database.sql`](00-fuentes/database.sql) (esquema y datos), contrastadas con la base de Neon
> que mandó Cocos.
>
> **Cómo leerlo:** este documento es el resumen. Cada punto lleva un código (C-1, D-1, A-6…) y su detalle, con el
> porqué, las opciones evaluadas, las mediciones y las fuentes, está en [`01-decisiones.md`](01-decisiones.md) bajo
> el mismo código.

## Resumen en 1 minuto

- La base de Neon y `database.sql` son **idénticas fila por fila**. Todo se desarrolla y se prueba contra una copia
  local; Neon se trata como producción: solo lectura.
- Los datos tienen **una trampa grande**: la orden 11 vende 30 acciones de BMA cuando el usuario tenía 20, y la
  posición queda en **−10**. Se muestra tal cual y no se corrige. Nada frenó esa venta, y dos ventas simultáneas sin
  bloqueo dejarían el mismo −10: por eso las órdenes de un mismo usuario se procesan de a una (D-1).
- Las dos ambigüedades que cambian los números del portfolio quedaron decididas:
  - Los pesos disponibles **descuentan las compras pendientes**: para el usuario 1 dan **$627.500**, no $753.000
    (A-1).
  - El rendimiento total es **contra el precio promedio de compra**: PAMP da **−0,45 %** (A-2). La variación
    diaria va aparte.
- Las posiciones **se calculan desde `orders`, no se guardan**: se midió con un millón de órdenes y, con un índice,
  cuesta milisegundos (A-4).
- La búsqueda encuentra por ticker o por nombre, **palabra por palabra, sin importar mayúsculas ni acentos** (A-5).
- En el envío de órdenes, una regla separa los casos: si con lo enviado no se puede armar una orden, es un **error**
  y no se graba; si la orden está bien armada pero la cuenta o el mercado no dejan ejecutarla, se graba como
  **`REJECTED`** (A-6).
- Se agregan **dos endpoints que el enunciado no pide**, cancelar y listar órdenes (A-7), y un **identificador
  obligatorio en cada orden** para que un reintento no compre dos veces (R-2). Todo lo agregado está en la sección 9.
- No se mandaron preguntas a Cocos: cada ambigüedad se investigó, se decidió y quedó escrita con su porqué.

## 1. Cómo se verificó

- **Neon, solo lectura.** Todo lo que se consultó corrió dentro de transacciones de solo lectura que se descartan al
  final (`BEGIN TRANSACTION READ ONLY … ROLLBACK`). No se escribió nada.
- **Neon = `database.sql`.** Se levantó un Postgres 18 descartable en Docker, se cargó `database.sql` y se comparó
  **todo el contenido** de las 4 tablas contra Neon: **0 diferencias**. `database.sql` carga sin errores en Postgres
  18, la misma versión mayor de Neon (18.6), así que sirve tal cual para los tests de integración.
- **Neon se puede modificar.** Los contadores de ids siguen en su valor inicial (`orders_id_seq` = 11): hasta hoy
  nadie insertó órdenes. Pero el usuario de la URL es el dueño de la base (`neondb_owner`), así que **cualquiera que
  tenga la URL puede modificarla** (R-5).
- **Todo lo demás, en la copia local:** los números de la sección 10, las pruebas de búsqueda con `unaccent` y dos
  mediciones con datos inventados.

  | Qué se midió | Resultado | Dónde |
  |---|---|---|
  | Portfolio de un usuario con 10.000 órdenes, en una tabla de 1.010.011 | sin índice, 26 ms; con índice por usuario, **3 ms** | A-4 |
  | Última página del listado de un usuario con 1.000.000 de órdenes | por número de página, 69 ms; por cursor, **0,017 ms** | A-7 |
  | Órdenes pendientes de ese mismo usuario | índice por (usuario, fecha, id), 28 ms; por (usuario, estado, fecha, id), **0,01 ms** | A-7 |

  Son números de una PC, con datos ya en memoria: sirven como orden de magnitud. El RFC repite la medición formal,
  antes y después de cada índice.
- **Investigación externa** cuando los datos la pedían: por qué PGR e IRCP no cotizan (D-2), el dividendo detrás del
  `previousClose` de BBAR (D-4), las bandas de precio de BYMA (A-6) y cómo identifican las órdenes los mercados
  (R-2). Las fuentes están en cada decisión.

## 2. Qué pide el enunciado

| Endpoint | Entrada | Salida |
|---|---|---|
| Portfolio | usuario | valor total de la cuenta, pesos disponibles para operar y lista de activos (cantidad, valor en $, rendimiento total %) |
| Buscar activos | texto (ticker y/o nombre) | lista de activos "similares" |
| Enviar orden | usuario, activo, lado (`BUY`/`SELL`), tipo (`MARKET`/`LIMIT`), cantidad **o** monto en pesos, precio (solo `LIMIT`) | la orden grabada en `orders`, con su estado |

Reglas de negocio, tal como las da el enunciado:

1. `MARKET`: se ejecuta al último precio (`close`) y queda `FILLED`.
2. `LIMIT`: requiere precio y queda `NEW`. No hace falta simular el mercado.
3. Si no alcanzan los pesos (compra) o las acciones (venta), la orden se graba como `REJECTED`.
4. Por monto: la cantidad **máxima** de acciones que entra, sin fracciones.
5. Tenencia y pesos se calculan con los movimientos de `orders` (columna `size`); valor, rendimiento y cantidad de
   cada posición, con las órdenes `FILLED`.
6. Solo se puede cancelar una orden `NEW`.
7. El efectivo es el instrumento `ARS` (tipo `MONEDA`); las transferencias son órdenes `CASH_IN` y `CASH_OUT`.
8. El retorno diario se calcula con `close` y `previousClose`.

Requisitos técnicos: un **test funcional del envío de orden**, sin autenticación de usuarios, y documentar los
supuestos y las decisiones de diseño. Se pueden cambiar las tablas si se justifica. Opcional: una colección de
Postman, Insomnia o REST Client.

## 3. Contradicciones

| # | Una fuente dice | La otra dice | Cómo se resolvió |
|---|---|---|---|
| C-1 | Enunciado: Node.js | Mail de Cocos: Java | Manda el mail: Java. |
| C-2 | Enunciado: columnas `instrumentId`, `previousClose`, `accountNumber` | Base: `instrumentid`, `previousclose`, `accountnumber` | No es una contradicción: Postgres pasa a minúsculas los nombres que no van entre comillas. El código usa los nombres reales. **Trampa para Spring/JPA:** por defecto convierte `instrumentId` en `instrument_id`, que no existe. |
| C-3 | Enunciado: `marketdata` tiene `datetime` | Base: `date`, sin hora | Vale la base: hay una fila por acción y por día. |
| C-4 | El portfolio pide "rendimiento **total**" | Más abajo explica el "retorno **diario**" | Son dos números distintos: se devuelven los dos (A-2). |
| C-5 | No se puede vender más de lo que se tiene | La orden 11 lo hizo y está `FILLED` | Ver D-1. |

## 4. Hallazgos en los datos

Contenido: 4 usuarios, 66 instrumentos (65 acciones y ARS), 126 filas de precios y 11 órdenes, todas del usuario 1.

Las órdenes del usuario 1, con la posición acumulada (solo cuentan las `FILLED`):

| id | fecha | activo | lado | tipo | estado | cantidad | precio | posición |
|---|---|---|---|---|---|---|---|---|
| 1 | 12/07 12:11 | ARS | CASH_IN | MARKET | FILLED | 1.000.000 | 1 | — |
| 2 | 12/07 12:31 | PAMP | BUY | MARKET | FILLED | 50 | 930 | 50 |
| 3 | 12/07 14:21 | PAMP | BUY | LIMIT | CANCELLED | 50 | 920 | 50 |
| 4 | 12/07 14:51 | PAMP | SELL | MARKET | FILLED | 10 | 940 | 40 |
| 5 | 12/07 15:14 | LOMA | BUY | LIMIT | NEW | 50 | 710 | 0 |
| 6 | 12/07 16:11 | PAMP | SELL | MARKET | REJECTED | 100 | 950 | 40 |
| 7 | 13/07 11:13 | BMA | BUY | LIMIT | NEW | 60 | 1.500 | 0 |
| 8 | 13/07 12:31 | ARS | CASH_OUT | MARKET | FILLED | 100.000 | 1 | — |
| 9 | 13/07 12:51 | BMA | BUY | LIMIT | FILLED | 20 | 1.540 | 20 |
| 10 | 13/07 14:11 | METR | BUY | MARKET | FILLED | 500 | 250 | 500 |
| 11 | 13/07 15:13 | BMA | SELL | MARKET | FILLED | 30 | 1.530 | **−10** |

| # | Hallazgo | Qué se decidió |
|---|---|---|
| D-1 | **La orden 11 vende 30 BMA teniendo 20:** la posición queda en −10. La orden 6 muestra la regla bien aplicada: quiso vender 100 PAMP teniendo 40 y quedó `REJECTED`. | El dato no se toca. El portfolio la muestra tal cual: −10 acciones, valor −$15.028, rendimiento vacío. La regla "no vender más de lo que se tiene" vive en el código, y las órdenes de un mismo usuario se procesan de a una. |
| D-2 | **PGR e IRCP no tienen precios.** Dejaron de cotizar antes de julio de 2023: IRCP fue absorbida por IRSA, y PGR tuvo en 2022 una oferta de compra a los minoritarios y salió de la bolsa de Londres. El dato es realista. | Sin cotización no se opera: `MARKET` y `LIMIT` quedan `REJECTED`. En la búsqueda aparecen, con el precio vacío. |
| D-3 | **Los precios son de julio de 2023:** "el precio de hoy" no existe. | El último precio es el `close` de la fecha más reciente de cada acción, sin límite de antigüedad (limitación documentada). |
| D-4 | **El `previousClose` de BBAR no coincide con el cierre del día anterior** (probable ajuste por dividendo) y venía con 3 decimales: la base lo redondeó sin avisar. | Se usa la columna tal cual: la variación diaria de BBAR da −0,87 %. Un precio de orden con más de 2 decimales es un error: nunca se redondea lo que el usuario autorizó. |
| D-5 | **DOME y POLL no tienen apertura, máximo ni mínimo el 13/07:** fueron días sin operaciones. | Es normal y no afecta: los cálculos solo usan `close` y `previousClose`. Si se muestran, van vacíos, nunca en 0. |
| D-6 | **Los precios de las órdenes viejas no salen de `marketdata`:** la orden 10 compró METR a $250 cuando el máximo del día fue $232, y del 12/07 no hay precios. | El costo de una posición sale del precio grabado en cada orden, nunca de `marketdata`. Los precios viejos no se corrigen. |
| D-7 | **La orden 9 es `LIMIT` y está `FILLED`.** | Se cuenta por estado, no por tipo. Contando solo las `MARKET`, BMA daría −30 y los pesos, $783.800. |
| D-8 | **Los usuarios 2, 3 y 4 no tienen órdenes.** | Su portfolio da $0 con la lista vacía: no es un error. Un usuario inexistente sí lo es (404). Sus compras quedan `REJECTED`. |
| D-9 | **`size` cambia de unidad:** en una acción es la cantidad de acciones; en ARS, la cantidad de pesos, con `price` = 1. | Se mantiene el modelo del enunciado. El código traduce cada fila una sola vez a movimiento de acciones o de pesos, y no los mezcla. Limitación: una transferencia no puede tener centavos. |
| D-10 | **`orders.datetime` no tiene zona horaria** y el servidor está en GMT. Las `MARKET` ejecutadas caen en horario de mercado solo si se leen como hora argentina. | Supuesto: las fechas existentes están en hora argentina. Para el RFC: pasar la columna a fecha y hora con zona. Las órdenes se ordenan por fecha y hora, con desempate por id. |

## 5. Hallazgos en el esquema

| # | Hallazgo | Por qué importa |
|---|---|---|
| E-1 | Ninguna columna, salvo `id`, es obligatoria (`NOT NULL`) | Para la base es válida una orden sin usuario o sin precio |
| E-2 | `side`, `type` y `status` aceptan cualquier texto | La base acepta `side = 'COMPRA'` |
| E-3 | `ticker` no es único, y (instrumento, fecha) en `marketdata` tampoco | Hoy no hay repetidos, pero nada lo impide |
| E-4 | No hay índices además de las claves primarias | Sin índice, cada portfolio lee la tabla de órdenes entera (medido en A-4) |
| E-5 | `price` es `NUMERIC(10,2)` | Máximo $99.999.999,99 por acción, 2 decimales y redondeo sin aviso (D-4) |
| E-6 | `size` es `INT` | Máximo 2.147.483.647 |
| E-7 | El orden alfabético es distinto: Neon usa `C.UTF-8` y la imagen local de Postgres, `en_US.utf8` | Ordenar por nombre da otro resultado en cada base: el orden se define siempre de forma explícita (A-5 desempata por ticker) |
| E-8 | Las extensiones `unaccent` y `pg_trgm` están disponibles, pero no instaladas | La búsqueda usa `unaccent` (A-5); `pg_trgm`, no |

El enunciado permite cambiar la base si se justifica. Estos puntos, más los que sumaron las decisiones (los índices
medidos en A-4 y A-7, la columna del identificador de R-2, el motivo del rechazo de A-6), son el **insumo del RFC**:
ahí se decide cada cambio y se escribe su migración. La lista completa está en `01-decisiones.md`, en "Pendientes
acumulados para el bloque del esquema". Los nombres de las columnas no se cambian (C-2), para que cualquiera pueda
consultar la base con los nombres que ya conoce.

## 6. Ambigüedades: qué se decidió

### A-1. Los pesos disponibles descuentan las compras pendientes

- El usuario 1 tiene $753.000 según su extracto y dos compras `LIMIT` pendientes: 50 LOMA a $710 y 60 BMA a $1.500,
  o sea **$125.500 reservados**. Sus pesos disponibles son **$627.500**.
- Sin descontar, podría mandar 10 compras de $700.000: cada una vería $753.000 y todas pasarían. La misma plata,
  usada diez veces.
- Las ventas pendientes reservan acciones: con 40 PAMP y una venta pendiente de 30, solo se pueden vender 10 más.
- **El valor total usa los $753.000**, porque lo reservado sigue siendo del usuario. Por eso valor total ≠
  disponibles + posiciones, y el portfolio muestra lo reservado para que la cuenta cierre a la vista.

### A-2. Rendimiento total contra el precio promedio de compra, y variación diaria aparte

- **Rendimiento total** = (último precio − PPC) ÷ PPC. El PPC es el promedio de las compras, ponderado por
  cantidad; las ventas no lo cambian. PAMP: compró 50 a $930, vendió 10 y le quedan 40, que hoy valen $925,85:
  **−0,45 %**. Los otros métodos evaluados daban −0,18 % y −0,14 %.
- **Variación diaria** = (`close` − `previousClose`) ÷ `previousClose`, en un campo aparte. PAMP: +0,44 %.
- Con la posición en 0 o negativa (BMA), el PPC y el rendimiento van vacíos. Si vuelve a positivo, el PPC arranca de
  la compra que la hizo positiva.

### A-3. Posición negativa

Ver D-1: se muestra tal cual.

### A-4. Las posiciones se calculan desde `orders`, no se guardan

- Una tabla de posiciones duplicaría el dato y no evitaría leer las órdenes: las reservas y el PPC salen de ellas.
  Además no admitiría la regla "cantidad ≥ 0", por BMA −10.
- La pregunta que decidía era si calcular es lento. Se midió (sección 1): con un índice por usuario, 3 ms para un
  usuario con 10.000 órdenes.
- "Cuando una orden se ejecuta, actualizar el listado de posiciones" se cumple solo, porque el listado se arma con
  las órdenes del momento. Quedó en el ADR 0003.

### A-5. "Similar" en la búsqueda: contiene, palabra por palabra

- Un solo texto, que se busca en el ticker y en el nombre a la vez: todas las palabras tienen que aparecer, en
  cualquier orden, sin importar mayúsculas ni acentos. "rio" encuentra "Molinos **Río** de la Plata"; "grupo
  galicia", "Grupo **Financiero** Galicia".
- **Sin tolerancia a errores de tipeo:** habría que inventar cuánto parecido alcanza. Queda como mejora posible, al
  estilo "¿quisiste decir…?".
- ARS no aparece. Un texto vacío da error; **una sola letra se permite** ("y" encuentra YPFD primero). Hay un tope de
  resultados configurable.
- Orden: ticker igual, ticker que empieza así, ticker que lo contiene, nombre que empieza así y el resto. Los empates
  se ordenan por ticker.
- Devuelve id, ticker, nombre, último precio y variación diaria.
- Limitaciones aceptadas: "banco" no encuentra VALO, porque figura como "BCO DE VALORES"; "bbva" no encuentra BBAR,
  porque figura como "Banco Frances".

### A-6. Envío de órdenes: error o `REJECTED`

**El criterio:** si con lo que mandó el usuario no se puede armar una orden, es un **error** y no se graba nada. Si
la orden está bien armada pero la cuenta o el mercado no dejan ejecutarla, se graba como **`REJECTED`**, con el
motivo en la respuesta.

| Caso | Resultado |
|---|---|
| Falta un dato o tiene un valor inválido; cantidad y monto juntos, o ninguno | error |
| Cantidad no entera o de 0 o menos; monto o precio de 0 o menos, o con más de 2 decimales | error |
| `LIMIT` sin precio, o `MARKET` con precio | error |
| Comprar o vender ARS, o mandar `CASH_IN`/`CASH_OUT` | error |
| Por monto, la cantidad da 0 ($500 en PAMP) | error |
| Por monto y a mercado, sobre un activo sin precio ($10.000 en PGR) | error: no hay con qué calcular la cantidad |
| Falta el identificador de la orden, o se repite con otro contenido | error (R-2) |
| El usuario o el activo no existen | 404 si están en la dirección del pedido; 400 si vienen en los datos |
| No alcanzan los pesos o las acciones, contando lo reservado | `REJECTED` |
| El activo no cotiza (10 PGR), `MARKET` o `LIMIT` | `REJECTED` |

**Por monto:** cantidad = monto ÷ precio, **cortando los decimales**, en compras y en ventas. El precio es el `close`
en una `MARKET` y el límite en una `LIMIT`. Después, la orden se valida como cualquier otra: si no alcanza, queda
`REJECTED` (no se compra "lo que entre").

**Precio límite:** se acepta cualquiera mayor a 0 y se reserva a ese precio. Una orden absurda se frena sola: 100
METR con límite $10.000 reservarían $1.000.000, y con $627.500 disponibles queda `REJECTED`.

### A-7. Cancelación y listado de órdenes sí; transferencias por API, no

- **Cancelar:** solo una orden `NEW`; en otro estado, error de conflicto; si es de otro usuario o no existe, 404.
  Libera la reserva. Si llegan dos cancelaciones juntas, gana una. Sin cancelación, la plata reservada por una
  `LIMIT` quedaría bloqueada para siempre, porque no simulamos el mercado.
- **Listar:** mínimo, para que la cancelación tenga sentido (hay que conocer el id). De la más nueva a la más vieja,
  un solo filtro opcional por estado, **paginado por cursor**. Con la paginación por número de página, cancelar
  entre una página y la siguiente hace que se salteen órdenes.
- **Transferencias por API, fuera:** no se piden, y sin autenticación cualquiera podría crear plata. Los datos de
  prueba las cargan directamente.

## 7. Riesgos técnicos

| # | Riesgo | Cómo quedó |
|---|---|---|
| R-1 | **Dos órdenes al mismo tiempo gastan la misma plata.** Con $627.500 disponibles llegan dos compras de $600.000: las dos leen $627.500, las dos pasan y se gastan $1.200.000. **El más serio del challenge.** | D-1: las órdenes de un mismo usuario se procesan de a una; el mecanismo, en el RFC. Test: dos ventas de 15 BMA teniendo 20 dejan una `FILLED` y una `REJECTED`. |
| R-2 | **Un reintento compra dos veces.** Se compran 100 PAMP ($92.585), la respuesta se pierde y la app reintenta: 200 PAMP y $185.170 gastados. | Cada orden trae un identificador obligatorio que pone el cliente, como el `ClOrdID` de FIX. Si se repite con el mismo contenido, se devuelve la orden original; con otro contenido, error. Es único por usuario. Para volver a intentar una orden rechazada, la app manda una orden nueva, con otro identificador. |
| R-3 | **Redondeo.** | La cantidad por monto se corta para abajo (A-6). Plata y precios, con 2 decimales y decimales exactos; los cálculos se redondean solo al final (el modo, en el RFC). |
| R-4 | **Rendimiento con costo cero.** | Imposible si la base exige precio > 0; con posición ≤ 0, rendimiento vacío (A-2). |
| R-5 | **Neon es modificable** por cualquiera que tenga la URL. | Todo corre contra la copia local; Neon, solo lectura. Si se le aplican las migraciones se decide en la fase 8. |

## 8. Supuestos

- **S-1.** Los precios ya están en pesos. **Confirmado:** las 65 acciones son locales y el otro instrumento es ARS.
- **S-2.** "Último precio" es la fila más reciente de cada acción, sin importar la fecha de hoy (D-3).
- **S-4.** Las fechas existentes de `orders` están en hora argentina (D-10).
- **S-5.** Sin autenticación, como dice el enunciado (*"NO es necesario implementar autenticación de usuarios"*): el
  usuario viene en el pedido. **Consecuencia:** cualquiera puede ver el portfolio de otro usuario o mandar órdenes en
  su nombre cambiando el número. En producción, el usuario saldría de la sesión autenticada. Queda fuera de alcance
  por decisión del enunciado, no por omisión.

El S-3 del borrador ("las recomendaciones de A-1 a A-7") se sacó: ya no son supuestos, son decisiones cerradas.

## 9. Alcance: lo que agregamos o cambiamos respecto del enunciado

**Endpoints**

| Endpoint | ¿Lo pide el enunciado? | Dónde |
|---|---|---|
| Portfolio | sí | A-1, A-2, A-4, D-1, D-8 |
| Búsqueda de activos | sí | A-5 |
| Envío de órdenes | sí | A-6, D-2, D-4, D-6, R-2 |
| **Cancelar una orden** | no: el enunciado define la regla, no el endpoint | A-7 |
| **Listar las órdenes del usuario**, paginado por cursor | no | A-7 |

**Datos que el enunciado no pide**

| Endpoint | Dato | Por qué | Dónde |
|---|---|---|---|
| Envío (pedido) | identificador de la orden, obligatorio | un reintento no crea una orden duplicada | R-2 |
| Portfolio | pesos reservados por órdenes pendientes | explica por qué valor total ≠ disponibles + posiciones | A-1 |
| Portfolio | último precio, PPC y variación diaria de cada posición | explican el valor y el rendimiento | A-2 |
| Búsqueda | id, último precio y variación diaria | el id hace falta para mandar una orden | A-5 |
| Envío | motivo del rechazo | sin él, nadie puede responder "¿por qué me rechazaron?" | A-6 |
| Envío por monto | la cantidad calculada | el usuario ve cuántas acciones salieron | A-6 |
| Envío y listado | el identificador de la orden | si se perdió la respuesta, el cliente encuentra su orden | R-2 |
| Listado | cursor de la página siguiente | paginación | A-7 |

**Reglas:** las que agregamos o precisamos son las de las secciones 4 y 6. La lista completa, con el código de cada
una, está en el inventario de `01-decisiones.md`.

**Cambios al modelo de datos:** ninguno en esta fase. Se decidieron y justificaron en el RFC (sección 3), y se
aplicaron con migraciones en la fase 5, solo en la copia local.

**Lo que evaluamos y dejamos afuera, a propósito**

| Qué | Por qué | Dónde |
|---|---|---|
| Corregir datos heredados (orden 11, precio de la orden 10, `previousClose` de BBAR) | no le corresponde al desarrollador; se documentan | D-1, D-4, D-6 |
| Tabla de posiciones, o una foto del portfolio a una fecha | con un índice alcanza (medido); la foto queda como evolución posible | A-4 |
| Tolerancia a errores de tipeo en la búsqueda | habría que inventar el umbral | A-5 |
| Bandas de precio como las de BYMA, o validar el paso de 5 centavos | sería simular el mercado; el porcentaje de las bandas no se pudo verificar | A-6, D-4 |
| Ejecutar en el momento una `LIMIT` que cruza el precio | contradice el enunciado: una `LIMIT` queda `NEW` | A-6 |
| Transferencias por API | no se piden; la plata entra desde el banco | A-7 |
| Detalle de una orden por id | el listado alcanza | A-7 |
| Identificador de orden opcional, o generado por el servidor | uno opcional deja órdenes sin protección, y uno generado por el servidor no protege el reintento | R-2 |
| Autenticación de usuarios | el enunciado dice que no es necesaria; la consecuencia queda documentada | S-5 |

## 10. Números de referencia (usuario 1)

Recalculados el 2026-11-06 con consultas sobre la copia local (ver anexo). Son los valores esperados de los tests de
aceptación.

**Portfolio**

| Concepto | Valor |
|---|---|
| Pesos de la cuenta (todas las órdenes `FILLED`) | $753.000,00 |
| Pesos reservados por compras `NEW` (órdenes 5 y 7) | $125.500,00 |
| **Pesos disponibles para operar** | **$627.500,00** |
| **Valor total de la cuenta** | **$889.756,00** |

| Activo | Cantidad | Último precio | Valor | PPC | Rendimiento total | Variación diaria |
|---|---|---|---|---|---|---|
| METR | 500 | $229,50 | $114.750,00 | $250,00 | −8,20 % | −1,08 % |
| PAMP | 40 | $925,85 | $37.034,00 | $930,00 | −0,45 % | +0,44 % |
| BMA | −10 | $1.502,80 | −$15.028,00 | — | — | −1,15 % |

Cuenta del total: 753.000 + 114.750 + 37.034 − 15.028 = 889.756. En BMA, el PPC y el rendimiento van vacíos: con −10
no hay acciones compradas a ningún precio (A-2).

**Envío, cancelación y listado** (cada caso parte de los datos originales)

| Caso | Resultado esperado |
|---|---|
| Comprar 10 PAMP a mercado | `FILLED` a $925,85 ($9.258,50). Portfolio: 50 PAMP, PPC $929,17, rendimiento −0,36 %; disponibles $618.241,50. El valor total sigue en $889.756 |
| Comprar 15 BMA a mercado | `FILLED` a $1.502,80 ($22.542). BMA pasa a 5 acciones con PPC $1.502,80: el promedio arranca de esa compra (arrastrando los $1.540 daría $1.428,40). Rendimiento 0 %; disponibles $604.958; el valor total sigue en $889.756 |
| $10.000 en PAMP a mercado | 10 acciones ($9.258,50); los $741,50 que sobran siguen disponibles |
| $9.258,50 en PAMP a mercado | exactamente 10 acciones, no 9 |
| $10.000 en PAMP con límite $900 | 11 acciones, `NEW`; reserva $9.900 |
| $630.000 en PAMP a mercado | 680 acciones ($629.578), más que los $627.500 disponibles: `REJECTED` |
| $700.000 en PAMP a mercado | 756 acciones ($699.942,60): `REJECTED`. Sin la reserva de A-1 habría pasado |
| Cancelar la orden 7 y mandar otra vez la compra de $700.000 | la orden 7 queda `CANCELLED` y libera su reserva de $90.000: los disponibles suben a $717.500. Con un identificador nuevo, la compra queda `FILLED` y los disponibles bajan a $17.557,40. Con el de antes, devuelve el mismo `REJECTED` sin comprar (R-2) |
| 10 METR con límite $10.000 | `NEW`; reserva $100.000 y los disponibles bajan a $527.500 |
| 100 METR con límite $10.000 | `REJECTED`: reservaría $1.000.000 |
| $500 en PAMP | error: la cantidad da 0 |
| 10 PGR a mercado | `REJECTED`, con el precio vacío |
| Vender 50 PAMP | `REJECTED`: tiene 40 |
| Cancelar la orden 3 (ya `CANCELLED`) o la 2 (`FILLED`) | error de conflicto; no cambia nada |
| Reenviar una orden con el mismo identificador y el mismo contenido | devuelve la misma orden; `orders` no suma filas |
| Listado con filtro `NEW` | las órdenes 7 (60 BMA a $1.500) y 5 (50 LOMA a $710), en ese orden |
| Portfolio del usuario 2 / del usuario 99 | $0 con la lista vacía / 404 |

**Búsqueda** (sin importar mayúsculas ni acentos, en el orden de A-5)

| Texto | Resultado |
|---|---|
| "banco" | BBAR, BHIP, BMA, BPAT |
| "rio" | BHIP, MOLI, OEST, RICH (MOLI por "Río") |
| "pa" | PAMP, PATA, BPAT, CGPA2, INTR |
| "grupo galicia" | GGAL |
| "gas sur" | CGPA2, TGSU2 |
| "y" | YPFD primero |
| "pesos" o "_" | nada: ARS no aparece, y `_` se busca como texto, no como comodín |
| "pgr" | PGR, con el precio y la variación vacíos |

**Variación diaria de BBAR:** −0,87 % (con la columna `previousClose`, D-4).

## 11. Qué sigue

- **PRD (fase 2):** convierte estas decisiones en requisitos y criterios de aceptación (Given/When/Then), con el
  alcance de la sección 9 y los números de la sección 10.
- **RFC (fase 3):** arquitectura; contratos de API (direcciones, códigos HTTP, formato de fechas); el mecanismo para
  procesar de a una las órdenes de un usuario (R-1); los cambios al esquema con sus migraciones (sección 5); el modo
  de redondeo, el tamaño de página, el largo máximo de la búsqueda y el formato del identificador de orden.

## Anexo: consultas de verificación

Copia local descartable, sin puertos expuestos (se borra al apagarla):

```bash
docker run --rm -d --name cocos-analisis-pg18 -e POSTGRES_PASSWORD=analisis postgres:18
docker exec -i cocos-analisis-pg18 psql -U postgres -X -q -v ON_ERROR_STOP=1 < docs/00-fuentes/database.sql
```

Pesos del usuario 1 y reserva de las compras `NEW`:

```sql
select
  sum(case when status = 'FILLED' then
        case side when 'CASH_IN'  then  size * price
                  when 'SELL'     then  size * price
                  when 'CASH_OUT' then -size * price
                  when 'BUY'      then -size * price end
      else 0 end)                                                         as pesos,      -- 753000.00
  sum(case when status = 'NEW' and side = 'BUY' then size * price else 0 end) as reservado -- 125500.00
from orders
where userid = 1;
```

Posiciones con último precio, PPC y rendimientos. Con estos datos, cada posición tiene una sola compra ejecutada, así
que el promedio simple de las compras coincide con el PPC cronológico de A-2; el código implementa el cronológico.

```sql
with ultimo as (
  select distinct on (instrumentid) instrumentid, close, previousclose
  from marketdata
  order by instrumentid, date desc
), pos as (
  select instrumentid,
         sum(case when side = 'BUY' then size when side = 'SELL' then -size end)         as cantidad,
         sum(case when side = 'BUY' then size * price end)
           / sum(case when side = 'BUY' then size end)                                   as ppc
  from orders
  where userid = 1 and status = 'FILLED' and side in ('BUY', 'SELL')
  group by instrumentid
)
select i.ticker, p.cantidad, u.close, p.cantidad * u.close                               as valor,
       round(p.ppc, 2)                                                                   as ppc,
       case when p.cantidad > 0 then round((u.close - p.ppc) / p.ppc * 100, 2) end       as rend_total,
       round((u.close - u.previousclose) / u.previousclose * 100, 2)                     as var_diaria
from pos p
join instruments i on i.id = p.instrumentid
join ultimo u on u.instrumentid = p.instrumentid
where p.cantidad <> 0
order by i.ticker;
-- BMA  -10  1502.80  -15028.00 1540.00  (vacío)  -1.15   <- la API muestra el PPC vacío (sección 10)
-- METR 500   229.50  114750.00  250.00   -8.20   -1.08
-- PAMP  40   925.85   37034.00  930.00   -0.45    0.44
```

Posición acumulada por instrumento (de acá sale el −10 de BMA):

```sql
select o.id, i.ticker, o.side, o.status, o.size,
       sum(case when o.status = 'FILLED' and o.side = 'BUY'  then  o.size
                when o.status = 'FILLED' and o.side = 'SELL' then -o.size
                else 0 end) over (partition by o.instrumentid order by o.datetime, o.id) as posicion
from orders o
join instruments i on i.id = o.instrumentid
order by o.datetime, o.id;
```

Búsqueda, versión de verificación de las reglas de A-5 (no es la implementación; necesita `create extension
unaccent`):

```sql
with q as (select lower(unaccent(trim('pa'))) as t),
palabras as (select w from q, regexp_split_to_table(q.t, '\s+') w where w <> '')
select i.ticker
from instruments i, q
where i.type <> 'MONEDA'
  and not exists (select 1 from palabras
                  where strpos(lower(unaccent(i.ticker || ' ' || i.name)), w) = 0)
order by case when lower(i.ticker) = q.t                 then 1
              when lower(i.ticker) like q.t || '%'       then 2
              when lower(i.ticker) like '%' || q.t || '%' then 3
              when lower(unaccent(i.name)) like q.t || '%' then 4
              else 5 end,
         i.ticker collate "C"
limit 20;
-- PAMP, PATA, BPAT, CGPA2, INTR
```
