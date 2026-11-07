# 01 — Registro de decisiones del análisis

> Se completa a medida que se revisa [`01-analisis.md`](01-analisis.md) punto por punto. Al terminar la revisión,
> el análisis se reescribe incorporando estas decisiones. Cada decisión lleva su porqué.

## Glosario

- **Instrumento:** cada cosa que se puede tener en la cuenta (tabla `instruments`): 65 acciones y **ARS**, los
  pesos (tipo `MONEDA`). Los pesos son un instrumento para poder guardar depósitos y retiros como órdenes.
- **Orden:** una operación suelta, una fila de `orders`: comprar, vender, depositar o retirar.
- **Lado (`side`):** `BUY` (compra), `SELL` (venta), `CASH_IN` (depósito), `CASH_OUT` (retiro).
- **Tipo (`type`):** `MARKET`, al precio de mercado, se ejecuta en el momento; `LIMIT`, con un precio máximo de
  compra o mínimo de venta, queda pendiente.
- **Estado (`status`):** `NEW` (pendiente), `FILLED` (ejecutada), `REJECTED` (rechazada), `CANCELLED` (cancelada por
  el usuario). `FILLED`, `REJECTED` y `CANCELLED` son finales.
- **Transferencia:** movimiento de plata entre el banco y la cuenta del broker. `CASH_IN` es un depósito y
  `CASH_OUT`, un retiro. Se guardan como órdenes sobre ARS, con el monto en `size` y `price` = 1.
- **Posición o tenencia:** cuántas acciones de una acción tiene un usuario, sumando sus órdenes `FILLED` de esa
  acción. Es por usuario y por acción.
- **Pesos de la cuenta:** la plata en efectivo, calculada como un extracto bancario: depósitos − retiros − compras +
  ventas (órdenes `FILLED`). $753.000 para el usuario 1.
- **Reserva (o pesos comprometidos):** lo que bloquean las compras pendientes (`NEW`) a su precio límite. Las ventas
  pendientes reservan acciones (A-1).
- **Pesos disponibles para operar:** los pesos de la cuenta menos la reserva (A-1). $627.500 para el usuario 1.
- **Portfolio:** la foto de la cuenta: valor total, pesos disponibles y lista de posiciones. ARS no va en la lista:
  los pesos tienen su propio campo.
- **Valor de una posición:** cantidad × último precio.
- **Último precio:** el `close` de la fila más reciente de `marketdata` de esa acción (D-3). Una acción **cotiza** si
  tiene al menos una fila en `marketdata`.
- **Variación diaria:** cuánto se movió el precio en el último día: (`close` − `previousClose`) ÷ `previousClose`.
- **Precio promedio de compra (PPC):** el promedio de las compras de una posición, ponderado por cantidad; las ventas
  no lo cambian (A-2).
- **Rendimiento total:** cuánto se ganó o perdió contra lo pagado: (último precio − PPC) ÷ PPC (A-2).
- **Día sin operaciones:** un día en que nadie compró ni vendió una acción. No tiene apertura, máximo ni mínimo, y
  el cierre repite el último precio conocido (D-5).
- **Error vs. `REJECTED`:** si con lo enviado no se puede armar una orden, es un error y no se graba nada; si la orden
  está bien armada pero la cuenta o el mercado no dejan ejecutarla, se graba como `REJECTED` (A-6).
- **Cursor:** la marca que trae cada página del listado de órdenes para pedir la siguiente ("ver más") (A-7).

## Cerradas

### C-1 · Node.js vs Java: descartada

El mail de Cocos pide Java. No hay contradicción que resolver.

### C-2 · Nombres de columnas: nota técnica, no contradicción

La diferencia entre el enunciado (`previousClose`) y la base (`previousclose`) es solo de mayúsculas y minúsculas:
Postgres pasa a minúsculas los nombres que no van entre comillas. El código usa los nombres reales.

### C-3 a C-5 · Las otras contradicciones del análisis

- **C-3 · `marketdata` tiene `date`, no `datetime`:** el enunciado nombra una columna `datetime`; la base tiene
  `date`, de tipo fecha sin hora. Vale la base. Consecuencia: hay una fila por acción y por día, y "la fila más
  reciente" se elige por fecha (D-3).
- **C-4 · Rendimiento total vs. retorno diario:** cerrada en A-2. Son dos números distintos y se devuelven los dos.
- **C-5 · No se puede vender más de lo que se tiene, pero la orden 11 lo hizo:** cerrada en D-1.

### Preguntas a Cocos: no se envían

Cada ambigüedad se investiga, se decide y se justifica por escrito. Base: el enunciado pide *"Documentá cualquier
suposición o decisión de diseño que consideres relevante"*.

### Esquema: se mejora

El enunciado permite *"modificar, agregar o ajustar tablas"* si se justifica. Se van a proponer cambios de dos
tipos, cada uno con su porqué:

- **Integridad:** que la base no acepte datos imposibles (una orden sin usuario, `side = 'COMPRA'`).
- **Rendimiento:** índices para las consultas de los tres endpoints. Como con 11 órdenes no hay diferencia
  medible, se justifican midiendo con volumen (por ejemplo, un millón de órdenes de prueba en local, antes y
  después).

El detalle se trabaja en el bloque del esquema, que se hace en el RFC (fase 3).

### D-1 · Orden 11: venta de 30 BMA teniendo 20 (posición −10)

1. **El dato no se toca:** ni se borra, ni se edita, ni se agrega un movimiento compensatorio. Se documenta como
   dato heredado inconsistente. Si el negocio decidiera corregirlo, el camino sería un movimiento compensatorio
   (como en contabilidad), nunca borrar ni editar la orden. Esa decisión no le corresponde al desarrollador: no
   sabemos si la venta fue real o un error de carga.
2. **El portfolio la muestra tal cual:** −10 acciones, valor −$15.028, rendimiento vacío. Las posiciones en 0 no
   se muestran.
3. **La regla "no vender más de lo que se tiene" vive en el código:** validación antes de grabar, y las órdenes
   de un mismo usuario se procesan de a una. No puede ir en la base, por estos motivos:
   - Una restricción mira una sola fila, y la regla depende de la suma de muchas.
   - Una tabla de posiciones con "cantidad ≥ 0" no carga: BMA da −10.
   - Esa misma regla con `NOT VALID` (que solo se aplica de acá en adelante) bloquearía compras parciales: si el
     usuario compra 5 BMA, la fila pasaría de −10 a −5, la regla la rechazaría, y el usuario no podría comprar.
   - Un trigger duplicaría la lógica de la aplicación, escondida en la base y con el mismo problema de órdenes
     simultáneas.
4. **Hallazgo:** la orden 11 es el síntoma exacto de dos ventas simultáneas sin bloqueo. Con 20 acciones llegan
   dos ventas de 15; las dos ven "tiene 20", las dos pasan y la posición queda en −10. Se demuestra con un test:
   dos ventas de 15 en paralelo, una `FILLED` y otra `REJECTED`, posición final 5.
5. **Sin aviso especial** en la respuesta del portfolio: el −10 ya lo dice.

### Base de Neon: se trata como producción

- **Desarrollo y tests: siempre contra una copia local**, armada con `database.sql` más nuestras migraciones
  (archivos SQL numerados que se aplican en orden).
- **Neon no se toca mientras desarrollamos:** solo se lee, para verificar. En la fase 8, con todo probado, se
  evalúa aplicarle las mismas migraciones una vez, como un pase a producción.
- **Por qué:**
  - El mail pide que *"el proyecto tiene que poder ser ejecutado localmente"*.
  - Los tests graban órdenes. Contra Neon, cada corrida cambiaría la plata y las posiciones del usuario 1, y los
    tests dejarían de ser repetibles.
  - Si una migración falla en Neon, no hay un backup propio para volver.
- **Por qué no se descarta aplicarlas al final:** el mail dice que la base la crearon *"para que no lo tengas que
  hacer vos"*, y los contadores de ids intactos sugieren que es una base solo para este challenge.
- **Nota para el RFC:** la URL de la base se configura por variable de entorno, así la misma aplicación puede
  apuntar a la copia local o a Neon sin cambiar código.

### D-2 · Instrumentos sin precio (PGR e IRCP)

- **Qué pasa:** 63 de las 65 acciones tienen precio. PGR (id 3) e IRCP (id 10) no tienen ninguna fila en
  `marketdata`. ARS tampoco, pero es la moneda: vale 1 por definición.
- **Investigación:** las dos dejaron de cotizar antes de julio de 2023, la fecha de los datos.
  - IRCP fue absorbida por IRSA (fusión aprobada en diciembre de 2021).
  - En PGR, su accionista controlante (Mercuria) hizo en 2022 una oferta de compra a los minoritarios, también
    en Argentina, y la retiró de la bolsa de Londres en septiembre de 2022.
  - No se encontró la fecha exacta de retiro en BYMA. Conclusión: el dato es realista, no un error de carga.
- **Regla: sin cotización, no se opera.**
  - Orden MARKET: `REJECTED`, porque no hay precio al que ejecutarla.
  - Orden LIMIT: `REJECTED`. Si se aceptara, quedaría `NEW` para siempre y le reservaría plata al usuario sin fecha
    de fin. Los brokers reales rechazan órdenes sobre activos que no cotizan.
  - Búsqueda: aparecen, con el precio vacío (si la búsqueda devuelve precios; se decide en A-5).
  - Portfolio: con estas reglas no puede haber posiciones en estos activos. Si aparecieran por datos cargados a
    mano, se muestran sin valor (no suman al total) y el cálculo no se rompe.
- **Qué cuenta como "tener cotización":** tener al menos una fila en `marketdata` (cerrado en D-3).
- **Fuentes:**
  [El Cronista](https://www.cronista.com/negocios/reorganizacion-en-irsa-el-gigante-inmobiliario-absorbera-su-negocio-de-los-shoppings),
  [PR Newswire](https://prnewswire.com/news-releases/irsa-inversiones-y-representaciones-sa-nyse-irs-byma-irsa-released-a-material-fact-regarding-the-proposal-of-a-merger-with-irsa-propiedades-comerciales-sa-approved-by-its-board-of-directors-301389443.html),
  [LSE (oferta de salida)](https://www.lse.co.uk/rns/cancellation-exit-opportunity-notice-of-gm-jc2mie7rkqjbhgl.html),
  [LSE (salida de AIM)](https://www.lse.co.uk/news/in-brief-phoenix-global-resources-delists-from-londons-aim-p82o5kyri6uqkt4.html),
  [BBVA, Comunicado 11270](https://bbvafrances.com.ar/content/dam/public-web/argentina/documents/Comunicado_N_11270_-_Extension_de_Oferta_de_Compra_de_Phoenix_Global_Resources_91800.pdf).

### D-3 · Qué es "el último precio"

- **Qué pasa:** cada acción tiene 2 filas en `marketdata` (13/07/2023 y 14/07/2023). Ejemplo: PAMP cerró en
  $921,80 el 13/07 y en $925,85 el 14/07. Su último precio es $925,85.
- **Tres formas de equivocarse:**
  1. Tomar cualquier fila: la base puede devolver la del 13/07, y la orden se ejecuta a un precio viejo.
  2. Tomar la última fila cargada (id más alto): hoy funciona de casualidad, porque las filas del 14/07 tienen
     ids más altos. Si se cargan datos fuera de orden, se rompe sin que nadie lo note.
  3. Buscar el precio de la fecha actual: los datos son de 2023 y no aparece nada, así que todas las órdenes
     MARKET serían rechazadas.
- **Decisiones:**
  1. **El último precio es el `close` de la fila con la fecha más reciente de esa acción.** Vale igual para las
     órdenes MARKET, el valor del portfolio y la búsqueda.
  2. **Sin límite de antigüedad.** Con estos datos, todos los precios tienen 3 años; un límite haría que nada
     funcione. Queda documentado como limitación conocida: en producción, cuánto puede tener un precio para
     usarse lo define el negocio.
  3. **"Tener cotización" = tener al menos una fila en `marketdata`** (lo que quedó pendiente de D-2).
  4. **Para el bloque del esquema:** que no puedan existir dos filas de la misma acción en la misma fecha; que
     `close` sea obligatorio (hoy ninguna fila lo tiene vacío); un índice por (acción, fecha) para buscar la fila
     más reciente sin recorrer toda la tabla.
  5. **Test:** datos de prueba donde la fila cargada última no es la más reciente. El código tiene que elegir por
     fecha, no por orden de carga.

### D-4 · El `previousClose` de BBAR: dos problemas distintos

**Problema 1: el valor no coincide.** El `close` del 13/07 es $1.062,20, pero el `previousClose` del 14/07 es
$1.047,166. Las otras 62 acciones cuadran.

- **Hipótesis: es un precio ajustado por dividendo.** BBVA Argentina pagó dividendos en 2023 en 6 cuotas, y la
  cuota 2 tuvo fecha de corte el 17/07/2023. Con liquidación a 48 h, el 14/07 fue el primer día sin derecho al
  dividendo, justo la fila que no cuadra. En los datos de mercado es habitual ajustar ese día el cierre anterior,
  restándole el dividendo.
- **Lo que no cierra del todo:** la diferencia es de $15,03 por acción y el aviso de la cuota habla de unos $13,71.
  Puede deberse a que se pagó en bonos (LECER) valuados a precio de mercado. No se pudo confirmar.
- **Efecto:** la variación diaria da −0,87 % con la columna y −2,27 % con el cierre real del día anterior. Si la
  hipótesis es correcta, la buena es −0,87 %: unos 1,4 puntos de la caída son el dividendo que cobró el accionista.

**Problema 2: tiene 3 decimales y la base guarda 2.** $1.047,166 son 1.047 pesos con 16,6 centavos; nadie puede
pagar 0,6 centavos, así que es una cuenta, no un precio operado. La base lo guardó como $1.047,17 sin avisar.

- **Medición:** de los 624 números del archivo, 623 tienen ceros después del segundo decimal (`20.50000000`). Los 8
  decimales son el formato de exportación, no información real.
- **Pasos de precio:** todos los precios de más de $10 se mueven de a 5 centavos. Los únicos 10 valores que se
  mueven de a 1 centavo están por debajo de $10.

**Decisiones:**

1. **La variación diaria usa la columna `previousClose`** y no se recalcula con la fila del día anterior.
2. **El dato no se toca.** Se documenta la hipótesis del dividendo.
3. **Decimales, según el tipo de número:**
   - **Plata:** 2 decimales (el peso tiene centavos; estándar ISO 4217).
   - **Precios: las columnas se quedan en 2 decimales.** Ampliarlas no recupera el 1.047,166 (se redondeó al
     cargar, también en la base de Cocos) y no cambia ningún resultado. Limitación documentada: si se incorporan
     acciones de menos de $1 (BYMA permite pasos de 0,001) o precios ajustados con más decimales, se amplían juntas
     `marketdata` y `orders.price`. Ampliar no pierde datos.
   - **Cálculos** (precio promedio, porcentajes): más precisión por dentro y redondeo solo al final. El modo de
     redondeo se elige en el RFC.
4. **Un precio de orden con más de 2 decimales se rechaza (error 400):** no se redondea ni se corta, porque nunca
   se cambia lo que el usuario autorizó con su plata. No se valida el paso de 5 centavos: sería simular el mercado
   (el enunciado dice que no hace falta) y no se encontró la tabla oficial completa.
5. **Test:** la variación diaria de BBAR tiene que dar −0,87 %.

**Fuentes:**
[aviso de pago, cuota 2](https://www.latibex.com/docs/Documentos/esp/hechosrelev/2023/AVISO%20DE%20PAGO%20DIVIDENDOS%202023%20CUOTA%202_.pdf),
[cronograma en 6 cuotas](https://www.puentenet.com/contenido/noticias/bbva-argentina-cia-informa-cronograma-de-pago),
[BYMA, anexo de la circular 3569](https://data-widgets.byma.com.ar/wp-content/uploads/2024/07/BYMA-CIR3569-Anexo-v20240704.pdf),
[ISO 4217](https://www.iso.org/iso-4217-currency-codes.html).

### D-5 · Filas de `marketdata` con valores vacíos (DOME y POLL)

- **Qué pasa:** el 13/07, DOME (Domec) y POLL (Polledo) tienen vacíos `open`, `high` y `low`, con `close` igual a
  `previousClose` ($20,50 y $62,00).
- **Qué significa:** ese día nadie operó esas acciones. Sin operaciones no hay apertura, ni máximo, ni mínimo, y el
  cierre repite el último precio conocido. Es la forma habitual de representar un día sin operaciones, frecuente en
  acciones poco negociadas. No es un error.
- **Decisiones:**
  1. **El dato no se toca.**
  2. **No afecta los cálculos**, que solo usan `close` y `previousClose`. Si alguna vez se muestran `open`, `high` o
     `low`, van vacíos, nunca en 0: un 0 diría que la acción llegó a valer $0.
  3. **Para el bloque del esquema:** `open`, `high` y `low` siguen opcionales; `close` obligatorio (D-3);
     `previousClose` opcional, porque el primer día que cotiza una acción no tiene cierre anterior (la variación
     diaria va vacía). Candidatas a revisar: precios mayores a 0, mínimo ≤ máximo y cierre entre los dos. Los datos
     actuales cumplen las tres (verificado).
  4. **Test:** una acción cuyo último día no tuvo operaciones. Su último precio es el `close` y su variación diaria,
     0 %.

### D-6 · El precio de una orden vieja no sale de `marketdata`

- **Conceptos:**
  - Una **orden** es una operación suelta ("compré 50 PAMP a $930").
  - Una **posición** es todo lo que un usuario tiene de una acción: la suma de sus órdenes ejecutadas (`FILLED`) de
    esa acción. Es por usuario y por acción. Ejemplo: PAMP del usuario 1 = +50 (orden 2) − 10 (orden 4) = 40; las
    órdenes 3 (`CANCELLED`) y 6 (`REJECTED`) no cuentan.
  - En la vida real, una orden a mercado se ejecuta al precio de ese momento, que cambia durante el día. Por eso su
    precio puede diferir del cierre, pero tiene que estar entre el mínimo y el máximo del día.
- **Comparación de los precios de las órdenes con el mercado:**
  - **Orden 10** (METR a $250): ese día el máximo fue $232, así que hay un dato mal (la orden o los precios), sin
    forma de saber cuál. Tampoco coincide con otro día: cierre del 12/07 $229; 13/07 entre $222,50 y $232; 14/07
    entre $225,25 y $233.
  - **Órdenes 1 a 6** (12/07): no se pueden verificar. Del 12/07 solo se conoce el cierre (aparece como
    `previousClose` del 13/07; PAMP cerró en $893,65), no el mínimo ni el máximo.
  - **Órdenes 9 y 11** (BMA, 13/07): cuadran.
- **Por qué importa:** el costo. METR, que hoy vale $229,50, da −8,20 % con el precio pagado ($250) y −1,08 % si se
  tomara el cierre de ese día ($232), que además se confunde con la variación diaria. Y como `marketdata` guarda
  solo los últimos 2 días, la orden es el único registro de lo que se pagó.
- **Decisiones:**
  1. **El costo de una posición sale del precio grabado en cada orden**, nunca de `marketdata` (precio promedio de
     compra y rendimiento total).
  2. **Los precios viejos no se validan ni se corrigen.** La orden 10 se documenta como inconsistencia de los datos
     de ejemplo; las órdenes 1 a 6, como imposibles de verificar.
  3. **En las órdenes nuevas se graba el precio usado:** una MARKET guarda el `close` al que se ejecutó (coincide con
     el mercado por definición); una LIMIT guarda el precio límite del usuario, que puede ser distinto del mercado a
     propósito ("compro solo si baja a $220").
  4. **Test:** el precio promedio de compra de METR da $250 y su rendimiento, −8,20 %.
- **Pendiente para A-6:** ¿se acepta cualquier precio límite? Ejemplo: comprar 100 METR con máximo $10.000 cuando
  cotiza a $229,50. En un mercado real se ejecutaría enseguida a unos $229,50; acá quedaría `NEW` y, si las
  órdenes pendientes reservan plata (A-1), bloquearía $1.000.000 por una compra de $22.950. **Resuelto en A-6,
  parte 2:** se acepta cualquier precio mayor a 0 y se reserva a ese precio; con el usuario 1, esa orden queda
  `REJECTED` porque $1.000.000 supera sus $627.500 disponibles.

### D-7 · Una orden LIMIT que terminó `FILLED`

- **Ciclo de vida de una orden** (los estados del enunciado, juntos):
  - MARKET: se envía y queda `FILLED` (o `REJECTED` si no alcanza la plata o las acciones).
  - LIMIT: se envía y queda `NEW` (o `REJECTED` si no alcanza). Después pasa a `FILLED` cuando el mercado llega al
    precio (no lo simulamos) o a `CANCELLED` cuando el usuario la cancela.
  - `FILLED`, `REJECTED` y `CANCELLED` son finales.
- **En los datos aparecen todos los caminos:** MARKET → `FILLED` (2, 4, 10, 11); MARKET → `REJECTED` (6); LIMIT
  todavía `NEW` (5, 7); LIMIT → `CANCELLED` (3); **LIMIT → `FILLED` (9)**.
- **La trampa:** si el código cuenta como ejecutadas "las MARKET" (por tipo), se pierde la orden 9. BMA daría −30 en
  vez de −10, y los pesos del usuario 1, $783.800 en vez de $753.000.
- **Decisiones:**
  1. **Se cuenta por estado, no por tipo:** todo lo `FILLED` cuenta, sea MARKET o LIMIT.
  2. **El ciclo de vida queda documentado y el código lo hace cumplir:** solo se cancela una orden `NEW`; los estados
     finales no cambian.
  3. **Nuestro sistema nunca pasa una LIMIT a `FILLED`**, como parte de "no se simula el mercado". Si un proceso
     externo lo hiciera, el portfolio ya la contaría bien, porque cuenta por estado.
  4. **Para el bloque del esquema, junto con la D-9:** `price` significa dos cosas según el estado. En una LIMIT
     `NEW` es "lo máximo que pago"; en una `FILLED`, "lo que pagué". En un mercado real pueden diferir (una compra
     con límite $1.540 puede ejecutarse a $1.500).
  5. **Test:** una LIMIT `FILLED` cuenta en el portfolio: BMA da −10, no −30.

### D-8 · Usuarios sin movimientos

- **Qué pasa:** los usuarios 2, 3 y 4 existen pero no tienen ninguna orden.
- **La distinción importante:** "no tiene nada" no es lo mismo que "no existe". Un usuario sin movimientos es un caso
  normal; uno inexistente es un error.
- **Decisiones:**
  1. **Usuario sin órdenes:** portfolio con valor total $0, pesos disponibles $0 y lista vacía. Respuesta normal.
  2. **Usuario inexistente** (por ejemplo, el 99): error 404.
  3. **Sus compras y ventas: `REJECTED`**, porque no tienen plata ni acciones. Ejemplo: comprar 1 PAMP a mercado
     necesita $925,85 y tiene $0. Sigue el criterio de A-6: pedido bien armado, pero el estado de la cuenta no
     permite ejecutarlo.
  4. **Tests independientes:** cada test arma sus propios datos (su usuario con su depósito `CASH_IN`). Si todos
     usaran al usuario 1, se pisarían entre sí y el resultado dependería del orden en que corren. Los números de
     referencia del usuario 1 se verifican en tests que solo leen.
  5. **El email no se devuelve ni se loguea:** el portfolio no lo necesita y es un dato personal.
  6. **Test:** el portfolio del usuario 2 da $0 con la lista vacía, y el del usuario 99 da 404.

### D-9 · La columna `size` significa dos cosas

- **Qué pasa:** en una transferencia (ARS), `size` es la cantidad de pesos y `price` = 1. En una acción, `size` es
  la cantidad de acciones y `price`, el precio por acción. Ventaja: `size × price` da el monto en pesos de cualquier
  movimiento.
- **Problemas:**
  - Es fácil mezclar unidades: un reporte que sume `size` contaría el depósito de 1.000.000 como acciones.
  - El `price` = 1 de los pesos es una convención que nada protege: un depósito de $1.000 con `price` = 2 le daría
    al usuario $2.000.
  - `size` es entero, así que las transferencias no pueden tener centavos ($100,50 no se puede guardar).
  - `price` también significa dos cosas (D-7).
- **Opciones evaluadas:**
  - **A. Mantener el modelo y protegerlo.**
  - B. Agregar una columna de monto en pesos: duplica `size × price` en compras y ventas (dos datos que pueden
    contradecirse) y obliga a rellenarla en las órdenes existentes.
  - C. Pasar `size` a decimal: la base aceptaría 1,5 acciones, y el enunciado prohíbe fracciones.
  - D. Tabla aparte para transferencias: obliga a mover datos, y el enunciado calcula todo sobre `orders`.
- **Lo que pesa:** el enunciado pide el modelo así: *"utilizar todos los movimientos pertinentes que hay en la tabla
  orders, utilizando la columna size"*.
- **Decisión: A.**
  1. **Se mantiene el modelo del enunciado.** Lo que significa cada columna queda documentado (ver Glosario).
  2. **La separación se hace en el código:** cada fila se traduce una sola vez a "movimiento de acciones" (cantidad
     entera) o "movimiento de pesos" (plata con centavos), y después nunca se mezclan.
  3. **Para el bloque del esquema:** una regla en la base, "si es `CASH_IN` o `CASH_OUT`, el `price` es 1" (mira
     una sola fila; los datos cumplen: órdenes 1 y 8). "No se compra ni vende ARS" va en el código (A-6), porque
     necesita mirar otra tabla.
  4. **Limitación documentada:** las transferencias no pueden tener centavos. No afecta, porque ningún endpoint crea
     transferencias (A-7). Si entraran en alcance, se evalúa la opción B.
  5. **`price` con dos significados:** no se agrega una columna ahora. En nuestro sistema nunca chocan: en una MARKET,
     `price` es lo pagado; en una LIMIT `NEW`, el máximo. Supuesto documentado: en una orden `FILLED`, `price` es lo
     pagado, incluida la orden 9. Si algún día se ejecutaran LIMIT a mejor precio, se agrega una columna para el
     precio de ejecución.
  6. **Test:** ARS no aparece en la lista de posiciones, y los pesos del usuario 1 dan $753.000 calculados con
     `size × price`.

### D-10 · Zona horaria de las fechas de las órdenes

- **Qué pasa:** `orders.datetime` guarda fecha y hora sin zona horaria, y el servidor de Neon está en GMT. Si el
  sistema guardara la hora del servidor, una orden mandada a las 14:00 en Buenos Aires quedaría como 17:00, después
  del cierre del mercado.
- **Evidencia de que las fechas existentes están en hora argentina:** la orden 2 es una MARKET ejecutada a las
  12:31. Como hora argentina cae dentro del horario de mercado (en 2023, de 11 a 17); como UTC serían las 9:31 en
  Argentina, con el mercado cerrado. Con hora argentina, todas las MARKET ejecutadas caen en horario de mercado.
  (Corrige el argumento del análisis, que usaba la orden 7: es una LIMIT, y las LIMIT se pueden mandar antes de la
  apertura.)
- **Por qué importa:** para mostrar la hora correcta y para el orden de las órdenes, del que puede depender el precio
  promedio de compra (A-2). Ejemplo: comprar 10 a $100, vender 10 y comprar 10 a $200 da un promedio de $200; comprar
  10 a $100, comprar 10 a $200 y vender 10 da $150.
- **Opciones:** A, dejar la columna y escribir siempre hora argentina (simple, pero depende de la configuración del
  servidor); **B, pasar la columna a fecha y hora con zona** (`timestamptz`).
- **Decisiones (opción B):**
  1. **Supuesto documentado:** las fechas existentes están en hora argentina (UTC−3).
  2. **Para el bloque del esquema:** pasar `datetime` a fecha y hora con zona, interpretando las existentes como
     hora argentina. No cambia lo que dicen los datos: "12:31 en Buenos Aires" sigue siendo eso; ahora la base lo
     sabe. Argentina no tiene horario de verano desde 2009, así que la conversión no tiene ambigüedades.
  3. **Las órdenes nuevas guardan el momento exacto en que se reciben.** La API muestra la fecha con su zona (por
     ejemplo, `2023-07-12T12:31:20-03:00`); el formato exacto se define en el RFC.
  4. **El orden de las órdenes es por fecha y hora;** si dos coinciden, se desempata por id.
  5. **Test:** una orden mandada a una hora conocida se guarda y se devuelve con la hora y la zona correctas.

### A-1 · Los pesos disponibles descuentan las compras pendientes

- **Qué pasa:** el usuario 1 tiene $753.000 según su extracto y dos compras LIMIT pendientes (`NEW`): 50 LOMA a $710
  ($35.500) y 60 BMA a $1.500 ($90.000), o sea $125.500 comprometidos.
- **Por qué importa:** sin descontar, podría mandar 10 compras LIMIT de $700.000; cada una vería $753.000
  disponibles y todas serían aceptadas. Si el mercado las ejecutara, la cuenta quedaría en −$6.247.000: la misma
  plata usada diez veces.
- **Lo que dice el enunciado:** pide los pesos *"disponibles **para operar**"* y calcularlos con los movimientos
  *"**pertinentes**"*. La regla de usar solo `FILLED` aparece para *"valor de mercado, rendimiento y cantidad de
  acciones **de cada posición**"*, no para la plata disponible. Los brokers reales separan el saldo total del
  disponible para operar.
- **Decisiones:**
  1. **Pesos disponibles = pesos del extracto − compras pendientes:** $627.500 para el usuario 1.
  2. **Acciones disponibles para vender = posición − ventas pendientes.** Ejemplo: con 40 PAMP y una venta pendiente
     de 30, solo se pueden vender 10 más.
  3. **El valor total usa los pesos totales**, incluida la plata reservada, que sigue siendo del usuario: $753.000 +
     $136.756 de posiciones = $889.756. Por eso valor total ≠ disponibles + posiciones.
  4. **El portfolio muestra también lo comprometido** ($125.500), para que la cuenta cierre a la vista. Es un campo
     que el enunciado no pide, pero explica la diferencia.
  5. **Las órdenes nuevas se validan contra lo disponible.** Cancelar una orden pendiente (A-7) libera su reserva.
  6. **Test:** el usuario 1 quiere comprar por $700.000. Sin reserva pasaría (700.000 < 753.000); con reserva queda
     `REJECTED` (700.000 > 627.500).

### A-2 · Qué rendimiento muestra el portfolio

- **Son dos números distintos y el enunciado nombra los dos:**
  - **Rendimiento total:** cuánto se ganó o perdió con lo que se tiene, contra lo pagado. Sale de las órdenes
    (*"para calcular… rendimiento… usar las órdenes en estado FILLED"*).
  - **Variación diaria:** cuánto se movió el precio en el último día, con `close` y `previousClose` (*"para calcular
    el retorno diario utilizar las columnas close y previousClose"*). Es igual para todos los que tienen la acción.
- **Métodos evaluados para el costo** (PAMP: compró 50 a $930, vendió 10 a $940, le quedan 40 que valen $37.034):
  - **Precio promedio de compra (PPC):** costo 40 × $930 = $37.200, rendimiento **−0,45 %**. Mira solo lo que se tiene
    hoy, que es lo que significa "rendimiento de la posición".
  - Plata neta puesta (compras − ventas): $37.100, −0,18 %. Confuso: una venta con ganancia achica el costo.
  - Todo junto, con lo ya vendido: −0,14 %. Es otra métrica, el resultado histórico de la acción.
  - FIFO (la primera acción comprada es la primera vendida): con estos datos da igual que el PPC, porque cada acción
    tiene una sola compra.
- **Validación del PPC:**
  - El método existe y es estándar: promedio de las compras ponderado por cantidad, y **vender no cambia el promedio
    de las acciones que quedan**. Lo confirman Vanguard, H&R Block y la autoridad fiscal de Dinamarca, que lo usa por
    ley para calcular ganancias de acciones.
  - La alternativa es FIFO, y para impuestos depende del país. En EE. UU., las acciones usan FIFO por defecto y el
    promedio se reserva sobre todo para fondos. Para Argentina, una fuente secundaria dice que ARCA acepta los dos; no
    se verificó con una fuente oficial.
  - **Los métodos difieren cuando hay varias compras a distintos precios.** Ejemplo: comprar 10 a $100 y 10 a $200,
    y vender 5, con precio actual $180. Con PPC (promedio $150) el rendimiento es 20 %. Con FIFO (se venden 5 de las de
    $100; quedan 5 a $100 y 10 a $200, promedio $166,67) es 8 %.
  - No se encontró documentación de Cocos sobre cómo muestra el precio promedio en su app. Verificable mirando una
    tenencia en la app.
  - Sin comisiones: el modelo de datos no las tiene, así que el PPC es solo sobre precios.
- **Decisiones:**
  1. **Rendimiento total contra el precio promedio de compra:** (último precio − PPC) ÷ PPC.
  2. **Variación diaria como campo aparte**, con `close` y `previousClose`.
  3. **El PPC se calcula en orden cronológico:** cada compra lo actualiza y las ventas no lo cambian. Cuando la
     posición llega a 0 se reinicia sola, porque la fórmula lo hace: (0 × promedio + cantidad × precio) ÷ cantidad =
     precio de la nueva compra. Con posición negativa (BMA) la fórmula no tiene sentido: rendimiento vacío, y si
     vuelve a positivo, el promedio arranca de nuevo desde esa compra.
  4. **Campos que el enunciado no pide pero explican los números:** último precio, PPC y variación diaria. Los
     obligatorios son cantidad, valor y rendimiento total.
  5. **Casos borde:** posición ≤ 0, rendimiento vacío. El costo cero queda imposible con la restricción de precio > 0
     del esquema, y eso resuelve el riesgo de dividir por cero (R-4).
  6. **Tests:** METR da −8,20 %; PAMP, −0,45 % (no −0,18 %); compra 10 a $100, compra 10 a $200 y venta 5 da un PPC de
     $150.
- **Fuentes:**
  [Vanguard](https://investor.vanguard.com/investor-resources-education/article/cost-basis-and-taxes),
  [H&R Block](https://www.hrblock.com/tax-center/income/investments/average-cost-basis/),
  [Skat, autoridad fiscal de Dinamarca](https://skat.dk/en-us/borger/aktier-og-andre-vaerdipapirer/skat-af-aktier/gevinst-og-tab-opgoeres-efter-gennemsnitsmetoden),
  [Janus Henderson](https://www.janushenderson.com/en-us/investor/resources/planning/tax/calculate-average-cost/),
  [Finantres](https://finantres.com/calculadora-de-promedio-de-acciones/),
  [Hacé Cuentas](https://hacecuentas.com/calculadora-precio-promedio-compra-cripto).

### A-3 · Posición negativa

Cerrada en D-1: la posición de BMA (−10) se muestra tal cual, con rendimiento vacío, y el dato no se corrige.

### A-4 · Las posiciones se calculan desde `orders`, no se guardan

- **Qué hay que decidir:** dónde vive la posición (cuántas acciones de cada empresa tiene un usuario). Puede
  calcularse en cada pedido sumando sus órdenes, o guardarse en una tabla de posiciones que cada orden ejecutada
  actualiza.
- **Lo que dice el enunciado:** empuja para los dos lados. *"Cuando una orden es ejecutada, se tiene que actualizar
  el listado de posiciones del usuario"* suena a tabla. *"Para hacer el cálculo de la tenencia y pesos disponibles
  utilizar todos los movimientos pertinentes que hay en la tabla `orders`"* suena a calcular. Y permite agregar
  tablas *"para mejorar la performance del código, optimizar consultas"*, así que una tabla sería válida si se
  justifica.
- **El camino: pensamos en una tabla de posiciones, medimos, y con un índice alcanza.**
  - **Lo que daría la tabla:** leer el portfolio costaría lo mismo aunque el usuario tenga años de historia.
  - **Lo que costaría:**
    1. No evita leer las órdenes: las reservas (A-1) salen de las órdenes `NEW`, y el PPC (A-2) habría que
       guardarlo también y recalcularlo en cada compra. Suma un segundo lugar sin sacar el primero.
    2. El mismo dato queda en dos lugares, que pueden diferir. Ejemplo: si se corrige una orden a mano, o si otro
       sistema ejecuta una LIMIT (nosotros no las ejecutamos, D-7) y no actualiza la tabla, el portfolio muestra
       una cantidad vieja.
    3. Habría que cargarla con lo que ya existe, incluido BMA −10, así que no admitiría la regla "cantidad ≥ 0"
       (D-1).
    4. Más código y más tests: cada cambio de estado de una orden tiene que tocar las dos tablas, siempre juntas.
  - **La pregunta que decidía:** ¿calcular es lento? Se midió en una copia local descartable (Postgres 18, con
    `database.sql`), con 1.010.011 órdenes inventadas: 10.000 usuarios con unas 100 cada uno y un usuario "pesado"
    con 10.000. Consulta medida: lo que necesita el portfolio, todas las órdenes `FILLED` y `NEW` del usuario en
    orden cronológico.

    | | Usuario típico (101 órdenes) | Usuario pesado (10.000) |
    |---|---|---|
    | Sin índice | 24 ms | 26 ms |
    | Con índice por usuario | **0,1 ms** | **3 ms** |

    Sin índice, la base lee la tabla entera (82 MB) en cada pedido, así que el tiempo depende de las órdenes de
    todos los usuarios: por eso el típico y el pesado tardan casi lo mismo. Con índice (7 MB), depende solo de las
    órdenes de ese usuario. Son números de una laptop, con datos inventados y ya en memoria: sirven como orden de
    magnitud. El RFC repite la medición formal, antes y después.
  - **Conclusión:** con el índice, calcular cuesta milisegundos aun con 10.000 órdenes. La tabla resolvería un
    problema que no tenemos, a cambio de los cuatro costos de arriba. El índice da la mejora de performance que el
    enunciado sugiere sin tener el dato copiado en dos lugares.
- **Otras opciones, descartadas:**
  - **Una "foto" que la base recalcula cada tanto** (vista materializada): entre foto y foto muestra datos viejos, y
    una compra recién ejecutada no aparecería. Choca con "actualizar el listado" y con validar contra lo disponible.
  - **Una foto a una fecha más las órdenes posteriores:** sería el camino si algún día hay usuarios con cientos de
    miles de órdenes. Queda documentada como evolución posible, sin hacerla.
- **Decisiones:**
  1. **Las posiciones no se guardan: se calculan desde `orders` en cada pedido.** Cantidades, pesos y PPC salen de
     las órdenes `FILLED`; las reservas, de las `NEW`. El dato vive en un solo lugar.
  2. **"Actualizar el listado de posiciones" se cumple solo,** porque el listado se arma con las órdenes del momento.
  3. **Para el bloque del esquema:** índice en `orders` por usuario, justificado con esta medición. Las columnas
     exactas (por ejemplo, usuario + fecha) se definen ahí.
  4. **Sin tabla de posiciones no aparece el problema de "cantidad ≥ 0"** con BMA −10: queda coherente con D-1.
  5. **La foto a una fecha queda como evolución posible.** Se usaría si el portfolio supera el tiempo de respuesta
     objetivo que fije el RFC.
  6. **La concurrencia de D-1 no cambia:** las órdenes de un mismo usuario se procesan de a una. El mecanismo se
     elige en el RFC.
  7. **Test funcional:** el usuario 1 compra 10 PAMP a mercado ($925,85, o sea $9.258,50). El siguiente portfolio
     muestra 50 PAMP, PPC $929,17 ((40 × $930 + 10 × $925,85) ÷ 50) y pesos disponibles por $618.241,50. El valor
     total sigue en $889.756: comprar a precio de mercado no cambia el total, solo pasa plata de pesos a acciones.
  8. **Esta decisión es candidata a ADR en la fase 3** ("posiciones calculadas, no guardadas"), con la medición
     formal.

### A-5 · Qué significa "similar" en la búsqueda

- **Lo que dice el enunciado:** *"devolver el listado de activos similares a la búsqueda realizada… (tiene que
  soportar búsqueda por ticker y/o por nombre)"*. No define "similar".
- **Un solo texto de búsqueda, que se busca en ticker y nombre a la vez,** como la caja de búsqueda de una app. El
  usuario no tiene que saber si escribe un ticker o un nombre: "pamp" encuentra PAMP por ticker; "pampa holding",
  por nombre. El "y/o" se cumple porque el mismo texto busca en los dos. Dos campos separados obligarían a elegir
  antes de buscar.
- **Niveles evaluados** (probados en la copia local con los 65 activos):

  | Nivel | Qué encuentra | Qué se le escapa |
  |---|---|---|
  | 1. Contiene, sin importar mayúsculas | "rio" → BHIP (Hipotecario), OEST (Concesionario), RICH (Laboratorios) | MOLI, "Molinos **Río**", por el acento |
  | 2. + sin importar acentos | "rio" suma MOLI; "compania" → INTR (Compañía); "zorraquin" → GARO | "grupo galicia": hay una palabra en el medio ("Grupo **Financiero** Galicia") |
  | 3. + palabra por palabra, en cualquier orden | "grupo galicia" → GGAL; "macro banco" → BMA; "gas sur" → CGPA2 y TGSU2 | errores de tipeo ("telecon") |
  | 4. + tolerancia a errores (`pg_trgm`) | "telecon" → Telecom (parecido 0,75) | "galisia" → nada: parecido 0,50 contra el mínimo por defecto de 0,60 |

- **Por qué los niveles 1 a 3 y no el 4:** los tres primeros cubren cómo busca la gente (el principio del ticker,
  una palabra del nombre o varias) y tardan 0,085 ms sobre los 65 activos. El 4 tiene un costo escondido: elegir
  cuánto parecido alcanza. Con el número por defecto, "galisia" no aparece, y calibrarlo con 65 activos es adivinar.
- **Decisiones:**
  1. **"Similar" = el ticker o el nombre contienen lo buscado,** palabra por palabra (todas tienen que aparecer, en
     cualquier orden), sin importar mayúsculas ni acentos.
  2. **Un solo texto de búsqueda.**
  3. **La tolerancia a errores de tipeo queda fuera,** documentada como mejora posible: usarla solo cuando la
     búsqueda normal no encuentra nada, al estilo "¿quisiste decir…?".
  4. **ARS no aparece:** no se compran pesos con pesos.
  5. **Texto vacío o solo espacios: error 400.**
  6. **Una sola letra se permite.** Cambia lo que proponía el análisis (400): "y" es una búsqueda válida y YPFD sale
     primero. El tope de resultados evita traer todo.
  7. **Texto muy largo: error 400** pasando un máximo que se fija en el RFC (el nombre más largo tiene 42 letras).
  8. **Tope de resultados configurable** (por ejemplo, 20): con "a" coinciden 59 de 65.
  9. **Orden:** ticker igual → ticker que empieza así → ticker que lo contiene → nombre que empieza así → el resto.
     Los empates, por ticker. Ejemplo "pa": PAMP y PATA, después BPAT y CGPA2, al final INTR ("Com**pa**ñía"). El
     empate por ticker da el mismo orden en Neon y en local, que ordenan los textos distinto (E-7).
  10. **`%` y `_` se buscan como texto común.** Para la base son comodines: sin tratarlos, buscar "_" devuelve los
      65.
  11. **Qué devuelve:** id, ticker, nombre, último precio y variación diaria. El id hace falta para mandar una orden;
      el precio es lo que muestra una pantalla de búsqueda. Precio y variación quedan vacíos para PGR e IRCP (D-2).
  12. **Para el bloque del esquema:** extensión `unaccent` (disponible en Neon, no instalada). En la copia local se
      instala con una migración; en Neon, se decide en la fase 8. Con 65 activos la búsqueda no necesita índice; se
      evalúa ahí midiendo con volumen, como en A-4.
  13. **Tests:** "rio" incluye MOLI; "grupo galicia" devuelve GGAL; "pa" devuelve PAMP primero; "pesos" no devuelve
      nada; "_" no devuelve nada; un texto vacío da 400.
- **Limitaciones aceptadas:**
  - "banco" no encuentra VALO: en los datos figura como "BCO DE VALORES" (abreviado).
  - "bbva" no encuentra BBAR: en los datos figura como "Banco Frances", su nombre anterior.
  - "banco sa" no encuentra "Banco Macro S.A.": los puntos de "S.A." cuentan.
  - No se toleran errores de tipeo.

### A-6 · Envío de órdenes: casos borde

#### Parte 1 · El criterio: error o `REJECTED`

- **Qué hay que decidir:** cuando una orden no se puede hacer, se puede contestar con un **error** (no se graba nada)
  o grabarla como **`REJECTED`** (queda en el historial del usuario). El enunciado da un solo ejemplo: *"si la orden
  enviada es por un monto mayor al disponible, la orden tiene que ser rechazada y guardarse en estado REJECTED"*.
- **La pista del enunciado:** `REJECTED` es *"cuando la orden es rechazada **por el mercado**"*. Primero tiene que
  existir una orden que llegue al mercado.
- **El criterio:**
  - **Si con lo que mandó el usuario no se puede armar una orden → error, no se graba.** Falta un dato, la
    combinación es imposible, el activo no existe o no se opera. Como un formulario de transferencia sin número de
    cuenta: el banco lo devuelve en la ventanilla.
  - **Si la orden está bien armada pero la cuenta o el mercado no dejan ejecutarla → `REJECTED`, se graba.** No
    alcanzan la plata o las acciones, o el activo no cotiza. Como una transferencia bien hecha sin fondos: queda en
    el historial como rechazada.
  - En los datos ya hay un caso: la orden 6 vendía 100 PAMP teniendo 40 y está grabada como `REJECTED`.
- **Casos que dan error (no se graba):**

  | Caso | Ejemplo | Por qué |
  |---|---|---|
  | Falta un dato o tiene un valor inválido | sin activo; `side` = "COMPRA" | no hay orden que armar |
  | Cantidad y monto juntos, o ninguno | | el enunciado pide uno de los dos |
  | Cantidad no entera, o 0 o menos | 2,5 acciones | *"no se admiten fracciones de acciones"* |
  | Monto de 0 o menos, o con más de 2 decimales | $100,555 | la plata tiene 2 decimales (D-4) |
  | LIMIT sin precio | | el enunciado lo exige |
  | MARKET con precio | | ignorarlo en silencio engaña: el usuario cree que puso un tope y se ejecuta al precio de mercado |
  | Precio de 0 o menos, o con más de 2 decimales | $229,555 | D-4 y A-2 |
  | Comprar o vender ARS | | los pesos entran y salen con transferencias, no se compran ni se venden |
  | `side` = `CASH_IN` o `CASH_OUT` | | este envío es de compra o venta; las transferencias, en A-7 |
  | Por monto, la cantidad da 0 | $500 en PAMP a $925,85 | una orden de 0 acciones no es una orden y nunca llegaría al mercado; una app muestra "cantidad: 0" y no deja enviar |
  | Por monto, a mercado, sobre un activo sin precio | $10.000 en PGR | sin precio no se puede calcular la cantidad: no hay orden que armar |
  | El usuario o el activo no existen | usuario 99 | ni se podría grabar: la base exige que existan |

- **Casos que quedan `REJECTED` (se graba):**

  | Caso | Ejemplo |
  |---|---|
  | No alcanzan los pesos, contando lo reservado (A-1) | usuario 1 compra por $700.000 con $627.500 disponibles |
  | No alcanzan las acciones, contando las ventas pendientes | vender 50 PAMP teniendo 40, como la orden 6 |
  | El activo no cotiza, MARKET o LIMIT, por cantidad | 10 PGR (D-2). El análisis original decía que una LIMIT sobre PGR quedaba `NEW`; D-2 lo cambió a `REJECTED` |

- **Decisiones:**
  1. **El criterio de arriba,** con los casos de las dos tablas.
  2. **Un `REJECTED` no es un error de la API:** la orden se creó, rechazada. La respuesta devuelve la orden con su
     estado, igual que una `FILLED` o una `NEW`. El código HTTP exacto, en el RFC.
  3. **La respuesta explica el motivo del rechazo** ("no alcanzan los pesos", "el activo no cotiza"). Guardarlo en la
     base es candidato para el bloque del esquema, con recomendación de hacerlo: sin eso, nadie puede responder
     después "¿por qué me rechazaron?".
  4. **"No existe":** 404 solo si lo que no existe está en la dirección del pedido (por ejemplo, `/users/99/...`); si
     viene en los datos enviados, 400. Las direcciones, en el RFC.
  5. **Para el bloque del esquema:** como una orden de 0 acciones nunca se graba, **cantidad ≥ 1 vale para todas las
     órdenes**. El precio vacío solo aparece en una `REJECTED` a mercado sobre un activo sin cotización.
  6. **Tests:** la compra de $700.000 queda `REJECTED` y `orders` tiene una fila más; una LIMIT sin precio da 400 y
     `orders` no cambia; $500 en PAMP da 400; 10 PGR a mercado queda `REJECTED` con el precio vacío; vender 50 PAMP
     teniendo 40 queda `REJECTED`.

#### Parte 2 · Órdenes por monto y precio límite

**Órdenes por monto**

- **Lo que dice el enunciado:** *"Permitir al usuario enviar la cantidad de acciones exactas o un monto total de
  inversión en pesos (en este caso, calcular la cantidad de acciones **máximas** que puede enviar, no se admiten
  fracciones de acciones)"*.
- **La cuenta:** cantidad = monto ÷ precio, **cortando los decimales**, nunca redondeando. El precio es el de la
  orden:

  | Orden | Precio que se usa | Ejemplo: $10.000 en PAMP |
  |---|---|---|
  | MARKET | el último `close` ($925,85) | 10,80 → **10 acciones**, cuestan $9.258,50; los $741,50 que sobran siguen disponibles |
  | LIMIT | el precio límite del usuario | límite $900: 11,11 → **11 acciones**; se reservan $9.900 |

- **Por qué cortar y no redondear:** redondeando, 10,80 pasa a 11 acciones, que cuestan $10.184,35: más de lo que el
  usuario autorizó, y podría dejar la cuenta en negativo.
- **Por qué la LIMIT usa su límite y no el `close`:** el límite es lo máximo que el usuario paga. Calculando con él,
  ni en el peor caso (11 × $900 = $9.900) se pasa del monto.
- **Ventas por monto, misma regla:** "vender $10.000 de PAMP" da 10 acciones ($9.258,50). El enunciado pide la
  cantidad *"máxima"* que entra en el monto, así que se corta para abajo en compras y en ventas. Otra lectura posible
  ("quiero sacar al menos $10.000") redondearía para arriba en las ventas; se descarta por la palabra "máxima" y para
  tener una sola regla.
- **Después de calcular, la orden se valida como cualquier otra** (A-1): el usuario 1, con $627.500 disponibles,
  compra por $630.000 de PAMP. Salen 680 acciones, que cuestan $629.578: no alcanza, y queda **`REJECTED`**. No se
  le compra "lo que entre" en sus pesos: el enunciado dice que una orden por más de lo disponible se rechaza.

**¿Se acepta cualquier precio límite? (lo que quedó pendiente en D-6)**

- **El problema:** comprar 10 METR con máximo $10.000 cuando cotiza a $229,50. En un mercado real se ejecuta enseguida,
  a unos $229,50, y se pagan $2.295. Acá queda `NEW` (el enunciado: una LIMIT queda `NEW` y no se simula el mercado)
  y **reserva $100.000** (10 × $10.000).
- **Qué hace el mercado real:** según la circular 3569 de BYMA (2018), al ingresar una orden el sistema bloquea las
  compras cuyo precio supera el precio de referencia (el último operado o, si no hay, el cierre) en más de un
  porcentaje, y las ventas que quedan por debajo. **Lo que no se pudo verificar:** el porcentaje está en un anexo que
  no se pudo abrir (el sitio de BYMA tiene un problema de certificado y la descarga se bloqueó por seguridad). Hay
  una versión del anexo de 2024 (la citada en D-4), así que la circular parece seguir en uso, pero no se pudo
  confirmar que la regla de las bandas siga igual. La circular se leyó a través del resumen de un buscador.
  Fuente: [BYMA, circular 3569](https://data-widgets.byma.com.ar/wp-content/uploads/2018/11/BYMA-CIR03569-ESP.pdf).
- **Opciones evaluadas:**

  | Opción | Qué hace | A favor | En contra |
  |---|---|---|---|
  | **A. Se acepta cualquier precio mayor a 0** | queda `NEW` y reserva al precio límite | simple; respeta el enunciado; reservar al límite es honesto, porque es lo máximo que el usuario dijo que paga | una orden absurda bloquea plata hasta que el usuario la cancela |
  | B. Se rechaza un límite muy lejos del precio actual | `REJECTED` si se aleja más de un X % del `close` | se parece a lo que hace BYMA | X sería inventado (no tenemos el porcentaje real), y agrega una regla de mercado que el enunciado dice que no hace falta simular |
  | C. Si el límite "cruza" el precio actual, se ejecuta en el momento | queda `FILLED` al `close` | es lo que pasaría en la realidad | contradice el enunciado: una LIMIT queda `NEW` |

- **Decisión: A.** Mismo criterio que la tolerancia a errores de tipeo en A-5: no se agrega una regla que dependa de
  un número que no podemos justificar.
- **La A se protege sola:** si el usuario 1 manda **100** METR con máximo $10.000, la reserva sería $1.000.000; con
  $627.500 disponibles, queda **`REJECTED`**. Reservar al precio límite impide que una orden absurda comprometa plata
  que no hay.

**Decisiones**

1. **Por monto, cantidad = monto ÷ precio, cortado para abajo.** El precio es el `close` en una MARKET y el límite en
   una LIMIT. La misma regla vale para compras y ventas.
2. **La orden calculada se valida contra lo disponible, como cualquier otra.** No se compra "lo que entre".
3. **Se graba la cantidad calculada** en `size`, con el precio de D-6 (el `close` en una MARKET, el límite en una
   LIMIT), **y la respuesta la muestra**, para que el usuario vea cuántas acciones salieron. El monto pedido no se
   guarda: la base no tiene dónde, y no hace falta para ningún cálculo.
4. **Se acepta cualquier precio límite mayor a 0,** con 2 decimales como máximo (parte 1), y se reserva a ese precio.
5. **El control de bandas de precio de BYMA queda documentado como mejora posible** (opción B), con la fuente. La
   ejecución inmediata (opción C) se descarta porque contradice el enunciado.
6. **El riesgo R-3 (redondeo) queda resuelto:** se corta para abajo y se usan decimales exactos (`BigDecimal`).
7. **Tests:**
   - $10.000 en PAMP a mercado dan 10 acciones a $925,85.
   - $9.258,50 dan exactamente 10 acciones, no 9 (el caso del borde: la división es exacta).
   - $10.000 con límite $900 dan 11 acciones.
   - $630.000 en PAMP del usuario 1 quedan `REJECTED`.
   - 100 METR con límite $10.000 quedan `REJECTED`.
   - 10 METR con límite $10.000 quedan `NEW` y reservan $100.000: los disponibles del usuario 1 bajan de $627.500 a
     $527.500.

### A-7 · Cancelación, listado de órdenes y transferencias

El enunciado pide tres endpoints: portfolio, búsqueda y envío de órdenes. Esta decisión agrega **dos endpoints que
no pide** (cancelar y listar órdenes) y deja afuera las transferencias. Cada cosa, con su porqué.

#### Cancelar una orden: sí, como extra chico

- **Lo que dice el enunciado:** define `CANCELLED` (*"cuando la orden es cancelada por el usuario"*) y la regla
  *"Solo se pueden cancelar las ordenes con estado `NEW`"*, pero no pide un endpoint.
- **Por qué hace falta, más allá de la regla:**
  - **Las reservas de A-1:** las órdenes pendientes reservan plata, y en nuestro sistema una orden `NEW` nunca se
    ejecuta (no se simula el mercado). Sin cancelación, esa plata queda bloqueada **para siempre**.
  - **La opción A de A-6, parte 2:** se acepta cualquier precio límite, y su contra es que *"bloquea plata hasta que
    el usuario la cancela"*. Sin cancelación, ese argumento se cae.
  - **Los datos ya tienen una cancelación:** la orden 3 está `CANCELLED`. El modelo de Cocos la contempla.
- **Ejemplo con el usuario 1:** tiene dos compras pendientes, la orden 5 (50 LOMA, $35.500) y la 7 (60 BMA,
  $90.000). Hoy le quedan $627.500 disponibles, y una compra por $700.000 queda `REJECTED` (test de A-1). Si cancela
  la orden 7, los disponibles suben a **$717.500** y esa compra pasa.
- **Los casos:**

  | Pedido | Resultado |
  |---|---|
  | Cancelar la orden 7 (`NEW`) | queda `CANCELLED`; se libera la reserva |
  | Cancelar la orden 3 (ya `CANCELLED`) | error de conflicto: "la orden ya está CANCELLED"; no cambia nada |
  | Cancelar la orden 2 (`FILLED`) o la 6 (`REJECTED`) | error de conflicto: ya no está pendiente |
  | Cancelar la orden 999 | 404 (está en la dirección del pedido: A-6, parte 1, decisión 4) |
  | Cancelar una orden de otro usuario | 404, como si no existiera: no se le confirma a nadie que esa orden existe |

- **Dos casos borde:**
  - **Dos cancelaciones de la misma orden al mismo tiempo:** el cambio de estado se hace en un solo paso y solo si la
    orden sigue `NEW`. Una gana; la otra recibe el error de conflicto. Nunca ganan las dos.
  - **Reintento** (se perdió la respuesta y el cliente vuelve a cancelar): recibe "ya está CANCELLED". Es inofensivo y
    le dice lo que pasó, así que no hace falta una clave de idempotencia como en el envío (R-2).
- **Lo que la base no guarda: cuándo se canceló.** `datetime` es el momento de creación. Candidato para el bloque del
  esquema, junto con el motivo del rechazo de A-6 (las dos cosas son "historia de la orden"), sin la misma fuerza de
  recomendación que el motivo.

#### Listar las órdenes del usuario: sí, mínimo

- **El camino:** primero lo dejamos afuera por "lo simple gana". Al revisarlo, una cancelación sin listado queda a
  medias: para cancelar hay que saber el id de la orden, y la única forma de conocerlo sería haber guardado la
  respuesta del envío. Ninguna app de inversiones funciona así; la de Cocos tiene una pantalla de órdenes con un botón
  para cancelar.
- **Por qué conviene:**
  1. **Completa el circuito de la cancelación.**
  2. **Le sirve a quien evalúa:** el enunciado dice *"la orden quedará grabada en la tabla `orders` con el estado y
     valores correspondientes"*. Con el listado se comprueba desde la API, sin abrir la base: enviar, ver `NEW`,
     cancelar, ver `CANCELLED`. El mismo recorrido sirve para la colección de Postman.
  3. **Le da sentido al motivo del rechazo de A-6:** sin listado, nadie lo podría consultar por la API.
  4. **Cuesta poco:** una consulta de solo lectura sobre `orders` por usuario, con el índice de A-4.
- **En contra, siendo realistas:** más alcance (5 endpoints en vez de 3, cada uno con tests y documentación). El riesgo
  real no es el endpoint, sino que crezca: filtros por fecha o por activo, opciones de orden, detalle por id. Por eso
  se mantiene mínimo a propósito. La frase del enunciado *"Documentá cualquier suposición o decisión de diseño"* no
  justifica sumar alcance por sí sola; lo justifica el circuito de la cancelación, y la frase obliga a dejarlo escrito.
- **Opciones evaluadas:**

  | Opción | Qué es | A favor | En contra |
  |---|---|---|---|
  | A. Sin listado | solo la cancelación | menos alcance | la cancelación queda a medias |
  | B. Las pendientes dentro del portfolio | el portfolio ya calcula las pendientes para la reserva (A-1) | no suma un endpoint | mezcla dos cosas, no muestra el historial (rechazadas, ejecutadas) y no se puede paginar |
  | **C. Endpoint de listado, mínimo** | las órdenes del usuario, con filtro por estado | separa bien las cosas; es el patrón de cualquier broker | un endpoint más |

- **Decisión: C.** Cómo es:
  - De la más nueva a la más vieja; si dos tienen la misma fecha, desempata el id (D-10).
  - **Un solo filtro, opcional, por estado.** Con `NEW` se ven las cancelables. No hay más filtros.
  - **Siempre paginado,** con tamaño por defecto y máximo configurables (ver abajo).
  - **Sin endpoint de detalle por id:** con el listado y la respuesta del envío alcanza.
- **Casos borde con nuestros datos:**

  | Caso | Qué hacer |
  |---|---|
  | Transferencias (órdenes 1 y 8) | se muestran, porque explican de dónde salen los $753.000, pero **como monto en pesos**, no como "cantidad 1.000.000 a precio 1": es la traducción de D-9; si no, el listado expone la doble lectura de `size` |
  | Orden 10 (METR a $250, fuera del rango del día) | tal cual: no se corrigen precios viejos (D-6) |
  | Orden 11 (vendió 30 BMA teniendo 20) | tal cual (D-1) |
  | Una `REJECTED` a mercado sobre PGR | precio vacío (A-6, parte 1) |
  | Usuarios 2 a 4, sin movimientos | lista vacía, no 404 (D-8) |
  | Usuario 99 | 404, porque está en la dirección del pedido (A-6, parte 1) |
  | Usuario 1 con filtro `NEW` | las órdenes 7 y 5 (BMA y LOMA), las dos cancelables |

#### Paginación: por cursor

- **Dos formas evaluadas:**
  - **Por número de página:** "dame la página 3, de a 20". La base se saltea las primeras 40 y devuelve las 20
    siguientes. Es la más conocida.
  - **Por cursor ("ver más"):** "dame 20 más viejas que la última que vi". Cada respuesta trae una marca de dónde
    quedó, y el pedido siguiente la manda de vuelta. Es lo que hacen las apps con scroll infinito.
- **Medición** (copia local en Docker, nunca Neon). Datos inventados: 1.000.000 de órdenes repartidas entre 10.000
  usuarios, un usuario de 10.000 órdenes (el "pesado" de A-4) y uno de 1.000.000 (caso extremo: ningún usuario normal
  llega a eso). Índice por (usuario, fecha, id). Son números de una laptop con datos ya en memoria: sirven como orden
  de magnitud.

  | | Usuario con 10.000 órdenes | Usuario con 1.000.000 |
  |---|---|---|
  | Por página, la primera | 0,015 ms | 0,013 ms |
  | Por página, la última | 0,7 ms | **69 ms** |
  | Por cursor, la última | 0,018 ms | **0,017 ms** |
  | Contar el total (para mostrar "página 2 de 50") | 0,7 ms | 35 ms |

  Para llegar a la última página, la paginación por número lee el millón de órdenes y descarta casi todas; el cursor
  va directo a donde quedó. **Con 10.000 órdenes las dos andan bien: la velocidad sola no decide.**
- **Lo que decide: la cancelación rompe la paginación por número.** Un usuario tiene 30 órdenes `NEW` y las lista de a
  20. Ve la página 1 (las órdenes 1 a 20) y cancela 5. Quedan 25 pendientes, y las que eran 21 a 25 suben a los
  lugares 16 a 20. Pide la página 2, que saltea las primeras 20, y recibe desde la 26: **las órdenes 21 a 25 nunca las
  ve.** Pasa justo en el recorrido que se agrega (listar pendientes y cancelar). Con el cursor no pasa: "las más viejas
  que la última que vi" no cambia aunque se cancelen órdenes. Si llega una orden nueva mientras se pagina, la
  paginación por número muestra una repetida; el cursor tampoco tiene ese problema.
- **Dónde está la complejidad del cursor (poca):**
  - Lleva la fecha y el id de la última orden vista, como texto opaco. **La fecha no puede perder precisión:** la base
    la guarda con microsegundos, y si el cursor la redondea, se saltean o se repiten órdenes.
  - Un cursor mal armado da 400.
  - Para saber si hay más, se piden 21 y se devuelven 20: si vino la 21, la respuesta trae el cursor siguiente; si no,
    lo trae vacío.
- **Lo que se pierde:** saltar directo a la página 7 y mostrar "página 2 de 50". Para un listado de órdenes no hace
  falta, y se ahorra el conteo, que con un millón de órdenes tarda 35 ms.
- **Hallazgo para el bloque del esquema:** el filtro por `NEW` sobre el usuario de un millón de órdenes tarda
  **28 ms**: con el índice por (usuario, fecha, id), la base tiene que leer todas sus órdenes para encontrar las 3
  pendientes. **Con un índice por (usuario, estado, fecha, id) tarda 0,01 ms.** Ayuda a definir las columnas exactas
  del índice de A-4.

#### Transferencias (`CASH_IN` y `CASH_OUT`) por API: fuera de alcance

- **Lo que dice el enunciado:** *"se pueden modelar como ordenes"*. Describe cómo están guardadas; no pide crearlas.
- **Por qué quedan afuera:**
  1. No se piden.
  2. **En la vida real, la plata no entra porque el usuario lo dice:** un `CASH_IN` llega del banco cuando se acredita
     la transferencia. Un endpoint "depositar" sin autenticación (el enunciado dice que no hace falta) le permitiría a
     cualquiera crear plata de la nada.
  3. **La limitación de D-9:** `size` es entero, así que una transferencia no podría tener centavos. Si entraran en
     alcance, habría que resolver eso antes.
- **Entonces:** los tests y la demo cargan las transferencias directo en la base de prueba, como Cocos cargó las
  órdenes 1 y 8. Mandar `CASH_IN` o `CASH_OUT` al endpoint de órdenes da 400 (A-6, parte 1).

#### Decisiones

1. **Cancelación: sí, como extra chico.** La justifican la regla del enunciado, las reservas de A-1 y la opción A de
   A-6.
2. **Solo se cancela una orden `NEW`.** Otro estado: error de conflicto con el estado actual. Orden inexistente o de
   otro usuario: 404. La respuesta devuelve la orden ya `CANCELLED`.
3. **El cambio de estado se hace en un solo paso y solo si la orden sigue `NEW`:** dos cancelaciones juntas no pueden
   ganar las dos.
4. **Cancelar libera la reserva:** la plata de una compra y las acciones de una venta.
5. **Listado de órdenes del usuario: sí, mínimo** (opción C): de la más nueva a la más vieja con desempate por id, un
   solo filtro opcional por estado, transferencias como monto en pesos, lista vacía para un usuario sin órdenes y 404
   para uno inexistente.
6. **Paginación por cursor,** con tamaño por defecto y máximo configurables (los números, en el RFC). No se muestra el
   total ni se salta a una página.
7. **Para el bloque del esquema:** índice por (usuario, estado, fecha, id), con la medición de arriba; candidato:
   cuándo cambió el estado de la orden (sin recomendación fuerte).
8. **Fuera de alcance, documentados:** transferencias por API y detalle de una orden por id. El formato de las
   direcciones y los códigos HTTP exactos, en el RFC.
9. **Tests:**
   - Cancelar la orden 7 la deja `CANCELLED` y los disponibles del usuario 1 pasan de $627.500 a $717.500; después,
     la compra por $700.000 ya no queda `REJECTED`.
   - Cancelar la orden 3 (ya cancelada) o la 2 (`FILLED`) da error de conflicto y no cambia nada.
   - Dos cancelaciones simultáneas de la misma orden: una pasa y la otra da error de conflicto.
   - El listado del usuario 1 con filtro `NEW` devuelve las órdenes 7 y 5.
   - Cancelar entre páginas no saltea ninguna orden.
   - Un cursor mal armado da 400.
   - Las fechas con microsegundos no se pierden al pasar de página.
   - El listado de un usuario sin órdenes es una lista vacía.

## Inventario: lo que agregamos o cambiamos respecto del enunciado

Todo lo que no sale literal del enunciado, en un solo lugar y con el enlace a su porqué. Se mantiene al día con cada
decisión y es la base del alcance del PRD.

**Endpoints**

| Endpoint | ¿Lo pide el enunciado? | Dónde se decidió |
|---|---|---|
| Portfolio | sí | A-1, A-2, A-4, D-1, D-8 |
| Búsqueda de activos | sí | A-5 |
| Envío de órdenes | sí | A-6, D-2, D-4, D-6 |
| **Cancelar una orden** | no: el enunciado define la regla, no el endpoint | A-7 |
| **Listar las órdenes del usuario**, paginado por cursor | no | A-7 |

**Datos de las respuestas que el enunciado no pide**

| Endpoint | Dato agregado | Por qué | Dónde |
|---|---|---|---|
| Portfolio | pesos comprometidos en órdenes pendientes | explica por qué valor total ≠ disponibles + posiciones | A-1, decisión 4 |
| Portfolio | último precio, PPC y variación diaria de cada posición | explican el valor y el rendimiento | A-2, decisión 4 |
| Búsqueda | id, último precio y variación diaria | el id hace falta para mandar una orden; el precio es lo que muestra una búsqueda | A-5, decisión 11 |
| Envío | motivo del rechazo | sin él nadie puede responder "¿por qué me rechazaron?" | A-6, parte 1, decisión 3 |
| Envío por monto | la cantidad calculada | el usuario tiene que ver cuántas acciones salieron | A-6, parte 2, decisión 3 |
| Listado | cursor de la página siguiente | paginación | A-7 |

**Reglas que agregamos o precisamos**

| Regla | Dónde |
|---|---|
| Los pesos y las acciones disponibles descuentan las órdenes pendientes (reservas) | A-1 |
| Rendimiento total contra el precio promedio de compra; variación diaria aparte | A-2 |
| El último precio es el de la fecha más reciente, sin límite de antigüedad | D-3 |
| Sin cotización no se opera: MARKET y LIMIT quedan `REJECTED` | D-2 |
| Precios con más de 2 decimales: error 400, nunca se redondean | D-4 |
| Se cuenta por estado, no por tipo (la LIMIT `FILLED` cuenta) | D-7 |
| Usuario sin órdenes: portfolio en $0; usuario inexistente: 404 | D-8 |
| El orden de las órdenes es por fecha y hora, con desempate por id; fechas con zona horaria | D-10 |
| Las órdenes de un mismo usuario se procesan de a una | D-1 |
| Búsqueda: contiene, palabra por palabra, sin mayúsculas ni acentos; ARS excluido; tope de resultados | A-5 |
| Error (no se graba) si no se puede armar la orden; `REJECTED` (se graba) si la cuenta o el mercado no dejan | A-6, parte 1 |
| MARKET con precio: error 400 | A-6, parte 1 |
| Por monto, la cantidad se corta para abajo, en compras y ventas | A-6, parte 2 |
| Se acepta cualquier precio límite mayor a 0 y se reserva a ese precio | A-6, parte 2 |
| Solo se cancela una orden `NEW`; otro estado da conflicto | A-7 |

**Cambios al modelo de datos:** ninguno aplicado todavía. Los candidatos se juntan en "Pendientes acumulados para el
bloque del esquema" (abajo), y cada uno se decide y justifica en el RFC (fase 3). Se aplican con migraciones, solo en la copia local
(ver "Base de Neon").

**Lo que evaluamos y dejamos afuera, a propósito**

| Qué | Por qué | Dónde |
|---|---|---|
| Corregir datos heredados (orden 11, precio de la orden 10, `previousClose` de BBAR) | no le corresponde al desarrollador; se documentan | D-1, D-4, D-6 |
| Tabla de posiciones | con un índice alcanza (medido) | A-4 |
| Foto del portfolio a una fecha | evolución posible, si el portfolio supera el tiempo objetivo del RFC | A-4 |
| Tolerancia a errores de tipeo en la búsqueda | habría que inventar el umbral; mejora posible como "¿quisiste decir…?" | A-5 |
| Bandas de precio como las de BYMA | el porcentaje no se pudo verificar y sería simular el mercado | A-6, parte 2 |
| Ejecutar una LIMIT que cruza el precio | contradice el enunciado | A-6, parte 2 |
| Validar el paso de precio de 5 centavos | sería simular el mercado | D-4 |
| Transferencias por API | no se piden; la plata entra desde el banco; `size` entero | A-7 |
| Detalle de una orden por id | el listado alcanza | A-7 |

## Lo que falta, en orden

1. ~~**A-4** · ¿Las posiciones se guardan en una tabla o se calculan desde `orders`?~~ Cerrada: se calculan.
2. ~~**A-5** · Qué significa "similar" en la búsqueda.~~ Cerrada: contiene, palabra por palabra, sin mayúsculas
   ni acentos.
3. ~~**A-6** · Casos borde del envío de órdenes.~~ Cerrada: error si no se puede armar la orden, `REJECTED` si la
   cuenta o el mercado no dejan; por monto se corta para abajo; se acepta cualquier precio límite mayor a 0.
4. ~~**A-7** · ¿Cancelación y transferencias por API?~~ Cerrada: cancelación y listado de órdenes (paginado por
   cursor) sí; transferencias por API, no.
5. ~~**Bloque del esquema**~~ Pasa al RFC (fase 3), decidido el 2026-11-06: es trabajo técnico, y `CLAUDE.md` pone
   "modelo y cambios a la base" en el RFC. La lista de abajo queda escrita como insumo.
6. **Repaso rápido de riesgos (R-1 a R-5) y supuestos (S-1 a S-5)** del análisis. Varios ya quedaron resueltos: la
   concurrencia en D-1, el redondeo en A-6, el costo cero en A-2, la base compartida en "Base de Neon". Queda abierto
   R-2 (reintentos). ← **siguiente**
7. **Repaso final de dudas sobre los conceptos.**
8. **Reescritura de `01-analisis.md`** con estas decisiones, y recálculo de los números de referencia del usuario 1.
   Incluye el inventario de arriba, y saca o corrige lo que estas decisiones dejaron viejo: la sección de preguntas
   para Cocos (no se envían), "LIMIT sobre PGR → `NEW`" (D-2 la cambió a `REJECTED`) y "1 letra → 400" (A-5 la
   permite).

Las ambigüedades se cerraron antes que el esquema porque varias decisiones del esquema dependen de ellas: A-4 define
si hay tabla de posiciones; A-5, qué índices y extensiones necesita la búsqueda; A-6, si una orden rechazada puede
quedar con cantidad 0 o sin precio; A-7, el índice del listado.

### Pendientes acumulados para el bloque del esquema

Insumo del RFC (fase 3): ahí se decide y justifica cada uno, y se escriben las migraciones.

- **E-1 a E-8 del análisis:** columnas obligatorias, valores permitidos, únicos, índices, decimales, tamaño de `size`,
  orden alfabético, extensiones.
- **D-1:** la regla "no vender más de lo que se tiene" no va en la base; sí las reglas de una sola fila.
- **D-3:** (acción, fecha) única en `marketdata`; `close` obligatorio; índice por (acción, fecha).
- **D-4:** los precios se quedan en 2 decimales (decidido). Si algún día se amplían, `marketdata` y `orders.price`
  juntas.
- **D-5:** `open`, `high`, `low` y `previousClose` opcionales. Candidatas: precios > 0, mínimo ≤ máximo, cierre entre
  los dos.
- **D-9:** "si es `CASH_IN` o `CASH_OUT`, el `price` es 1".
- **D-10:** `datetime` con zona horaria, interpretando las fechas existentes como hora argentina.
- **A-2:** precio > 0, que también evita el costo cero.
- **A-4:** sin tabla de posiciones. Índice en `orders` por usuario (columnas exactas a definir), justificado con la
  medición de A-4.
- **A-5:** extensión `unaccent`. Índice para la búsqueda: no hace falta con 65 activos; se evalúa midiendo con
  volumen.
- **A-6, parte 1:** cantidad ≥ 1 en todas las órdenes; precio vacío solo en una `REJECTED` a mercado sobre un activo
  sin cotización; columna candidata para el motivo del rechazo (recomendada).
- **A-6, parte 2:** nada nuevo. El precio límite > 0 ya está en A-2, y el monto pedido no se guarda.
- **A-7:** índice por (usuario, estado, fecha, id) para el listado con filtro por estado (28 ms → 0,01 ms con un
  usuario de un millón de órdenes); se define junto con el índice de A-4. Candidato: cuándo cambió el estado de la
  orden (sin recomendación fuerte).
- **C-2, abierta:** ¿renombrar columnas (por ejemplo, `instrumentid` → `instrument_id`)? Propuesta: no, para que
  cualquiera que consulte la base con los nombres que conoce siga pudiendo hacerlo.
