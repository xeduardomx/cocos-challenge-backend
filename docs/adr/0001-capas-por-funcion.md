# ADR 0001 — Capas agrupadas por función, no hexagonal

> Estado: **aceptada** · 2026-11-07, revisada el 2026-11-10 · Se aplica en el [RFC, sección 2](../03-rfc.md#2-arquitectura)

## Contexto

- La API tiene cinco funciones (PRD, sección 2) y habla con un solo sistema: su base. No hay mercado real, pagos ni
  mensajería: el enunciado dice que no hace falta simular el mercado.
- Lo más delicado es una transacción: verificar la plata o las acciones y grabar la orden, con las órdenes de un mismo
  usuario de a una (R-1, GEN-1). Eso depende de Postgres, y se prueba contra un Postgres real (RFC, principio 5).
- Los cálculos de plata (pesos disponibles, PPC, rendimiento, cantidad por monto, reservas) tienen muchos casos borde.
  Conviene probarlos solos, rápido y sin base.
- Cada capa o patrón extra se justifica o no entra (RFC, principio 6).

## Opciones

**A. Capas clásicas, agrupadas por función.** Cada función tiene su controller (recibe y valida), su service
(transacción y reglas) y su repository (SQL). Los paquetes son las funciones: `portfolio`, `instruments`, `orders`.

- A favor: es la menor cantidad de piezas que resuelve el problema, y todo lo de una función está junto: un cambio en
  el envío toca un solo paquete.
- En contra: el service usa el repository directamente, sin una interfaz en el medio. Para probar un service sin base
  habría que simular el repository. Acá casi no hace falta: lo delicado de los services se prueba contra Postgres real a
  propósito, y la simulación queda para casos puntuales.

**B. Hexagonal (puertos y adaptadores), o Clean Architecture, que sigue la misma idea.** El núcleo define interfaces
("puertos") para todo lo de afuera, y afuera se implementan ("adaptadores"): uno para HTTP, otro para la base. El
núcleo no conoce Spring ni la base.

- A favor: lo de afuera se cambia sin tocar el núcleo (otra base, un mercado real), y el núcleo se prueba con
  implementaciones falsas de los puertos.
- En contra:
  - Cada puerto tendría una sola implementación: no hay nada que cambiar.
  - Hay que copiar datos entre el modelo del núcleo y el de cada borde (la fila de la base, el JSON).
  - Lo más delicado no se puede probar con una implementación falsa: el bloqueo y la transacción hay que probarlos
    contra Postgres igual.
- Ejemplo, mandar una orden: con A son `OrderController` → `OrderSubmissionService` → `OrderRepository`. Con B se suman un
  puerto de entrada (el caso de uso), un puerto de salida (el repositorio) con su adaptador para la base, y la
  conversión entre la orden del núcleo y la fila de `orders`.

**C. Capas agrupadas por tipo** (`controllers/`, `services/`, `repositories/`). Las mismas piezas que A, ordenadas de
otra forma.

- En contra: lo de una función queda repartido en tres carpetas, y nada separa lo de las órdenes de lo del portfolio.

## Decisión

**A, con dos reglas que toman de B lo que sí sirve acá:**

1. **Los cálculos van en clases de Java puro,** sin Spring ni base, con tests unitarios. Es la ventaja de B que
   importa en este challenge: la lógica de la plata, aislada y probada caso por caso.
2. **La cuenta del usuario se calcula en un solo lugar,** el paquete `account` (pesos, reservas, disponibles y
   posiciones), y la usan el portfolio y el envío (RFC, principio 4). Si cada uno tuviera su fórmula, el portfolio
   podría mostrar $627.500 disponibles y el envío validar contra otro número.

## Consecuencias

- Las dependencias van en un solo sentido: controller → service → repository. Los cálculos no dependen de nada.
- Se prueba en tres niveles: los cálculos, con tests unitarios; los services y los repositories, contra Postgres
  real; y las cinco funciones de punta a punta, por HTTP (RFC, sección 10).
- **Cuándo revisarla:** si la API tuviera que mandar órdenes a un mercado real (por ejemplo, a BYMA). Ese sí es un
  sistema externo que se va a querer simular en los tests y poder cambiar: ahí conviene un puerto para el mercado. Se
  agrega ese puerto, no toda la arquitectura.

## Revisión (fase 8, 2026-11-10)

Al repasar el código antes de entregar, el autor vio que la decisión no se leía en las carpetas. Las funciones estaban
separadas, pero adentro de cada una había clases de capas distintas en la misma carpeta. Por ejemplo, `account` tenía
juntos el service, el repository, la configuración, los records y los errores. En la raíz de `orders` estaban
`OrderRepository`, `NewOrder`, `OrderMetrics` y `OrdersConfiguration`. Solo `web` tenía su propia carpeta.

**Se mantiene A, y ahora se ve en los paquetes.** Adentro de cada función hay un paquete por capa, con los mismos
nombres en todas: `web`, `service`, `domain`, `repository`, `config`, `error` y `observability` (RFC, sección 2).

- **Qué cambió:** 57 clases y 23 tests cambiaron de paquete. No cambió ninguna línea de lógica: solo las líneas
  `package` e `import`.
- **Cómo se verificó:** los mismos 372 tests y la misma cobertura, clase por clase, antes y después. Además se levantó
  todo con `docker compose up --build` y se corrieron la prueba de humo, la colección de Postman y la medición con
  volumen ([detalle](../mediciones/08-verificacion-final.md#después-de-reordenar-los-paquetes-2026-11-10)).
- **Por qué no la opción C** (una carpeta por tipo para toda la API): vale lo mismo que arriba. Lo de las órdenes
  quedaría repartido en siete carpetas y mezclado con lo del portfolio, y dejaría de verse en los paquetes que
  `account` e `instruments` no dependen de `orders`.
- **Qué se perdió:** en `orders`, las carpetas por caso de uso (`submission`, `cancellation` y `listing`). Los services
  llevan el caso de uso en el nombre, por ejemplo `OrderSubmissionService`, y `domain` conserva la división por tema.
