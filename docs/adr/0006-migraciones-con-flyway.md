# ADR 0006 — Migraciones con Flyway, sobre una base con datos y sin historial

> Estado: **aceptada** · 2026-11-07 · Se aplica en el [RFC, sección 3.6](../03-rfc.md#36-migraciones-y-neon)

## Contexto

- Neon ya tiene las tablas y los datos de `database.sql`, y no tiene historial de migraciones (revisado en solo
  lectura). Se trata como producción: mientras se desarrolla, solo se lee. En la fase 8 se le aplicaron los cambios, una sola
  vez ([`mediciones/08-migracion-neon.md`](../mediciones/08-migracion-neon.md)).
- La copia local y los tests tienen que recorrer el mismo camino que Neon, para que lo que se pruebe sea lo que va a
  pasar.
- Una migración que falla no puede dejar a Neon a medio cambiar.
- No hay un ORM que cree las tablas (ADR 0002).

## Opciones

**A. Flyway.** Cada migración es un archivo SQL numerado, y Flyway anota en su propia tabla cuáles se aplicaron.

- A favor: viene integrado con Spring Boot (Spring Boot 4.1.2 trae la versión 12.4.0), y las migraciones salen del SQL
  que se probó en `mediciones/03-esquema.sql`.
- En contra: agrega una tabla a la base, `flyway_schema_history`.

**B. Liquibase.** Acepta cambios en SQL, XML o YAML, y su fuerte es describir un cambio una vez para cualquier motor
de base y poder deshacerlo (en Flyway, deshacer es de la edición paga). Acá hay un solo motor, y los cambios van
siempre hacia adelante.

**C. Scripts a mano, sin herramienta.** No dejan registro de qué se aplicó: el pase a Neon dependería de acordarse.

**Dos variantes para arrancar sobre la base con datos:**

- `database.sql` es la versión 1, y Flyway la anota sin tocarla (`baseline-on-migrate`). La copia local se arma igual
  que Neon: Postgres carga `database.sql` y después se migra.
- Copiar `database.sql` como la versión 1. Una base vacía se podría migrar sola, pero la copia local seguiría otro
  camino que Neon.

## Decisión

**A, con `database.sql` como versión 1.**

- `baseline-on-migrate`, con la versión 1 descrita como "database.sql de Cocos".
- `group`: todas las migraciones pendientes van en una sola transacción.
- Spring Boot prende Flyway por defecto; acá se apaga, y lo prenden el compose de la copia local y los tests. A Neon
  se lo migró a propósito, una vez, en la fase 8.

## Consecuencias

- **Probado con Flyway 12.4.0 en la copia local** (Postgres 18.6;
  [`mediciones/03-pruebas-resultados.md`](../mediciones/03-pruebas-resultados.md)):
  - anotó la versión 1, aplicó la 2, y una segunda corrida no hizo nada;
  - una migración que falla se deshace entera;
  - sin `group`, una migración que falla deja aplicadas las anteriores; con `group`, no queda ninguna;
  - una base vacía no se puede migrar, porque las migraciones necesitan las tablas de `database.sql`. Falla y se deshace todo.
- Apuntar la API a Neon no lo migra por accidente.
- En una tabla grande, cambiar el tipo de una columna o crear un índice habría que planificarlo aparte (RFC,
  sección 3.6).
- **Cuándo revisarla:** si los mismos cambios tuvieran que correr en varios motores de base, o si hiciera falta
  deshacer migraciones ya aplicadas. Ahí Liquibase volvería a tener sentido.
