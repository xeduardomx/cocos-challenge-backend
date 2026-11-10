# El challenge, de punta a punta

Una API en Java con el portfolio de un usuario, la búsqueda de activos y el envío de órdenes, sobre la base que mandó
Cocos. Acá está el camino completo: qué se entendió del enunciado, qué se decidió y por qué, cómo se construyó y qué dio
al medirlo. El código y cómo levantarlo están en el
[repositorio](https://github.com/xeduardomx/cocos-challenge-backend).

## Por dónde empezar

| Si tenés | Mirá |
|---|---|
| 2 minutos | las 12 preguntas de esta página |
| 10 minutos | los [diagramas](portal/diagramas.md) y el [resumen del RFC](03-rfc.md#resumen) |
| Una hora | el [análisis](01-analisis.md), el [PRD](02-prd.md) y el [RFC](03-rfc.md) |
| Ganas de probarla | el [runbook](portal/runbook.md) |

## El camino

Cada fase cerró con su documento, y ese documento se aprobó antes de seguir.

| Fase | Qué salió |
|---|---|
| 1. Análisis del enunciado y de los datos reales | [análisis](01-analisis.md) y [registro de decisiones](01-decisiones.md) |
| 2. Qué tiene que hacer la API | [PRD](02-prd.md) |
| 3. Cómo se construye | [RFC](03-rfc.md) y [ADR](adr/README.md) |
| 4. En qué orden | [plan](04-plan.md) |
| 5. Implementación, con el test primero | la API y sus tests |
| 6. Observabilidad y medición | [métricas](portal/metricas.md) y [mediciones](mediciones/README.md) |
| 7. Este portal | se genera desde los mismos documentos, así nunca dicen cosas distintas |
| 8. Revisión final | [verificación sobre un clon limpio](mediciones/08-verificacion-final.md) |

## Las decisiones clave en 12 preguntas

### 1. ¿Cómo se evita gastar dos veces la misma plata o las mismas acciones?

Las órdenes de un mismo usuario se procesan de a una: cada envío toma el turno de su usuario, un lock sobre su fila, y
recién ahí lee la cuenta. Sin turno, dos ventas simultáneas de 15 acciones teniendo 20 pasan las dos y dejan la posición
en −10. Con turno, la segunda queda `REJECTED` y la posición termina en 5.

[RFC 5.2](03-rfc.md#52-el-turno-del-usuario) · [ADR 0004](adr/0004-turno-del-usuario.md)

### 2. ¿Qué pasa si se corta la red y la app reintenta la orden?

Cada orden trae un identificador que genera el cliente. Si la misma orden llega de nuevo, la API devuelve la original
con un 200, sin volver a evaluarla. Si ese identificador llega con otro contenido, responde 422 y no graba nada.

¿Y si la app, por un error suyo, manda la misma orden con un identificador nuevo? Ya no es un reintento, y la API no
puede saber si es un error o una decisión. Una segunda red frena el caso más común: si la orden es igual a otra que el
usuario mandó hace menos de 5 segundos, responde 409 y avisa cuál es. Es una consulta más dentro del turno, con la
huella del pedido que ya se guarda. En EE. UU., la SEC les exige ese control a los brokers, y los procesadores de pagos
lo traen prendido. Ninguno fija un número: la ventana es configurable.

[RFC 5.3](03-rfc.md#53-reintentos) · [ADR 0005](adr/0005-reintentos-con-identificador-y-huella.md) ·
[RFC 5.5](03-rfc.md#55-la-misma-orden-con-otro-identificador) ·
[ADR 0009](adr/0009-ordenes-iguales-con-otro-identificador.md)

### 3. ¿Por qué las posiciones se calculan y no se guardan?

Para que cada dato viva en un solo lugar: una tabla de posiciones sería una copia que puede diferir de las órdenes, y
las reservas y el precio promedio igual salen de ellas. La duda era si calcular es lento, y se midió: con un índice, 1,2
ms en la base para un usuario de 10.000 órdenes, con 2 millones en la tabla.

Antes de entregar se volvió a evaluar la tabla por otra razón: el cálculo necesita todo el historial, y si algún día se
borraran órdenes viejas daría mal. Se decidió mantenerlo, porque el enunciado indica *"utilizar todos los movimientos
pertinentes que hay en la tabla `orders`, utilizando la columna `size`"*, y porque una tabla no vería lo que no pasa por
la API, como un depósito o una orden límite que ejecute otro sistema. Antes de borrar historial, primero se guarda una
foto de la cuenta.

[A-4](01-decisiones.md#a-4--las-posiciones-se-calculan-desde-orders-no-se-guardan) ·
[ADR 0003](adr/0003-posiciones-calculadas.md) · [RFC 3.5](03-rfc.md#35-índices)

### 4. ¿Cuánta plata puede usar un usuario?

Su saldo menos lo que reservan sus compras pendientes. El usuario 1 tiene $753.000 y $125.500 reservados: puede operar
con $627.500. Sin ese descuento, la misma plata serviría para varias compras pendientes a la vez. Lo reservado sigue
siendo suyo, así que el valor total de la cuenta lo incluye.

[A-1](01-decisiones.md#a-1--los-pesos-disponibles-descuentan-las-compras-pendientes)

### 5. ¿Contra qué se mide el rendimiento?

Contra el precio promedio de compra. PAMP se compró a $930 y hoy vale $925,85: −0,45 %. Los otros métodos evaluados
daban −0,18 % y −0,14 %. La variación diaria va en un campo aparte.

[A-2](01-decisiones.md#a-2--qué-rendimiento-muestra-el-portfolio)

### 6. ¿Qué se hizo con la orden 11, que vendió más de lo que había?

Vendió 30 BMA con 20 en la cuenta y dejó la posición en −10. El dato no se toca: no se sabe si la venta fue real o un
error de carga, y corregirla no le corresponde al desarrollador. El portfolio la muestra tal cual, y de acá en adelante
la regla "no vender más de lo que se tiene" la hace cumplir el código.

[D-1](01-decisiones.md#d-1--orden-11-venta-de-30-bma-teniendo-20-posición-10)

### 7. ¿Cuándo una orden da error y cuándo queda rechazada?

Si con lo enviado no se puede armar la orden, es un error y no se graba nada. Si está bien armada pero la cuenta o el
mercado no dejan ejecutarla, se graba como `REJECTED`, responde 201 y trae el motivo. Un error dice "corregí el pedido";
un rechazo, "el pedido estaba bien, pero tu cuenta o el mercado no lo dejaron".

[A-6](01-decisiones.md#a-6--envío-de-órdenes-casos-borde) · [RFC 7.4](03-rfc.md#74-un-rejected-no-es-un-error)

### 8. ¿Qué significa "similar" en la búsqueda?

Que contiene el texto, palabra por palabra, en el ticker o en el nombre, sin importar mayúsculas ni acentos: "rio"
encuentra "Molinos Río de la Plata". No tolera errores de tipeo, porque habría que inventar cuánto parecido alcanza.

[A-5](01-decisiones.md#a-5--qué-significa-similar-en-la-búsqueda)

### 9. ¿Qué se agregó que el enunciado no pedía?

Cancelar y listar órdenes, porque sin cancelación la plata reservada por una orden límite quedaría bloqueada para
siempre, y un identificador obligatorio en cada orden, por los reintentos. Las transferencias por API quedaron afuera:
no se piden y, sin autenticación, cualquiera podría crear plata.

[Inventario de lo agregado](01-decisiones.md#inventario-lo-que-agregamos-o-cambiamos-respecto-del-enunciado) ·
[A-7](01-decisiones.md#a-7--cancelación-listado-de-órdenes-y-transferencias)

### 10. ¿Por qué capas y SQL directo, y no hexagonal ni JPA?

Lo único de afuera es la base, y lo más delicado se prueba contra Postgres real de todas formas: los puertos de la
arquitectura hexagonal no aportaban. Las consultas importantes son SQL propio de Postgres (`FOR UPDATE`, `LATERAL`,
`unaccent`), y JPA agrega comportamiento escondido justo en el camino más delicado.

En el código, cada función es un paquete (`account`, `instruments`, `portfolio` y `orders`), y adentro hay un paquete
por capa: `web`, `service`, `domain` y `repository`, entre otros.

[ADR 0001](adr/0001-capas-por-funcion.md) · [ADR 0002](adr/0002-sql-directo-con-jdbcclient.md)

### 11. ¿Se tocó la base de Cocos?

Se trata como producción: mientras se desarrolló, solo se leyó. La API y los tests corren contra una copia local,
armada con `database.sql` y nuestras migraciones: reglas que miran una fila, tres columnas nuevas en `orders` y un
índice nuevo para las consultas. Al final, con todo probado, se le aplicaron esas migraciones una sola vez, como un pase a
producción: el enunciado permite ajustar la base si se justifica. Los datos no cambiaron.

[Base de Neon](01-decisiones.md#base-de-neon-se-trata-como-producción) ·
[RFC 3](03-rfc.md#3-modelo-de-datos-y-cambios-a-la-base) · [ADR 0006](adr/0006-migraciones-con-flyway.md) ·
[el pase a Neon](mediciones/08-migracion-neon.md)

### 12. ¿Cómo se sabe que funciona y que aguanta?

Tests en tres niveles, con los casos del PRD: los cálculos en Java puro, y las consultas y las funciones de punta a punta
contra un Postgres real. Los 27 casos del envío son el test funcional que pide el enunciado, y el build falla si la
cobertura baja de 85 % de las líneas o 80 % de las ramas. Y se midió con 2
millones de órdenes: el objetivo era un percentil 95 por debajo de 100 ms, y el peor caso, el envío de un usuario con
10.000 órdenes, dio 61,15 ms. Es una carga moderada en una computadora de escritorio: sirve como orden de magnitud.

[RFC 10](03-rfc.md#10-estrategia-de-tests) · [RFC 8.4](03-rfc.md#84-el-objetivo-de-tiempo-de-respuesta-gen-8) ·
[la medición](mediciones/06-tiempo-de-respuesta.md)
