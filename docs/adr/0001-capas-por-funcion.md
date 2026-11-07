# ADR 0001 — Capas agrupadas por función, no hexagonal

> Estado: **aceptada** · 2026-11-07 · Se aplica en el [RFC, sección 2](../03-rfc.md#2-arquitectura)

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
  habría que simular el repository. Acá no hace falta: los services se prueban contra Postgres real a propósito,
  porque ahí está lo delicado.

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
- Ejemplo, mandar una orden: con A son `OrderController` → `OrderService` → `OrderRepository`. Con B se suman un
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
- Se prueba en dos niveles: los cálculos, con tests unitarios; los services y los repositories, contra Postgres real
  (RFC, sección 10).
- **Cuándo revisarla:** si la API tuviera que mandar órdenes a un mercado real (por ejemplo, a BYMA). Ese sí es un
  sistema externo que se va a querer simular en los tests y poder cambiar: ahí conviene un puerto para el mercado. Se
  agrega ese puerto, no toda la arquitectura.
