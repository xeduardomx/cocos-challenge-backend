# 02 — PRD: qué tiene que hacer la API

> Fase 2 · 2026-11-07 · Estado: **aprobado por el autor el 2026-11-07**
>
> Este documento dice qué hace la API y para qué. El porqué de cada decisión está en
> [`01-analisis.md`](01-analisis.md) y [`01-decisiones.md`](01-decisiones.md), bajo el código que aparece entre
> paréntesis (A-1, D-2…); las pocas que se tomaron al armar este PRD están en la sección 7. El cómo se construye va
> en el RFC (fase 3).

## 1. El problema y para quién

**El problema.** Un inversor que usa la app necesita:

- **Saber cuánto tiene y cuánto puede invertir.** Si la app le mostrara como disponible la plata que ya reservó una
  compra pendiente, podría usarla dos veces.
- **Entender si gana o pierde** con cada acción, comparando con lo que pagó.
- **Encontrar un activo sin saber el ticker:** escribir "galicia" o "rio" y que aparezca.
- **Comprar o vender y saber qué pasó:** si la orden se ejecutó, quedó pendiente o se rechazó, y por qué. Nunca se
  ejecuta dos veces la misma orden, ni con plata o acciones que no tiene.
- **Arrepentirse de una orden pendiente** y que se le libere la plata reservada.

**Para quién.**

- **El inversor:** usa la app y nunca ve la API. Cuando este documento dice "el usuario", es él.
- **La app:** llama a la API en nombre del inversor. Tiene una responsabilidad propia: ponerle un identificador a cada
  orden nueva y usar el mismo cuando reintenta (R-2).
- **Soporte (secundario):** tiene que poder contestar "¿por qué me rechazaron esta orden?" y ver cuántas órdenes se
  ejecutan o se rechazan.

**Qué es un buen resultado.**

- **Los números cierran a la vista:** valor total, disponibles y reservado se explican entre sí, y coinciden con los
  números de referencia del análisis (sección 10).
- **Ninguna orden gasta plata o acciones que no hay,** aunque lleguen dos juntas o una repetida.
- **Se puede recorrer todo desde la API,** sin abrir la base: buscar un activo, mandar una orden, verla en el listado y
  cancelarla.

## 2. Alcance

**Qué hace la API**

| Función | Para qué | ¿Lo pide el enunciado? |
|---|---|---|
| Portfolio | ver el valor de la cuenta, los pesos disponibles y cada posición con su rendimiento | sí |
| Búsqueda de activos | encontrar un activo por ticker o por nombre | sí |
| Envío de órdenes | comprar o vender, a mercado o con precio límite, por cantidad o por monto | sí |
| Cancelación de órdenes | arrepentirse de una orden pendiente y liberar lo reservado | no: el enunciado da la regla ("solo se cancela una orden `NEW`") pero no pide la función. Sin ella, lo reservado quedaría bloqueado para siempre (A-7) |
| Listado de órdenes | ver las órdenes propias: cuál cancelar, por qué se rechazó una | no: sin listado no hay forma de saber qué cancelar (A-7) |

**Además:**

- **Una colección de Postman** con el recorrido completo: buscar, enviar, reintentar la misma orden, listar y cancelar.
  El enunciado la pide como opcional. Cada envío genera solo su identificador, y el pedido de reintento repite una
  compra anterior, para mostrar que no se crea otra orden (R-2).
- **Métricas para soporte:** se detallan en la sección 4.

**Qué no hace, a propósito**

| Qué | Por qué | Dónde |
|---|---|---|
| Autenticación | el enunciado dice que no es necesaria; el usuario viene en el pedido (la limitación, en la sección 5) | S-5 |
| Depósitos y retiros | no se piden, y sin autenticación cualquiera podría crear plata; los datos de prueba los cargan directo | A-7 |
| Simular el mercado | el enunciado dice que no hace falta: una orden límite queda pendiente y nunca se ejecuta sola | D-7 |
| Ejecutar en el momento una orden límite que cruza el precio | contradice el enunciado | A-6 |
| Bandas de precio, o pasos de precio de 5 centavos | sería simular el mercado | A-6, D-4 |
| Tolerar errores de tipeo en la búsqueda | habría que inventar cuánto parecido alcanza | A-5 |
| Ver el detalle de una orden por separado | con el listado alcanza | A-7 |
| Modificar una orden pendiente | se cancela y se manda otra | este PRD |
| Vencimiento de las órdenes límite | quedan pendientes hasta que el usuario las cancela; vencerlas (por ejemplo, al cierre del día) sería simular el mercado | enunciado, D-7 |
| Horario de mercado | las órdenes se aceptan a cualquier hora, con el último precio; controlar el horario sería simular el mercado | enunciado |
| Corregir datos heredados (órdenes 11 y 10, el precio anterior de BBAR) | no le corresponde al desarrollador; se documentan | D-1, D-4, D-6 |
| Otras monedas | todo está en pesos y no hay nada que convertir | S-1 |
| Comisiones e impuestos | el modelo de datos no los tiene, así que el PPC es solo sobre precios. Quedan como mejora posible, afuera por practicidad (ver abajo) | A-2 |

**Comisiones: por qué quedan afuera.** Serían una mejora posible, pero tocan casi todos los cálculos de plata. Aun
con una comisión fija por orden, cambiarían:

- los pesos de la cuenta y los disponibles, porque cada orden restaría la comisión;
- la cantidad máxima de una compra por monto, que tendría que dejar lugar para la comisión;
- la reserva de las órdenes límite (precio × cantidad + comisión);
- el PPC, si la comisión se cuenta como parte del costo;
- y aparecerían casos nuevos, como una venta chica que no alcanza a cubrir su comisión.

Además, el valor sería inventado: el enunciado no da ninguno. Por practicidad, se decidió dejarlas afuera.

## 3. Requisitos por función

Los casos usan los datos de `database.sql`. Ahí, el usuario 1 tiene $753.000 en la cuenta, compras pendientes por
$125.500 (órdenes 5 y 7) y tres posiciones: 500 METR, 40 PAMP y −10 BMA. Cuando un caso usa otros datos, lo aclara.
Cada caso parte de los datos originales: lo que graba un caso no lo ve el siguiente. Cómo lo logran los tests se
define en el RFC (sección 10.2).

Los requisitos llevan un código de tres letras por función (POR, BUS, ENV, CAN, LIS y GEN), para no confundirlos con
los del análisis. El plan (fase 4) los cita, tarea por tarea.

### 3.1 Portfolio

*Para qué:* que el inversor sepa cuánto vale su cuenta, cuánto puede invertir y cómo le va con cada acción.

| | Requisito | Dónde |
|---|---|---|
| POR-1 | **Valor total** = pesos de la cuenta + valor de las posiciones. Los pesos de la cuenta son depósitos − retiros − compras + ventas ejecutadas, e incluyen lo reservado, que sigue siendo del usuario | A-1, D-9 |
| POR-2 | **Disponibles para operar** = pesos de la cuenta − lo que reservan las compras pendientes, a su precio límite. Lo reservado también se muestra | A-1 |
| POR-3 | **Posiciones:** por acción, compras − ventas ejecutadas, a mercado o límite. Si da 0, la posición no aparece; si da negativa, aparece tal cual. ARS no aparece | D-1, D-7, D-9 |
| POR-4 | **Valor de cada posición** = cantidad × último precio (el `close` de la fecha más reciente). Si la acción no cotiza, aparece sin valor y no suma | D-2, D-3 |
| POR-5 | **Rendimiento total** = (último precio − PPC) ÷ PPC. El PPC promedia las compras en orden cronológico, y las ventas no lo cambian. Con la posición en 0 o negativa, PPC y rendimiento quedan vacíos; si vuelve a positiva, el PPC arranca de esa compra | A-2 |
| POR-6 | **Variación diaria** = (`close` − `previousClose`) ÷ `previousClose`, usando la columna tal cual. Si no hay `previousClose`, queda vacía | A-2, D-4, D-5 |
| POR-7 | **Cada posición muestra** el activo, la cantidad, el valor y el rendimiento total (lo que pide el enunciado), más el último precio, el PPC, la variación diaria y cuántas de sus acciones están reservadas por ventas pendientes. Esto último explica por qué se rechaza una venta aunque la cantidad parezca alcanzar | A-1, A-2, sección 7 |
| POR-8 | **Siempre al día:** una orden recién ejecutada ya cuenta en el siguiente portfolio. Es el "actualizar el listado de posiciones" del enunciado | A-4 |
| POR-9 | **Usuario sin órdenes:** todo en $0 y la lista vacía, sin error. **Usuario que no existe:** error | D-8 |

**Casos**

1. **Dado** el usuario 1, **cuando** pide su portfolio, **entonces** ve $627.500 disponibles, $125.500 reservados y un
   valor total de $889.756.
2. **Y** ve estas posiciones. No aparecen ARS ni LOMA, porque de LOMA solo tiene una compra pendiente:

   | Activo | Cantidad | Valor | PPC | Rendimiento | Variación diaria |
   |---|---|---|---|---|---|
   | METR | 500 | $114.750 | $250 | −8,20 % | −1,08 % |
   | PAMP | 40 | $37.034 | $930 | −0,45 % | +0,44 % |
   | BMA | −10 | −$15.028 | — | — | −1,15 % |

   BMA da −10 y no −30, porque la orden 9 es límite, está ejecutada y cuenta.
3. **Dado** el usuario 1, **cuando** compra 10 PAMP a mercado, **entonces** el siguiente portfolio muestra 50 PAMP con
   PPC $929,17 y rendimiento −0,36 %, $618.241,50 disponibles y el mismo valor total.
4. **Dado** el usuario 1, **cuando** compra 15 BMA a mercado, **entonces** su portfolio muestra 5 BMA con PPC $1.502,80
   (no $1.428,40) y rendimiento 0 %.
5. **Dado** un usuario que compró 10 acciones a $100 y 10 a $200 y vendió 5, **cuando** pide su portfolio, **entonces**
   su PPC es $150.
6. **Dado** un usuario con acciones de BBAR, **cuando** pide su portfolio, **entonces** la variación diaria de BBAR es
   −0,87 %.
7. **Dado** un usuario con una acción cuyo último día no tuvo operaciones, **cuando** pide su portfolio, **entonces** el
   último precio de esa acción es el `close` y su variación diaria es 0 %. Necesita datos propios: en `database.sql`,
   los días sin operaciones de DOME y POLL no son el último.
8. **Dado** un usuario con una acción cuyo precio más reciente se cargó antes que uno más viejo, **cuando** pide su
   portfolio, **entonces** se usa el precio más reciente por fecha.
9. **Dado** un usuario con acciones de PGR, que no cotiza, **cuando** pide su portfolio, **entonces** PGR aparece sin
   valor y el total se calcula igual.
10. **Dado** el usuario 2, sin órdenes, **cuando** pide su portfolio, **entonces** ve $0 y la lista vacía. **Dado** el
    usuario 99, que no existe, **cuando** pide su portfolio, **entonces** recibe un error.
11. **Dado** el usuario 1, que mandó una venta límite de 30 PAMP a $950, **cuando** pide su portfolio, **entonces** PAMP
    muestra 40 acciones, 30 de ellas reservadas. En los datos originales, las tres posiciones tienen 0 reservadas.

### 3.2 Búsqueda de activos

*Para qué:* que el inversor encuentre un activo escribiendo una parte del ticker o del nombre, como en la caja de
búsqueda de la app.

| | Requisito | Dónde |
|---|---|---|
| BUS-1 | **Un solo texto**, que se busca en el ticker y en el nombre a la vez. Así se cumple el "ticker y/o nombre" del enunciado sin que el usuario tenga que elegir dónde buscar | A-5 |
| BUS-2 | **"Similar" significa "contiene":** todas las palabras del texto tienen que aparecer en el ticker o en el nombre, en cualquier orden, sin importar mayúsculas ni acentos. Cada carácter se busca tal cual: `%` y `_` no funcionan como comodines | A-5 |
| BUS-3 | **ARS no aparece:** no se compran pesos con pesos | A-5 |
| BUS-4 | **Orden de los resultados:** primero el ticker igual al texto, después el ticker que empieza así, el ticker que lo contiene, el nombre que empieza así y por último el resto. Los empates se ordenan por ticker | A-5 |
| BUS-5 | **Tope de resultados** configurable (por ejemplo, 20). Si nada coincide, la lista viene vacía, sin error | A-5 |
| BUS-6 | **Texto vacío o solo con espacios: error.** Una sola letra se permite. Un texto más largo que el máximo también da error (el máximo se fija en el RFC) | A-5 |
| BUS-7 | **Cada resultado muestra** el identificador del activo (para mandar una orden), el ticker, el nombre, el último precio y la variación diaria. Si el activo no cotiza, el precio y la variación van vacíos | A-5, D-2 |

**Casos.** **Dado** el mercado de `database.sql` (65 acciones y ARS):

1. **Cuando** busca "banco", **entonces** aparecen BBAR, BHIP, BMA y BPAT.
2. **Cuando** busca "rio", **entonces** aparecen BHIP, MOLI, OEST y RICH. MOLI aparece por "Molinos **Río** de la
   Plata", aunque lleve acento. Con "compania" aparece INTR ("Com**pañ**ía").
3. **Cuando** busca "grupo galicia", **entonces** aparece GGAL ("Grupo **Financiero** Galicia": las palabras no tienen
   que estar juntas). Con "gas sur" aparecen CGPA2 y TGSU2.
4. **Cuando** busca "pa", **entonces** aparecen, en este orden, PAMP, PATA, BPAT, CGPA2 e INTR.
5. **Cuando** busca "y", **entonces** YPFD aparece primero.
6. **Cuando** busca "a", **entonces** vienen 20 resultados como máximo, aunque coinciden 59.
7. **Cuando** busca "pesos" o "_", **entonces** la lista viene vacía.
8. **Cuando** busca "pamp", **entonces** PAMP aparece con último precio $925,85 y variación diaria +0,44 %. Con "pgr",
   PGR aparece con precio y variación vacíos.
9. **Cuando** busca un texto vacío, solo con espacios o demasiado largo, **entonces** recibe un error.

Las limitaciones aceptadas van en la sección 5: "banco" no encuentra VALO, que en los datos figura como "BCO DE
VALORES", y "bbva" no encuentra BBAR, que figura como "Banco Frances".

### 3.3 Envío de órdenes

*Para qué:* que el inversor compre o venda una acción, a mercado o con precio límite, por cantidad o por monto, y sepa
enseguida qué pasó con su orden. Estos casos son la base del test funcional que pide el enunciado (GEN-3).

| | Requisito | Dónde |
|---|---|---|
| ENV-1 | **Qué se manda:** el usuario, el activo, el lado (compra o venta), el tipo (a mercado o límite), la cantidad de acciones **o** el monto en pesos (uno de los dos), el precio límite (solo en las límite) y el identificador de la orden | enunciado, R-2 |
| ENV-2 | **A mercado:** se ejecuta en el momento al último precio (`close`), queda `FILLED` y se graba con ese precio | enunciado, D-6 |
| ENV-3 | **Límite:** queda pendiente (`NEW`) con el precio que puso el usuario, y nunca se ejecuta sola, porque no se simula el mercado. Se acepta cualquier precio mayor a 0, y la orden reserva a ese precio | enunciado, A-6, D-7 |
| ENV-4 | **Por monto:** cantidad = monto ÷ precio, cortando los decimales, o sea, la cantidad máxima que entra, sin fracciones. El precio es el `close` en una orden a mercado y el límite en una límite. Vale para compras y para ventas | enunciado, A-6 |
| ENV-5 | **Si no alcanza, se graba como `REJECTED`.** Una compra necesita pesos disponibles, descontando lo reservado. Una venta necesita acciones disponibles, descontando las ventas pendientes. Por monto tampoco se compra "lo que entre": si no alcanza, se rechaza | enunciado, A-1, A-6 |
| ENV-6 | **Si el activo no cotiza, se graba como `REJECTED`:** por cantidad, a mercado o límite, y por monto con límite. Por monto a mercado no hay precio para calcular la cantidad, así que es un error (caso 14) | D-2, A-6 |
| ENV-7 | **Error, sin grabar nada, cuando con lo enviado no se puede armar una orden** (la lista completa está en los casos) | A-6 |
| ENV-8 | **La respuesta devuelve la orden:** su número (con el que se la cancela), el activo, el lado, el tipo, la cantidad (incluida la calculada por monto), el precio grabado, el estado, la fecha y hora con zona horaria, el identificador que puso la app y, si se rechazó, el motivo. Un rechazo no es un error: la orden existe, rechazada | A-6, D-10, R-2 |
| ENV-9 | **Identificador obligatorio, único por usuario.** Si llega repetido con el mismo contenido, es un reintento: se devuelve la orden original, sin crear otra ni volver a evaluarla, aunque haya quedado rechazada. Si llega repetido con otro contenido, es un error. Un pedido que dio error no gasta el identificador, y dos usuarios pueden usar el mismo | R-2 |

**Casos.** Son del usuario 1 con sus datos originales, salvo que se aclare otra cosa.

*Ejecución*

1. **Cuando** compra 10 PAMP a mercado, **entonces** la orden queda `FILLED` a $925,85, por $9.258,50.
2. **Cuando** compra 10 METR con límite $10.000, **entonces** queda `NEW`, reserva $100.000 y los disponibles bajan a
   $527.500.

*Por monto*

3. **Cuando** compra $10.000 de PAMP a mercado, **entonces** salen 10 acciones ($9.258,50), la respuesta muestra la
   cantidad 10 y los $741,50 que sobran siguen disponibles.
4. **Cuando** compra $9.258,50 de PAMP a mercado, **entonces** salen exactamente 10 acciones, no 9.
5. **Cuando** compra $10.000 de PAMP con límite $900, **entonces** salen 11 acciones, la orden queda `NEW` y se reservan
   $9.900.
6. **Cuando** vende $10.000 de PAMP a mercado, **entonces** vende 10 acciones ($9.258,50).

*Rechazos (la orden se graba)*

7. **Cuando** compra $700.000 de PAMP a mercado (756 acciones, $699.942,60), **entonces** queda `REJECTED` con el motivo
   "no alcanzan los pesos", y `orders` tiene una fila más. Sin la reserva de A-1, habría pasado.
8. **Cuando** compra $630.000 de PAMP a mercado (680 acciones, $629.578), **entonces** queda `REJECTED`: no se compra lo
   que entre.
9. **Cuando** compra 100 METR con límite $10.000, **entonces** queda `REJECTED`, porque reservaría $1.000.000.
10. **Cuando** vende 50 PAMP, **entonces** queda `REJECTED`, porque tiene 40.
11. **Dado** que mandó una venta límite de 30 PAMP a $950, que queda `NEW`, **cuando** vende 20 PAMP a mercado,
    **entonces** queda `REJECTED`, porque solo le quedan 10 disponibles.
12. **Cuando** compra 10 PGR, a mercado o límite, **entonces** queda `REJECTED` con el motivo "el activo no cotiza". La
    orden a mercado queda con el precio vacío.
13. **Dado** el usuario 2, sin pesos, **cuando** compra 1 PAMP a mercado, **entonces** queda `REJECTED`.

*Errores (no se graba nada y `orders` no cambia)*

14. **Cuando** manda cualquiera de estos pedidos, **entonces** recibe un error que explica qué está mal:
    - falta un dato, o tiene un valor no válido (por ejemplo, lado "COMPRA");
    - trae cantidad y monto juntos, o ninguno de los dos;
    - la cantidad no es entera (2,5) o es 0 o menos;
    - el monto o el precio es 0 o menos, o tiene más de 2 decimales ($229,555);
    - es límite sin precio, o a mercado con precio;
    - compra o vende ARS, o el lado es un depósito o un retiro;
    - pide $500 de PAMP, y la cantidad da 0;
    - pide $10.000 de PGR a mercado, y no hay precio para calcular la cantidad;
    - el usuario o el activo no existen;
    - no trae identificador.

*Identificador (reintentos)*

15. **Cuando** reenvía una orden con el mismo identificador y el mismo contenido, **entonces** recibe la misma orden y
    `orders` no suma filas.
16. **Dado** que la compra de $700.000 quedó `REJECTED` con el identificador "A" y que después canceló la orden 7,
    **cuando** la reenvía con "A", **entonces** recibe el mismo rechazo; **cuando** la manda con "B", **entonces** queda
    `FILLED` y los disponibles quedan en $17.557,40.
17. **Cuando** usa un identificador ya usado, pero con otro contenido (100 PAMP y después 200 PAMP), **entonces** recibe
    un error y `orders` no cambia.
18. **Cuando** dos usuarios usan el mismo identificador, **entonces** se crean las dos órdenes.
19. **Cuando** un pedido da error y lo corrige, **entonces** la orden se crea con el mismo identificador.
20. **Cuando** llegan al mismo tiempo dos pedidos con el mismo identificador, **entonces** se crea una sola orden.

### 3.4 Cancelación de órdenes

*Para qué:* que el inversor se arrepienta de una orden pendiente y vuelva a tener disponible lo que reservaba.

| | Requisito | Dónde |
|---|---|---|
| CAN-1 | **Solo se cancela una orden pendiente (`NEW`),** indicando su número, el que devuelven el envío y el listado. Queda `CANCELLED`, y la respuesta devuelve la orden así | enunciado, A-7 |
| CAN-2 | **Cancelar libera la reserva:** la plata de una compra, o las acciones de una venta, vuelven a estar disponibles. Los pesos de la cuenta no cambian | A-1, A-7 |
| CAN-3 | **Una orden que ya no está pendiente** (ejecutada, rechazada o cancelada) **no se cancela:** error que dice en qué estado está, y no cambia nada | A-7 |
| CAN-4 | **Una orden que no existe, o que es de otro usuario: error de "no existe".** A nadie se le confirma que existe una orden ajena | A-7 |
| CAN-5 | **Dos cancelaciones de la misma orden al mismo tiempo:** gana una, y la otra recibe el error de CAN-3. Reintentar una cancelación también recibe ese error, que es inofensivo, así que no hace falta identificador | A-7, R-2 |

**Casos.**

1. **Dado** el usuario 1, **cuando** cancela la orden 7 (60 BMA a $1.500), **entonces** queda `CANCELLED`, se liberan
   $90.000 y los disponibles pasan de $627.500 a $717.500. Los pesos de la cuenta siguen en $753.000.
2. **Y** después, **cuando** compra $700.000 de PAMP a mercado con un identificador nuevo, **entonces** queda `FILLED`.
   Antes de cancelar, quedaba `REJECTED`.
3. **Dado** el usuario 1, que mandó una venta límite de 30 PAMP, **cuando** la cancela, **entonces** vuelve a tener las 40
   PAMP disponibles para vender, y su portfolio muestra 0 reservadas.
4. **Cuando** cancela la orden 3 (ya `CANCELLED`), la 2 (`FILLED`) o la 6 (`REJECTED`), **entonces** recibe un error que
   dice el estado de la orden, y nada cambia.
5. **Cuando** cancela la orden 999, **entonces** recibe un error de "no existe". **Dado** el usuario 2, **cuando** cancela
   la orden 7, que es del usuario 1, **entonces** recibe el mismo error.
6. **Cuando** llegan al mismo tiempo dos cancelaciones de la orden 7, **entonces** una la cancela y la otra recibe el
   error de que ya está `CANCELLED`.

### 3.5 Listado de órdenes

*Para qué:* que el inversor vea sus órdenes, sepa cuáles puede cancelar y por qué se rechazó una.

| | Requisito | Dónde |
|---|---|---|
| LIS-1 | **Las órdenes del usuario, de la más nueva a la más vieja.** Si dos tienen la misma fecha y hora, desempata el número de orden | A-7, D-10 |
| LIS-2 | **Un solo filtro, opcional: el estado.** Con `NEW` se ven las cancelables | A-7 |
| LIS-3 | **Siempre paginado, por cursor ("ver más"):** cada página trae la marca para pedir la siguiente, y la última no trae ninguna. El tamaño de página tiene un valor por defecto y un máximo, configurables. No se muestra el total ni se puede saltar a una página. Cancelar entre una página y otra no hace saltear ninguna orden | A-7 |
| LIS-4 | **Cada orden muestra lo mismo que la respuesta del envío** (ENV-8), incluido su número, para poder cancelarla. Los depósitos y retiros son la excepción: se muestran como monto en pesos (LIS-5). Las 11 órdenes de `database.sql` no tienen identificador ni motivo de rechazo: esos datos van vacíos | A-6, A-7, R-2 |
| LIS-5 | **Los depósitos y retiros aparecen como monto en pesos**, no como "1.000.000 acciones a $1" | A-7, D-9 |
| LIS-6 | **Usuario sin órdenes: lista vacía. Usuario que no existe: error.** Una marca de página mal armada también da error | A-7, D-8 |

**Casos.**

1. **Dado** el usuario 1, **cuando** lista sus órdenes con el filtro `NEW`, **entonces** ve la 7 (60 BMA a $1.500) y la 5
   (50 LOMA a $710), en ese orden.
2. **Cuando** lista todas, **entonces** ve las 11, de la 11 a la 1, tal como están en la base. El depósito (orden 1)
   aparece como $1.000.000 y el retiro (orden 8), como $100.000.
3. **Dado** un usuario con 30 órdenes pendientes que las lista de a 20, **cuando** cancela 5 de la primera página y pide
   la siguiente, **entonces** recibe las 10 que faltaban, sin saltear ninguna.
4. **Dado** un usuario con órdenes cuyas fechas difieren en microsegundos, **cuando** recorre todas las páginas,
   **entonces** cada orden aparece una sola vez.
5. **Dado** el usuario 2, **cuando** lista sus órdenes, **entonces** recibe la lista vacía. **Dado** el usuario 99,
   **entonces** recibe un error. **Cuando** manda una marca de página mal armada, **entonces** recibe un error.

## 4. Requisitos generales

| | Requisito | Dónde |
|---|---|---|
| GEN-1 | **Órdenes simultáneas:** las órdenes de un mismo usuario se procesan de a una. Dos órdenes que llegan juntas nunca gastan la misma plata ni venden las mismas acciones | D-1 |
| GEN-2 | **Se ejecuta localmente con un comando,** con una base propia cargada con `database.sql`. Los tests nunca tocan la base de Neon, y mientras se desarrolla la API tampoco. La dirección de la base se configura, así la misma API puede apuntar a la copia local o, si se decide en la fase 8, a Neon | mail, "Base de Neon" |
| GEN-3 | **Test funcional del envío de órdenes,** contra una base real, con los casos de la sección 3.3 | enunciado |
| GEN-4 | **Métricas para soporte:** cuántas órdenes se ejecutan, quedan pendientes o se rechazan, por tipo y por lado, y con qué motivo se rechazan; y, por función, cuánto tarda y cuántos errores da | sección 1 |
| GEN-5 | **Sin datos personales:** el email del usuario no se devuelve ni se registra en los logs | D-8 |
| GEN-6 | **Errores que se entienden:** cada error dice qué está mal (por ejemplo, "falta el precio límite"), con el mismo formato en todas las funciones | A-6 |
| GEN-7 | **Plata con 2 decimales,** sin redondear nunca lo que manda el usuario. Los cálculos redondean solo al final | D-4, R-3 |
| GEN-8 | **Tiempo de respuesta:** el RFC fija el objetivo y lo mide con volumen, como en A-4 | A-4 |

**Casos.**

1. **Dado** un usuario con 20 BMA, **cuando** llegan al mismo tiempo dos ventas de 15, **entonces** una queda `FILLED`, la
   otra `REJECTED`, y la posición termina en 5. Sin esto quedaría en −10, como la orden 11 (D-1).
2. **Dado** el usuario 1, con $627.500 disponibles, **cuando** llegan al mismo tiempo dos compras de $600.000 de PAMP a
   mercado, **entonces** una queda `FILLED` (648 acciones, $599.950,80) y la otra `REJECTED`.
3. **Dada** una máquina con Docker, **cuando** se levanta el proyecto con un comando, **entonces** el portfolio del usuario
   1 da los números de la sección 3.1.
4. **Cuando** una orden se rechaza, **entonces** la métrica de órdenes rechazadas suma una, con su motivo.
5. **Cuando** se pide cualquier portfolio o listado, **entonces** ni la respuesta ni los logs traen el email del usuario.

## 5. Supuestos y limitaciones

**Supuestos**

- **Los precios están en pesos** y no hay nada que convertir (S-1).
- **El último precio es el de la fecha más reciente, aunque sea viejo.** Los datos son de julio de 2023 y no hay límite
  de antigüedad; en producción, cuánto puede tener un precio para usarse lo define el negocio (S-2, D-3).
- **Las fechas de las órdenes existentes están en hora argentina** (S-4, D-10).
- **En una orden ejecutada, el precio es lo que se pagó,** también en la orden 9, que es límite (D-9).

**Limitaciones**

- **Sin autenticación:** cualquiera puede ver el portfolio de otro usuario o mandar órdenes en su nombre, solo
  cambiando el número. En producción, el usuario saldría de la sesión autenticada. Queda fuera por decisión del
  enunciado (S-5).
- **Datos heredados, tal cual:** la orden 11 deja BMA en −10, la orden 10 pagó METR por encima del máximo del día, y el
  `previousClose` de BBAR no coincide con el cierre anterior (probable ajuste por dividendo). Se documentan y no se
  corrigen (D-1, D-6, D-4).
- **Búsqueda:** "banco" no encuentra VALO, que figura como "BCO DE VALORES"; "bbva" no encuentra BBAR, que figura como
  "Banco Frances"; "banco sa" no encuentra "Banco Macro S.A.", porque los puntos cuentan; y no se toleran errores de
  tipeo (A-5).
- **Las órdenes límite no vencen:** quedan pendientes, reservando su plata o sus acciones, hasta que el usuario las
  cancela (D-7, A-7).
- **Reintentos:** si la app genera un identificador nuevo en cada reintento, la protección no funciona. Ninguna API
  puede arreglar ese error de la app (R-2).
- **Precios con 2 decimales:** si se incorporan acciones de menos de $1, o precios ajustados con más decimales, hay que
  ampliar las columnas de precios (D-4).
- **Transferencias sin centavos:** la base guarda los montos de los depósitos y retiros como enteros. No afecta, porque
  la API no crea transferencias (D-9).

## 6. Lo que queda para el RFC

Lo que este documento deja abierto a propósito, porque es parte del cómo:

- Las direcciones de la API, los códigos HTTP y el formato de los errores (GEN-6).
- El formato de las fechas (ENV-8) y el del identificador de orden: largo máximo y si viaja en el cuerpo o en un
  encabezado (ENV-9).
- Cómo se procesan de a una las órdenes de un mismo usuario (GEN-1).
- La estrategia de tests: cómo cada caso parte de los datos originales sin que un test pise a otro (D-8), y cómo se
  prueban los casos simultáneos (GEN-1, el caso 20 del envío y CAN-5), que necesitan cambios confirmados en la base
  desde conexiones separadas.
- Cómo se aplican las migraciones, sabiendo que la base de Neon ya tiene los datos y no tiene historial de
  migraciones.
- Los números configurables: el largo máximo de la búsqueda (BUS-6), el tope de resultados (BUS-5) y el tamaño de
  página del listado (LIS-3).
- El modo de redondeo (GEN-7), el objetivo de tiempo de respuesta (GEN-8) y cómo se muestran las métricas (GEN-4).
- Los cambios a la base. Entre ellos, guardar el identificador de cada orden (ENV-9) y su motivo de rechazo, porque el
  listado lo muestra (LIS-4).

## 7. Lo que se decidió en esta fase

Casi todo este documento ordena decisiones del análisis. Estas se tomaron al armar el PRD:

| Decisión | Por qué | Dónde |
|---|---|---|
| Se entrega una colección de Postman con el recorrido completo, incluido un reintento | el enunciado la pide como opcional, y permite recorrer todo desde la API | sección 2 |
| Comisiones e impuestos, afuera, como mejora posible | cambian casi todos los cálculos de plata y el valor sería inventado | sección 2 |
| Modificar una orden, el vencimiento de las órdenes límite y el horario de mercado, afuera | una orden se cancela y se manda otra; lo demás sería simular el mercado | sección 2 |
| El portfolio muestra, en cada posición, las acciones reservadas por ventas pendientes | surgió al revisar los casos del envío: sin ese dato, una venta rechazada no se explica. El usuario ve 40 PAMP, quiere vender 20 y no sabe que 30 están reservadas por una venta pendiente. Es lo mismo que A-1 hace con los pesos | POR-7 |
| La respuesta del envío y el listado traen el número de orden | es el dato con el que se cancela una orden | ENV-8, LIS-4, CAN-1 |
| Soporte es un usuario secundario, con métricas de órdenes | tiene que poder contestar "¿por qué me rechazaron?" y ver cuántas órdenes se ejecutan o se rechazan | sección 1, GEN-4 |
