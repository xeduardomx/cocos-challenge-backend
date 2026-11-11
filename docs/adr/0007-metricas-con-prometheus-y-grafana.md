# ADR 0007 — Métricas con Micrometer, y Prometheus y Grafana en el `docker compose`

> Estado: **aceptada** · 2026-11-07 · Se aplica en el
> [RFC, sección 8](../03-rfc.md#8-observabilidad)

## Contexto

- La vara de calidad de `CLAUDE.md` pide métricas de negocio (órdenes por tipo, lado y estado) y técnicas (latencia y
  errores por función), y un tablero. El plan de fases decía "New Relic simulado".
- Soporte tiene que poder ver cuántas órdenes se ejecutan, quedan pendientes o se rechazan, y por qué (GEN-4).
- Quien evalúa corre todo en su máquina, con un comando, y no debería tener que crear cuentas.

## Opciones

**A. Prometheus y Grafana, en el mismo `docker compose`.** Prometheus lee `/actuator/prometheus` de la API, y Grafana
arranca con la fuente de datos y el tablero cargados desde archivos del repo.

- A favor: muestra las métricas reales de la API corriendo, sin cuentas ni pasos manuales.
- En contra: dos contenedores más en el compose.

**B. New Relic de verdad, con una cuenta gratuita.** Quien evalúa necesitaría una cuenta y una clave para ver el
tablero.

**C. Un tablero simulado.** Mostraría números inventados.

**D. Solo `/actuator/prometheus`, sin tablero.** Las métricas existen, pero hay que leerlas en texto.

## Decisión

**A.** Micrometer, que viene con Spring Boot, mide; Prometheus junta; Grafana muestra. Las métricas y sus etiquetas
están en el RFC (sección 8.2). Para producción con New Relic se cambia el registro de Micrometer
(`micrometer-registry-new-relic`, con la clave y la cuenta por variables de entorno): las métricas y su código no
cambian.

## Consecuencias

- El tablero (órdenes por estado, tipo y lado; rechazos por motivo; latencia y errores por función; espera del turno)
  se arma en la fase 6 y queda en el repo. En la fase 8 los errores pasaron a dos paneles, los del servidor (5xx) y los
  del cliente (4xx), porque no significan lo mismo; los paneles que cuentan pasaron a leer el contador desde que arrancó
  la API; y quedó escrito qué se alertaría con estas métricas
  ([RFC, secciones 8.3 y 8.5](../03-rfc.md#83-cómo-se-ven-prometheus-y-grafana-en-el-mismo-docker-compose); revisado el
  2026-11-11).
- Las etiquetas de las métricas toman pocos valores. El usuario y la orden no van como etiqueta.
- Con `docker compose up api` se puede levantar solo la API con su base, sin Prometheus ni Grafana.
- **Cuándo revisarla:** al pasar a producción, donde la empresa ya tenga su herramienta (New Relic u otra). El código
  de las métricas se mantiene.
