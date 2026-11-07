# CLAUDE.md — Cocos Capital, challenge backend

Challenge técnico para el puesto de **dev senior backend en Cocos Capital**, recibido por mail el
2026-11-06. Cocos aclaró que valora la calidad por sobre la velocidad. No se hacen consultas sobre el
enunciado: cada ambigüedad se investiga, se decide y se documenta (ver `docs/01-decisiones.md`).

## Qué pide Cocos (del mail)

- Los **3 endpoints** de `docs/00-fuentes/backend-challenge.md` (portfolio, búsqueda de activos, envío de orden).
- **En Java** (el enunciado dice Node.js: el mail lo pisa).
- Entregable: **link al repositorio** + que **se pueda ejecutar localmente**.
- Se puede usar IA, pero evalúan **cómo pensás, qué decisiones tomaste y qué camino hiciste**.
  Consecuencia: cada decisión queda escrita con su porqué, y no entra nada que el autor no pueda explicar
  y defender en la entrevista. La historia de git también cuenta el camino.

## Fuentes (no tocar)

- `docs/00-fuentes/backend-challenge.md` — enunciado original (repo `github.com/cocoscap/cocos-challenge`;
  el link del mail apunta a `cocos-capital/...`, que redirige ahí).
- `docs/00-fuentes/database.sql` — esquema y datos. Verificado el 2026-11-06: coincide con la base de Neon
  (users 4, instruments 66, marketdata 126, orders 11).

## Estructura del repo (decidida el 2026-11-06)

El análisis y las decisiones tienen que verse en el repo, separados del código. En la raíz:

- `docs/`: el camino (análisis, PRD, RFC, ADR, portal). `00-fuentes/` no se toca.
- `api/`: el proyecto Java (`pom.xml`, `src/`, `Dockerfile`). Se crea en la fase 5.
- `docker-compose.yml` y `README.md`. El README es la puerta de entrada: cómo correrlo y un mapa a los documentos.

## Base de datos

- La URL está en `.env` (`DATABASE_URL`, gitignoreado). **Nunca** escribir credenciales en otro archivo.
- **La base de Neon es de Cocos y se trata como producción: mientras desarrollamos, solo lectura.** Explorar sí;
  insertar, borrar o migrar, no. Desarrollo y tests van contra una copia local en Docker armada con `database.sql`
  y nuestras migraciones. Si al final se aplican las migraciones en Neon se decide en la fase 8
  (ver `docs/01-decisiones.md`).
- Para explorarla hace falta un `psql` moderno (Neon usa `channel_binding` y SNI).

## Cómo se trabaja (ciclo de desarrollo)

Fase por fase. Cada fase **cierra con su documento** y el autor lo aprueba antes de seguir.
**No se escribe código de producción sin el RFC aprobado.**

| # | Fase | Entregable | Skill de superpowers |
|---|------|-----------|----------------------|
| 1 | Análisis del enunciado y de los datos reales: ambigüedades, contradicciones, trampas, supuestos y decisiones | `docs/01-analisis.md` + `docs/01-decisiones.md` | — (análisis, sin código) |
| 2 | PRD: problema, alcance y fuera de alcance, requisitos, criterios de aceptación (Given/When/Then) | `docs/02-prd.md` | brainstorming |
| 3 | RFC: arquitectura, modelo y cambios a la base, contratos de API, consistencia/concurrencia, errores, observabilidad, estrategia de tests, diagramas | `docs/03-rfc.md` + `docs/adr/` | brainstorming |
| 4 | Plan de implementación en tareas chicas | `docs/04-plan.md` | writing-plans |
| 5 | Implementación con TDD | código + tests | test-driven-development, executing-plans |
| 6 | Observabilidad: métricas, dashboard (New Relic simulado) | código + dashboard | — |
| 7 | Portal HTML tipo Confluence: análisis, PRD, RFC, diagramas, runbook, métricas | `docs/portal/` | — |
| 8 | Revisión y entrega: review, verificación, README, repo público, mail | repo + mail | requesting-code-review, verification-before-completion, finishing-a-development-branch |

## Ramas, commits y repo (decidido el 2026-11-06)

- **`main`:** solo lo entregable. Recibe el commit inicial y, en la fase 8, el merge final desde `develop`
  (tag `v1.0.0`).
- **`develop`:** donde se integra cada fase. Sale de `main`.
- **Una rama por fase, desde `develop`,** con prefijo según el tipo: `docs/phase-1-analysis`, `docs/phase-2-prd`,
  `docs/phase-3-rfc`, `docs/phase-4-plan`, `feat/phase-5-implementation`, etc. Cierra con un PR a `develop`.
- **Fases de documentos (1 a 4): una sola rama, sin sub-ramas.** Es un documento, un autor, y se revisa entero.
- **Fases de código (5 y 6): una sub-rama por tarea del plan,** desde la rama de la fase y con PR a ella.
  PR encadenados solo cuando una tarea depende de otra que todavía no se mergeó; si se puede mergear la anterior
  primero, mejor.
- **Merge siempre con merge commit (`--no-ff`), nunca squash:** el squash borra el camino, y el camino se evalúa.
- **Commits** con Conventional Commits, en inglés y chicos. Commits y push, solo cuando el autor los pide.
- **Repo:** en el GitHub personal del autor, privado mientras se trabaja y público en la fase 8, al entregar.

## Vara de calidad (no negociable)

**Código**
- Sin números mágicos ni strings sueltos: constantes con nombre, enums para `side`/`status`/`type`,
  configuración en `application.yml` vía `@ConfigurationProperties`.
- Plata: `BigDecimal` siempre, con escala y redondeo explícitos; nunca `double`/`float`.
- Sin secretos ni datos personales en el repo: los secretos, por variables de entorno; lo personal, fuera del repo.
- Validación en el borde (Bean Validation) y errores con formato estándar (Problem Details, RFC 9457).
- Logs estructurados con id de request; sin datos personales (nada de emails en logs).
- Métricas con Micrometer: de negocio (órdenes por tipo, lado y estado) y técnicas (latencia y errores por endpoint).
- Formato y análisis estático automáticos que rompen el build (herramientas: se eligen en el RFC).

**Tests**
- TDD: primero el test que falla, después el código.
- Dominio con tests unitarios; integración contra Postgres real (Testcontainers + `database.sql`);
  el **test funcional del envío de orden** es requisito explícito del enunciado.
- Cobertura medida (JaCoCo) con un piso que se fija en el RFC.
- Los tests **nunca** tocan la base de Neon.

**Proceso**
- Lo simple gana: cada capa, patrón o librería extra se justifica en un ADR o no entra.
- Commits chicos con Conventional Commits; ramas y merges según "Ramas, commits y repo".
- CI en GitHub Actions (build + tests + análisis) en cada push.
- Ejecutable con un comando (`docker compose up`) y README con los pasos.
- Documentación en español; código, nombres y commits técnicos en inglés.

## Decisiones abiertas (se deciden en el análisis o el RFC, no antes)

- ¿Arquitectura hexagonal o capas clásicas? Se decide por lo que el problema pide, no por moda.
- Versión de Spring Boot; JPA vs JDBC/jOOQ.
- Cambios a la base (el enunciado los permite si se justifican). Los candidatos ya están juntados en
  `docs/01-decisiones.md` ("Pendientes acumulados para el bloque del esquema") y se deciden en el RFC.
- Cómo se muestran las métricas: Prometheus + Grafana local, New Relic real (cuenta free) o dashboard simulado.
- Formato del portal HTML y de los diagramas (Mermaid, PlantUML…).
- Nice to have del enunciado: **decidido en el PRD**, se entrega una colección de Postman con el recorrido completo
  (`docs/02-prd.md`, sección 2).
