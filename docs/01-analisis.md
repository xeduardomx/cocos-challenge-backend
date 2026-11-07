# 01 — Análisis del enunciado y de los datos

> Fase 1 · 2026-11-06 · Estado: **borrador, pendiente de aprobación**
>
> Fuentes: [`00-fuentes/backend-challenge.md`](00-fuentes/backend-challenge.md) (enunciado) y
> [`00-fuentes/database.sql`](00-fuentes/database.sql) (esquema y datos), contrastadas con la base de Neon
> que mandó Cocos.

## Resumen en 1 minuto

- La base de Neon y `database.sql` son **idénticas fila por fila** (verificado con una comparación completa).
  Se puede trabajar 100 % con la copia local sin perder nada.
- Los datos tienen **una trampa grande**: la orden 11 vende 30 acciones de BMA cuando el usuario tenía 20, así que
  la posición queda en **−10**. El propio enunciado dice que esa venta tendría que haberse rechazado.
- Hay **cuatro puntos del enunciado con más de una lectura** que cambian los números del portfolio: los pesos
  disponibles (¿se descuentan las órdenes pendientes?), el rendimiento (¿total o diario? ¿contra qué costo?), la
  posición negativa y qué precio es "el último".
- Por qué importa: para el usuario 1, los pesos disponibles dan **$753.000 o $627.500** según la lectura, y el
  rendimiento de PAMP da **−0,45 %, −0,18 % o −0,14 %** según el método.
- El esquema no protege nada: no hay columnas obligatorias, no hay valores controlados, no hay índices, y los
  nombres reales de las columnas no son los del enunciado (`previousclose`, no `previousClose`).
- Hay 4 preguntas para Cocos (sección 9) y, mientras no respondan, supuestos explícitos (sección 8) para no frenar.

## 1. Cómo se verificó

- Todo lo que se consultó en Neon corrió **dentro de transacciones de solo lectura que se descartan al final**
  (`BEGIN TRANSACTION READ ONLY … ROLLBACK`). No se escribió nada.
- Se levantó un Postgres 18 temporal en Docker (se borró al apagarlo), se cargó `database.sql` y se comparó **todo
  el contenido** de las 4 tablas contra Neon: **0 diferencias**.
- `database.sql` carga sin errores en Postgres 18, la misma versión mayor de Neon (18.6). Sirve tal cual para los
  tests de integración.
- Los contadores de ids de Neon siguen en su valor inicial (`orders_id_seq` = 11): hasta hoy nadie insertó
  órdenes. Pero el usuario de la URL es el dueño de la base (`neondb_owner`) y puede escribir: **cualquiera que
  tenga la URL puede modificarla**. Otra razón para no depender de ella.

## 2. Qué pide el enunciado

| Endpoint | Entrada | Salida |
|---|---|---|
| Portfolio | usuario | valor total de la cuenta, pesos disponibles y lista de activos (cantidad, valor en $, rendimiento total %) |
| Buscar activos | texto (ticker y/o nombre) | lista de activos "similares" |
| Enviar orden | usuario, activo, lado (BUY/SELL), tipo (MARKET/LIMIT), cantidad **o** monto en pesos, precio (solo LIMIT) | la orden grabada en `orders`, con su estado |

Reglas de negocio que salen del enunciado:

1. MARKET: se ejecuta al último precio (`close`) y queda `FILLED`.
2. LIMIT: requiere precio y queda `NEW`. No se simula el mercado, así que una LIMIT nueva nunca pasa sola a `FILLED`.
3. Si no alcanza (pesos para comprar, acciones para vender), la orden se graba como `REJECTED`.
4. Por monto: cantidad = parte entera de (monto ÷ precio). Sin fracciones de acción.
5. Pesos y tenencias se calculan desde `orders` (columna `size`); las posiciones, solo con órdenes `FILLED`.
6. Solo se puede cancelar una orden `NEW`.
7. El efectivo es el instrumento `ARS` (tipo `MONEDA`); depósitos y extracciones son órdenes `CASH_IN` y `CASH_OUT`.

## 3. Contradicciones

| # | Una fuente dice | La otra dice | Cómo se resuelve |
|---|---|---|---|
| C-1 | Enunciado: Node.js | Mail de Cocos: Java | Manda el mail: Java. |
| C-2 | Enunciado: columnas `instrumentId`, `previousClose`, `accountNumber` | Base real: `instrumentid`, `previousclose`, `accountnumber` (Postgres pasa a minúsculas los nombres que no van entre comillas) | Se usan los nombres reales. **Trampa para Spring/JPA**: por defecto convierte `instrumentId` en `instrument_id`, que no existe. |
| C-3 | Enunciado: `marketdata` tiene `datetime` | Base real: `date`, de tipo `DATE` (sin hora) | Vale la base. |
| C-4 | El portfolio pide "rendimiento **total** (%)" | Más abajo explica cómo calcular el "retorno **diario**" (`close` vs `previousClose`) | Ver A-2: devolver los dos. |
| C-5 | Regla: no se puede vender más de lo que se tiene | La orden 11 vende 30 BMA teniendo 20, y está `FILLED` | Ver D-1. |

## 4. Hallazgos en los datos

Contenido: 4 usuarios, 66 instrumentos (65 acciones y ARS), 126 filas de precios y 11 órdenes.

### D-1. Posición negativa de BMA (la trampa principal)

Órdenes del usuario 1 (el único que tiene) con la posición acumulada, contando solo las `FILLED`:

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

La orden 6 muestra la regla bien aplicada: quiso vender 100 PAMP teniendo 40, y quedó `REJECTED`. La orden 11 la
viola. Puede ser un error de carga o una trampa a propósito; en los dos casos el portfolio tiene que hacer algo
razonable con −10.

| Opción | Qué muestra | Valor total de la cuenta | Problema |
|---|---|---|---|
| **A. Mostrar tal cual** | BMA con −10 y valor −$15.028 | $889.756 | Se ve raro, pero es lo que dice el registro |
| B. Ocultar posiciones ≤ 0 | BMA desaparece | $904.784 | **Infla la cuenta en $15.028**: cuenta la plata de la venta y esconde las acciones que faltan |
| C. Ignorar la orden 11 | BMA con 20 | $888.940 | Reescribe la historia; el enunciado dice usar todas las órdenes `FILLED` |

**Recomendación: A.** El registro de órdenes es la fuente de verdad y no se corrige en silencio. La validación de
acciones antes de vender impide que el sistema genere casos así de acá en adelante. El rendimiento de una posición
≤ 0 no tiene sentido: se devuelve vacío.

### D-2. Instrumentos sin precio

- **PGR (id 3) e IRCP (id 10)** no tienen ninguna fila en `marketdata`. ARS tampoco, lo cual es lógico (vale 1).
- Consecuencias: una orden MARKET sobre PGR o IRCP no tiene a qué precio ejecutarse, y la búsqueda los tiene que
  poder mostrar igual, sin precio.
- Los otros 63 instrumentos tienen exactamente 2 días: 13/07/2023 y 14/07/2023. No hay días repetidos.

### D-3. "El último precio" no es "el precio de hoy"

Los precios son de **julio de 2023** y hoy es noviembre de 2026. Si se busca "el precio de hoy" no aparece nada.
"Último" = la fila con la **fecha más reciente de cada instrumento** (hoy, el 14/07/2023 para los 63).

### D-4. Un precio anterior que no cuadra (BBAR)

Lo normal: el `previousClose` de un día es el `close` del día anterior. Se cumple en 62 de los 63 instrumentos.
**BBAR (id 22)** no: cerró en 1.062,20 el 13/07, pero el 14/07 dice `previousClose` = 1.047,17.

Además, en el SQL ese valor viene como `1047.166` (3 decimales) y la columna guarda 2: **Postgres lo redondeó a
1.047,17 sin avisar**.

Efecto en la variación diaria de BBAR: −0,87 % con el dato de la columna, −2,27 % con el cierre real del día
anterior.

**Recomendación:** usar `previousClose`, como pide el enunciado, y dejar anotada la inconsistencia (no se corrigen
datos de mercado). Lección para el diseño: como la base redondea sin avisar, **la escala de los precios se valida al
recibir el pedido**: una orden LIMIT a $100,555 se rechaza, no se redondea.

### D-5. Filas con valores vacíos

DOME y POLL tienen `open`, `high` y `low` vacíos el 13/07, con `close` igual a `previousClose` (día sin
operaciones). No afectan los cálculos pedidos, que solo usan `close` y `previousClose`, pero el código no puede
asumir que las columnas vienen llenas.

### D-6. Los precios de las órdenes viejas no salen de `marketdata`

- Las órdenes del 12/07 son de un día del que no hay precios.
- La orden 10 compró METR a $250 el 13/07, cuando ese día el máximo fue $232.

Conclusión: el costo de una posición se toma **del precio grabado en cada orden**, nunca reconstruido desde
`marketdata`.

### D-7. Una LIMIT que terminó `FILLED`

La orden 9 es LIMIT y está `FILLED`: en el modelo, una LIMIT puede ejecutarse más tarde (eso lo haría el mercado
real). No simulamos esa parte, pero el portfolio tiene que contarla igual: se cuenta por estado, no por tipo.

### D-8. Usuarios sin movimientos

Los usuarios 2, 3 y 4 no tienen órdenes: su portfolio es $0 con la lista vacía, y eso no es un error. Como no
tienen pesos, **cualquier compra suya termina `REJECTED`**. Para probar compras exitosas se usa el usuario 1 o se
carga un `CASH_IN` en los datos de prueba.

### D-9. La columna `size` cambia de unidad según el activo

- En acciones, `size` es la cantidad de acciones.
- En ARS, `size` es la cantidad de pesos, con `price` = 1.

Ventaja: la misma cuenta (`size × price`) da el monto en pesos de cualquier orden. Limitación: `size` es entero, así
que **un depósito no puede tener centavos** ($100,50 no se puede representar). No afecta a ningún endpoint pedido;
queda anotado para el RFC.

### D-10. Zona horaria

`orders.datetime` es una fecha y hora **sin zona horaria**, y la base está configurada en GMT. Los horarios de las
órdenes (11:13 a 16:11) tienen sentido como hora argentina, dentro del horario de mercado (en 2023, BYMA operaba de
11 a 17). Leídos como UTC serían 8:13 a 13:11 en Argentina, y la orden de las 8:13 caería antes de la apertura.
**Supuesto:** las fechas existentes están en hora argentina. Cómo se guardan las nuevas se decide en el RFC.

## 5. Hallazgos en el esquema

| # | Hallazgo | Por qué importa |
|---|---|---|
| E-1 | Ninguna columna, salvo `id`, es obligatoria (`NOT NULL`) | Para la base es válida una orden sin usuario o sin precio |
| E-2 | `side`, `type` y `status` aceptan cualquier texto | La base acepta `side = 'COMPRA'`; toda la validación queda en la aplicación (o se agrega en el RFC) |
| E-3 | `ticker` no es único, y (instrumento, fecha) en `marketdata` tampoco | Hoy no hay repetidos, pero nada lo impide |
| E-4 | No hay índices además de las claves primarias | Con 11 órdenes da igual; con millones, el portfolio (órdenes de un usuario) y el último precio (por instrumento y fecha) recorren la tabla entera |
| E-5 | `price` es `NUMERIC(10,2)` | Máximo $99.999.999,99 por acción, 2 decimales y redondeo sin aviso (ver D-4) |
| E-6 | `size` es `INT` | Máximo 2.147.483.647 |
| E-7 | El orden alfabético es distinto: Neon usa `C.UTF-8` y la imagen local de Postgres usa `en_US.utf8` | Ordenar por nombre da otro resultado: en Neon "BCO DE VALORES…" va antes que "Banco Frances", en local va después. **Nunca depender del orden por defecto**: se define explícito |
| E-8 | Las extensiones `unaccent` (ignorar acentos) y `pg_trgm` (parecido entre textos) están disponibles pero no instaladas | Sirven para la búsqueda (ver A-5) |

El enunciado permite cambiar la base si se justifica. Estos puntos son los candidatos para el RFC.

## 6. Ambigüedades: qué hay que decidir

Cada una con sus opciones y una recomendación. Se cierran en el PRD.

### A-1. ¿Los pesos disponibles descuentan las compras pendientes?

El usuario 1 tiene dos compras LIMIT en `NEW`: 50 LOMA a $710 y 60 BMA a $1.500, o sea **$125.500
comprometidos**.

- **Sin descontar:** disponibles $753.000.
- **Descontando:** disponibles **$627.500**.

Si no se descuentan, el usuario puede mandar 10 compras LIMIT de $700.000 cada una, y si el mercado las ejecuta
todas, la cuenta queda en −$6.247.000.

**Recomendación: descontar.** Las compras `NEW` reservan pesos y las ventas `NEW` reservan acciones: eso significa
"disponibles para operar". **Ojo:** el valor total de la cuenta sigue usando los $753.000, porque la plata reservada
sigue siendo del usuario. Entonces **valor total ≠ disponibles + posiciones**.

### A-2. ¿Qué rendimiento muestra el portfolio?

Son dos números distintos y el enunciado nombra los dos:

- **Rendimiento total:** cuánto gané o perdí contra lo que pagué.
- **Variación diaria:** cuánto se movió el precio en el último día (`close` vs `previousClose`).

Además, el total depende de cómo se calcule "lo que pagué". Ejemplo con PAMP: compró 50 a $930, vendió 10 a $940 y
le quedan 40, que hoy valen $925,85 cada una ($37.034).

| Método | Contra qué se compara | Rendimiento |
|---|---|---|
| **Precio promedio de compra (PPC)** | 40 × $930 = $37.200 | **−0,45 %** |
| Plata neta puesta (compras − ventas) | $46.500 − $9.400 = $37.100 | −0,18 % |
| Todo junto, con la ganancia de la venta | ($37.034 + $9.400) contra $46.500 | −0,14 % |

**Recomendación:** rendimiento total contra el **precio promedio de compra**, que es como lo muestran los brokers
("PPC"), y la **variación diaria** como campo aparte. Cuesta poco y cubre las dos lecturas.

### A-3. Posición negativa

Ver D-1. Recomendación: mostrarla como está.

### A-4. ¿Las posiciones se guardan o se calculan?

"Cuando una orden se ejecuta, actualizar las posiciones" se puede leer como "mantener una tabla de posiciones".
Pero el enunciado también dice "calcular tenencia y pesos desde `orders`".

**Recomendación:** calcular siempre desde `orders`. Hay una sola fuente de verdad y no existe el riesgo de que una
tabla de posiciones y las órdenes digan cosas distintas. Una tabla de posiciones o un índice son optimizaciones
para discutir en el RFC si hacen falta.

### A-5. ¿Qué significa "similar" en la búsqueda?

| Opción | Buscando "rio" encuentra… | Buscando mal escrito |
|---|---|---|
| Contiene el texto, sin distinguir mayúsculas | BHIP (Hipotecario), RICH (Richmond), OEST (Concesionario). **No** encuentra "Molinos **Río** de la Plata" | nada |
| Lo anterior, sin distinguir acentos (`unaccent`) | lo anterior **y MOLI** | nada |
| Lo anterior, más parecido entre textos (`pg_trgm`) | lo anterior, ordenado por parecido | "telecon" → Telecom. "galisia" → **nada** con la configuración por defecto |

Probado en la copia local: `pg_trgm` puntúa el parecido de 0 a 1 y por defecto exige 0,60. "telecon" contra
"Telecom" da 0,75 y aparece. "galisia" contra "Grupo Financiero Galicia" da 0,50 y **no aparece**, salvo que se baje
el mínimo a 0,50 (con eso no se cuela nada más: el siguiente parecido es 0,25). O sea: la búsqueda tolerante a
errores no es gratis, hay que calibrar un número.

Ningún método encuentra "BCO DE VALORES…" buscando "banco", porque es una abreviatura. Se acepta como límite.

**Recomendación:** "contiene", sin distinguir mayúsculas ni acentos, sobre ticker y nombre, con las coincidencias de
ticker primero. La tolerancia a errores de tipeo queda como opción para el RFC, solo si se justifica la calibración.

Decisiones chicas que vienen con esta:

- **Excluir ARS** de los resultados: no se compran pesos con pesos.
- Un texto vacío o de 1 letra devuelve error 400.
- Un tope de resultados.
- Devolver también el último precio y la variación diaria, que es lo que muestra una pantalla de búsqueda (vacíos
  para PGR e IRCP).

### A-6. Envío de órdenes: casos borde

El criterio, en una línea fácil de defender:

- Si el pedido es **inválido por sí mismo** (falta un dato, una combinación imposible, un activo que no se opera):
  **400 y no se graba**.
- Si el pedido es válido pero **el estado de la cuenta o del mercado no permite ejecutarlo** (no alcanza la plata, no
  hay precio): **se graba como `REJECTED`**.

| Caso | Resultado |
|---|---|
| Se manda cantidad **y** monto, o ninguno de los dos | 400 |
| LIMIT sin precio, o MARKET con precio | 400 |
| Precio con más de 2 decimales | 400 (ver D-4) |
| Comprar o vender ARS | 400: los pesos no se operan como una acción |
| Usuario o instrumento inexistente | 404 |
| Pesos o acciones insuficientes | `REJECTED` (lo pide el enunciado) |
| El monto no alcanza para 1 acción (ej.: $500 en PAMP a $925,85) | `REJECTED` |
| MARKET sobre PGR o IRCP (sin precio) | `REJECTED` |
| LIMIT sobre PGR o IRCP | Se acepta como `NEW`: una LIMIT no necesita precio de mercado |

En una MARKET se graba como precio el último `close`, igual que en las órdenes de los datos.

### A-7. ¿Cancelación y transferencias por API?

- El enunciado define `CANCELLED` y la regla "solo se cancela lo que está `NEW`", pero no pide un endpoint.
  **Recomendación:** agregarlo como extra chico. Es una regla que escribieron ellos y prueba bien los cambios de
  estado de una orden.
- `CASH_IN` y `CASH_OUT`: el enunciado describe cómo están modelados, no pide un endpoint. **Recomendación:** fuera
  de alcance; los datos de prueba los cargan directamente.

## 7. Riesgos técnicos (para el RFC)

- **R-1. Dos órdenes al mismo tiempo.** El usuario 1 tiene $627.500 disponibles y manda dos compras de $600.000 a la
  vez. Las dos leen "$627.500", las dos pasan la validación y se gastan $1.200.000. Hay que procesar de a una las
  órdenes de un mismo usuario (con un bloqueo en la base). **Es el riesgo más serio del challenge.**
- **R-2. Reintentos.** Si la respuesta se pierde y el cliente reintenta, se compra dos veces. Opción: una clave que
  identifique cada pedido (idempotencia).
- **R-3. Redondeo.** Plata en `BigDecimal` con escala y modo de redondeo definidos. La cantidad por monto se trunca
  hacia abajo, nunca se redondea: redondear para arriba compra de más y deja la cuenta en negativo.
- **R-4. Rendimiento con costo cero.** Se divide por el costo; hay que definir qué se devuelve cuando es 0.
- **R-5. Base compartida.** Neon es modificable por cualquiera que tenga la URL. Los tests y la aplicación corren
  contra la copia local; Neon solo se usó para verificar.

## 8. Supuestos (si Cocos no responde, se trabaja con estos)

- **S-1.** Los precios ya están en pesos (todos los instrumentos son acciones locales): no hay conversión.
- **S-2.** "Último precio" es la fila más reciente de cada instrumento, sin importar la fecha de hoy.
- **S-3.** Las recomendaciones de A-1 a A-7.
- **S-4.** Las fechas existentes de `orders` están en hora argentina.
- **S-5.** Sin autenticación, como dice el enunciado: el usuario viene en el pedido.

## 9. Preguntas para Cocos

Listas para pegar en la respuesta al mail. Ninguna frena el trabajo: si no hay respuesta, se aplican los supuestos
de la sección 8.

1. **Pesos disponibles:** ¿las órdenes LIMIT en estado NEW deben descontarse de los pesos disponibles (y de las
   acciones disponibles, en el caso de una venta)? Nuestra idea es que sí.
2. **Rendimiento:** en el portfolio, ¿el "rendimiento total" es contra el precio promedio de compra? ¿Esperan además
   la variación diaria (close vs previousClose)?
3. **Orden 11:** en los datos, la orden 11 vende 30 BMA cuando el usuario tenía 20 y está FILLED, así que la
   posición queda en −10. ¿Es intencional? Nuestra idea es mostrar la posición como está, sin corregir el historial.
4. **Cancelación:** ¿esperan un endpoint para cancelar órdenes NEW, o alcanza con los tres pedidos?

## 10. Números de referencia (usuario 1)

Calculados con las recomendaciones de este documento. Son los valores esperados de los tests de aceptación.

| Concepto | Valor |
|---|---|
| Pesos (todas las órdenes `FILLED`) | $753.000,00 |
| Pesos reservados por compras `NEW` | $125.500,00 |
| **Pesos disponibles para operar** | **$627.500,00** |
| **Valor total de la cuenta** | **$889.756,00** |

| Activo | Cantidad | Último precio | Valor | PPC | Rendimiento total | Variación diaria |
|---|---|---|---|---|---|---|
| METR | 500 | $229,50 | $114.750,00 | $250,00 | −8,20 % | −1,08 % |
| PAMP | 40 | $925,85 | $37.034,00 | $930,00 | −0,45 % | +0,44 % |
| BMA | −10 | $1.502,80 | −$15.028,00 | — | — | −1,15 % |

Cuenta del total: 753.000 + 114.750 + 37.034 − 15.028 = 889.756.

Búsqueda (sin distinguir mayúsculas ni acentos):

- "banco" → BBAR, BHIP, BMA, BPAT.
- "rio" → BHIP, MOLI, OEST, RICH.

## 11. Qué sigue

- **PRD (fase 2):** convierte A-1 a A-7 en requisitos y criterios de aceptación.
- **RFC (fase 3):** resuelve R-1 a R-5, los cambios al esquema (E-1 a E-8), el mapeo de nombres de columnas (C-2) y
  la zona horaria (D-10).

## Anexo: consultas de verificación

Pesos del usuario 1 (todo `FILLED`) y reserva de compras `NEW`:

```sql
select
  sum(case when status = 'FILLED' then
        case side when 'CASH_IN'  then  size * price
                  when 'SELL'     then  size * price
                  when 'CASH_OUT' then -size * price
                  when 'BUY'      then -size * price end
      else 0 end)                                                        as pesos_filled,    -- 753000.00
  sum(case when status = 'NEW' and side = 'BUY' then size * price else 0 end) as reservado_new  -- 125500.00
from orders
where userid = 1;
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
