# Métricas

Qué mide la API, dónde se ve y qué dio al medirla con volumen.

## El tablero

Con todo levantado está en Grafana, en <http://localhost:3000>. Es la página de inicio y no pide usuario ni clave.

![Tablero de Grafana de la API, con tráfico de ejemplo](img/tablero-grafana.png)

La captura es de la API corriendo en local, con tráfico de ejemplo: los números cambian en cada corrida.

| Panel | Qué contesta |
|---|---|
| Órdenes por estado, tipo y lado | cuántas órdenes se ejecutan, quedan pendientes o se rechazan |
| Rechazos por motivo | por qué se rechazan: faltan pesos, faltan acciones o el activo no cotiza |
| Latencia p95 por función | cuánto tarda cada función, sin contar `/actuator/*` |
| Errores por función | qué función devuelve errores, y con qué código |
| Espera del turno, p95 | cuánto espera un envío el turno de su usuario: si crece, hay órdenes del mismo usuario chocando |

Las órdenes y la espera del turno se ven desde el primer pedido. La latencia y los errores de cada función aparecen
desde el segundo, con al menos 15 segundos entre los dos: Prometheus necesita dos lecturas para calcularlos.

## Qué mide la API

En texto, tal como las lee Prometheus, están en <http://localhost:8080/actuator/prometheus>.

--8<-- "03-rfc.md:metricas"

Por qué estas y cómo se eligió mostrarlas: [RFC, sección 8](../03-rfc.md#8-observabilidad) y
[ADR 0007](../adr/0007-metricas-con-prometheus-y-grafana.md).

## El tiempo de respuesta

El objetivo es un percentil 95 por debajo de 100 ms en las cinco funciones, con 2 millones de órdenes en la tabla y
usuarios de hasta 10.000 órdenes ([RFC, sección 8.4](../03-rfc.md#84-el-objetivo-de-tiempo-de-respuesta-gen-8)). Cada
escenario manda 10 pedidos por segundo durante un minuto:

--8<-- "mediciones/06-tiempo-de-respuesta.md:resultado-volumen"

Es una carga moderada, en una computadora de escritorio y con la base en memoria: sirve como orden de magnitud, y no
dice cuántos pedidos aguanta la API. La máquina, los datos, los límites y cómo repetirla están en
[la medición completa](../mediciones/06-tiempo-de-respuesta.md).

## En producción, con New Relic

Se cambia el registro de Micrometer por `micrometer-registry-new-relic`, con la clave y la cuenta por variables de
entorno. Las métricas y su código no cambian.
