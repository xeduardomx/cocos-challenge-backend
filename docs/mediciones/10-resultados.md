# Resultados de la prueba de la sección 10 del RFC

> Corrida del 2026-11-07 en una laptop, con Postgres 18.6 en Docker. Salida de
> [`10-volver-a-los-originales.sql`](10-volver-a-los-originales.sql), sobre la misma copia que las otras mediciones
> (RFC, sección 3.5).

```text
== Después de volver: la orden 7, los conteos y el próximo id de orden
 orden_7 | ordenes | usuarios | proximo_id_de_orden
---------+---------+----------+---------------------
 NEW     |      11 |        4 |                  12
== Tiempo de 50 vueltas a los originales
NOTICE:  promedio por vuelta: 4.0 ms
```
