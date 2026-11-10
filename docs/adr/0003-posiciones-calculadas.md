# ADR 0003 — Las posiciones se calculan desde `orders`, sin tabla de posiciones

> Estado: **aceptada** · 2026-11-07, revisada el 2026-11-09 · Viene de A-4 (`01-decisiones.md`) y se aplica en el
> [RFC, secciones 3.5 y 4.1](../03-rfc.md#41-la-cuenta-del-usuario-la-base-trae-las-órdenes-y-java-calcula)

## Contexto

- El enunciado empuja para los dos lados. *"Cuando una orden es ejecutada, se tiene que actualizar el listado de
  posiciones del usuario"* suena a una tabla. *"Para hacer el cálculo de la tenencia y pesos disponibles utilizar todos
  los movimientos pertinentes que hay en la tabla `orders`"* suena a calcular. Y permite agregar tablas para mejorar
  la performance, si se justifica.
- Las reservas salen de las órdenes `NEW` (A-1), y el PPC depende del orden de las compras y las ventas (A-2): las dos
  cosas necesitan leer las órdenes igual.
- La posición de BMA del usuario 1 está en −10 (D-1).

## Opciones

**A. Calcular en cada pedido desde `orders`, con un índice por usuario.**

- A favor: el dato vive en un solo lugar (RFC, principio 4), y el listado de posiciones siempre está al día, porque
  se arma con las órdenes del momento.
- En contra: el costo crece con las órdenes del usuario. Medido con 2 millones de órdenes (RFC, sección 3.5): 1,2 ms
  en la base para un usuario de 10.000 órdenes, y 175 ms para uno de 1.000.000.

**B. Una tabla de posiciones que cada orden ejecutada actualiza.**

- A favor: leer el portfolio costaría lo mismo, tenga el usuario la historia que tenga.
- En contra:
  - No evita leer las órdenes: las reservas y el PPC siguen saliendo de ellas.
  - El mismo dato queda en dos lugares, que pueden diferir: si se corrige una orden a mano, o si otro sistema ejecuta
    una límite sin actualizar la tabla, el portfolio muestra una cantidad vieja.
  - No admitiría la regla "cantidad ≥ 0", porque BMA ya está en −10.
  - Cada cambio de estado de una orden tendría que tocar las dos tablas, siempre juntas.

**C. Una foto que la base recalcula cada tanto (vista materializada).** Entre foto y foto muestra datos viejos: una
compra recién ejecutada no aparecería, y no serviría para validar lo disponible al mandar una orden.

**D. Una foto a una fecha, más las órdenes posteriores.** Lee poco aunque el usuario tenga años de historia, pero
suma el mantenimiento de la foto.

## Decisión

**A.** Las posiciones, los pesos, las reservas y el PPC se calculan en cada pedido desde las órdenes `FILLED` y `NEW`
del usuario, con el índice por (usuario, fecha, id) de la sección 3.5. El cálculo vive en el paquete `account` y lo
usan el portfolio y el envío (ADR 0001).

## Consecuencias

- "Actualizar el listado de posiciones" se cumple solo.
- Sin tabla de posiciones, no aparece el problema de "cantidad ≥ 0" con BMA: queda coherente con D-1.
- El usuario extremo, con un millón de órdenes, queda fuera del objetivo de tiempo de la sección 8.4: es el límite
  documentado de esta decisión.
- **El cálculo necesita todo el historial de órdenes** (ver "Revisión", abajo).
- **Cuándo revisarla:** si el portfolio de los usuarios del objetivo (hasta 10.000 órdenes) superara los 100 ms de la
  sección 8.4, o si se decidiera borrar o archivar órdenes viejas. El paso siguiente sería D, la foto a una fecha. Con
  eso, las pendientes pasarían a ser la consulta más lenta, y ahí entra el índice parcial que ya está medido (sección
  3.5).

## Revisión (fase 8, 2026-11-09)

Antes de entregar, el autor volvió a evaluar la opción B por una razón que no se había mirado: **el cálculo necesita
todo el historial.** Si dentro de unos años se borraran las órdenes más viejas de un usuario, los números darían mal y
sin ningún error. Por ejemplo, sin las órdenes 1 a 4, el usuario 1 pasaría de $753.000 a −$209.900 en pesos y PAMP
desaparecería. Una tabla con la posición vigente no dependería de ese historial.

**Se mantiene A.** La tabla sería válida, pero se descartó por esto:

1. **El enunciado indica calcular desde las órdenes.** *"Para hacer el calculo de la tenencia y pesos disponibles
   utilizar todos los movimientos pertinentes que hay en la tabla `orders`, utilizando la columna `size`"*, y *"Para
   calcular el valor de mercado, rendimiento y cantidad de acciones de cada posición usar las ordenes en estado
   `FILLED` de cada activo"*. Lo de "actualizar el listado" ya se cumple: una orden ejecutada cuenta en el siguiente
   portfolio (POR-8).
2. **Una tabla no vería lo que no pasa por la API.** La API no crea depósitos ni ejecuta órdenes límite (A-7, D-7): eso
   lo hace otro sistema. Hoy, un `CASH_IN` nuevo o una límite que pasa a `FILLED` cuentan en el siguiente pedido. Con la
   tabla no contarían, salvo que cada sistema la actualice o que un trigger lleve a la base el cálculo del PPC, que el
   RFC deja en Java (sección 4.1).
3. **Con las cantidades no alcanza.** Para no depender del historial, también tendría que guardar el PPC y los pesos.
4. **Borrar historial se planifica, y la opción D lo resuelve.** Antes de borrar se guarda la foto: cantidad, PPC y
   pesos a una fecha; después se suman solo las órdenes posteriores. Se escribe una sola vez y lo nuevo se sigue leyendo
   de `orders`, así que el punto 2 no aparece.

Hoy la API no borra órdenes, así que la foto no se construye: queda como paso obligatorio antes de borrar o archivar.
