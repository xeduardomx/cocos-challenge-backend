# Resultados de las pruebas de la sección 5 del RFC

> Corrida del 2026-11-10 en una PC, con Postgres 18.6 en Docker. Salida de [`05-turno.sh`](05-turno.sh), que
> simula en SQL lo que hace la API: dos pedidos casi a la vez, que leen la cuenta, esperan 1 segundo y graban.
> Corre en una copia nueva, con `database.sql` y `03-esquema.sql`, sin los datos de `03-indices.sql` (RFC, sección 3.5).
> Las pruebas A a F dieron lo mismo que en la corrida del 2026-11-07. La G se agregó al revisar el
> [ADR 0004](../adr/0004-turno-del-usuario.md#revisión-fase-8-2026-11-10): hace la venta en un solo comando, sin
> transacción ni turno.

```text
== Dos ventas de 15 BMA a la vez, teniendo 20
A. sin turno, READ COMMITTED | venta 1: FILLED (vio 20) | venta 2: FILLED (vio 20) | posición final: -10
B. con turno, READ COMMITTED | venta 1: FILLED (vio 20) | venta 2: REJECTED (vio 5) | posición final: 5
C. con turno, REPEATABLE READ | venta 1: FILLED (vio 20) | venta 2: FILLED (vio 20) | posición final: -10
== Dos pedidos a la vez con el mismo identificador
D. sin turno | pedido 1: crea la orden | pedido 2: ERROR:  duplicate key value violates unique constraint "orders_user_clientorderi | órdenes con ese identificador: 1
E. con turno | pedido 1: crea la orden | pedido 2: devuelve la orden original | órdenes con ese identificador: 1
== Dos cancelaciones a la vez de la orden 7
F. un solo UPDATE condicionado | pedido 1: cancela la orden 7 | pedido 2: no cambia ninguna fila | estado final: CANCELLED
== Dos ventas de 15 BMA a la vez, teniendo 20, cada una en un solo comando
G. un solo INSERT ... SELECT, sin turno | venta 1: FILLED (vio 20) | venta 2: FILLED (vio 20) | posición final: -10
```
