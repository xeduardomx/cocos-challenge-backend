# Resultados de la medición de la sección 4 del RFC

> Corrida del 2026-11-07 en una laptop, con Postgres 18.6 en Docker y los datos en memoria: sirven como orden
> de magnitud. Salida de [`04-consultas.sql`](04-consultas.sql), sin las líneas de control de `psql`. Se repite como las
> de la sección 3 (RFC, sección 3.5), con `04-consultas.sql` en lugar de los scripts de índices.

```text
== Último precio de 20 acciones, con 10 años de precios (mediana en ms)
 filas_de_precios
           164430
                  consulta                  |   ms   |              plan
--------------------------------------------+--------+--------------------------------
 a. DISTINCT ON sobre toda la tabla         | 79.515 | tabla entera
 b. DISTINCT ON solo de las 20 acciones     | 26.268 | marketdata_instrument_date_key
 c. acción por acción (LATERAL ... LIMIT 1) |  0.046 | marketdata_instrument_date_key
```
