# ADR 0003 — Las posiciones se calculan desde `orders`, sin tabla de posiciones

> Estado: **aceptada** · 2026-11-07 · Viene de A-4 (`01-decisiones.md`) y se aplica en el
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
- **Cuándo revisarla:** si el portfolio de los usuarios del objetivo (hasta 10.000 órdenes) superara los 100 ms de la
  sección 8.4. El paso siguiente sería D, la foto a una fecha. Con eso, las pendientes pasarían a ser la consulta más
  lenta, y ahí entra el índice parcial que ya está medido (sección 3.5).
