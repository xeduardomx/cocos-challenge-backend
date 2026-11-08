# Mediciones

Lo que el RFC afirma con un número, medido o probado, con su script y su salida. Todo corrió en copias locales, nunca en
la base de Cocos, y con los datos en memoria: los tiempos sirven como orden de magnitud.

| Resultado | Qué se midió o se probó | Cómo se repite |
|---|---|---|
| [Índices, precios y búsqueda](03-resultados.md) | qué índice usa cada consulta y cuánto tarda con 2 millones de órdenes; el último precio, la búsqueda y el borrado de un activo (RFC 3.5) | [`03-indices.sql`](03-indices.sql) y [`03-precios-y-busqueda.sql`](03-precios-y-busqueda.sql) |
| [Trampas del esquema y Flyway](03-pruebas-resultados.md) | las trampas de las reglas nuevas y de la hora, y las migraciones sobre una base que ya tiene datos (RFC 3.2, 3.4 y 3.6) | [`03-trampas.sh`](03-trampas.sh) y [`03-flyway.sh`](03-flyway.sh), con [`03-esquema.sql`](03-esquema.sql) |
| [El último precio](04-resultados.md) | tres formas de pedir el último precio de 20 acciones, con 10 años de precios (RFC 4.2) | [`04-consultas.sql`](04-consultas.sql) |
| [El turno del usuario](05-resultados.md) | dos ventas a la vez (sin turno, con turno y en `REPEATABLE READ`), dos pedidos con el mismo identificador y dos cancelaciones (RFC 5) | [`05-turno.sh`](05-turno.sh) |
| [Tiempo de respuesta con volumen](06-tiempo-de-respuesta.md) | la API completa con k6 y 2 millones de órdenes (RFC 8.4) | `scripts/run-load-test.sh` |
| [Volver a los datos originales](10-resultados.md) | cuánto tarda restaurar la base antes de cada test (RFC 10.2) | [`10-volver-a-los-originales.sql`](10-volver-a-los-originales.sql) |
