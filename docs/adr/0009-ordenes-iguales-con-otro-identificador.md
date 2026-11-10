# ADR 0009 — Órdenes iguales con otro identificador: una segunda red, con la huella y una ventana de 5 segundos

> Estado: **aceptada** · 2026-11-10 · Viene de R-6 (`01-decisiones.md`) y se aplica en el
> [RFC, sección 5.5](../03-rfc.md#55-la-misma-orden-con-otro-identificador)

## Contexto

- El identificador de la orden protege un reintento solo si la app repite el mismo (R-2, ADR 0005). Si la app genera uno
  nuevo en cada intento, o si cada toque del botón "Comprar" genera el suyo, a la API le llegan dos pedidos distintos,
  los dos válidos: se compran 20 PAMP en vez de 10. Estaba documentado como un límite, con la frase "ninguna API puede
  arreglar ese error". Al revisar la entrega (fase 8) se decidió mitigarlo.
- **La API no puede saber si la segunda orden es un error o una decisión.** Comprar 10 PAMP y enseguida 10 más es
  legítimo. Cualquier control frena de más o frena de menos.
- **Los dos errores no cuestan lo mismo.** Frenar una orden legítima cuesta esperar unos segundos y volver a enviarla.
  Dejar pasar un duplicado a mercado es una compra ejecutada dos veces, con plata real.
- **Casi todo lo necesario ya existe.** La huella del pedido se guarda en cada orden y no incluye el identificador, así
  que dos pedidos iguales tienen la misma (ADR 0005). El turno pone en fila las órdenes de un usuario (ADR 0004). Y el
  índice por (usuario, fecha, id) encuentra las últimas órdenes de un usuario sin recorrer la tabla (RFC, sección 3.5).

## Qué hacen otros

Investigado el 2026-11-10, con las fuentes al final. Ninguna norma ni producto fija un número: todos dejan el valor
configurable.

| Quién | Qué hace | Ventana o umbral |
|---|---|---|
| **SEC,** el regulador del mercado de valores de EE. UU. Regla 15c3-5 (2010) | obliga a los brokers con acceso al mercado a tener controles que frenen las órdenes erróneas antes de que entren: entre ellas, las que "indican órdenes duplicadas" (*"that indicate duplicative orders"*) | no fija número: lo decide cada broker |
| **FIX,** el protocolo con el que los brokers se conectan a los mercados | además del identificador (`ClOrdID`), tiene el campo `PossResend` (97): marca un mensaje que *"may contain information that has been sent under another sequence number"* | no aplica |
| **Cboe,** un mercado de EE. UU. (*Duplicative Order Protection*) | compara cada orden con la anterior del mismo participante: mismo símbolo, lado, precio y cantidad. Cuenta las iguales seguidas y, al llegar al tope, rechaza o deshabilita la conexión | sin ventana de tiempo: el tope lo elige cada firma, y 0 lo apaga |
| **Braintree,** un procesador de pagos de PayPal | rechaza un cobro con el mismo monto, la misma tarjeta y la misma orden de compra. Solo cuenta si el primer cobro fue exitoso | 30 segundos por defecto, configurable |
| **Authorize.Net,** un procesador de pagos | rechaza una transacción con los mismos datos que otra reciente (error 11) | 120 segundos por defecto; de 0, que lo apaga, a 8 horas |

Lo que se toma de ahí:

- **El problema es reconocido:** un regulador lo exige como control, y los procesadores de pagos lo traen prendido por
  defecto.
- **Hay dos familias.** Los pagos, donde opera una persona, usan una ventana de tiempo. Los mercados, donde operan
  algoritmos que mandan órdenes iguales a propósito, cuentan cuántas llegan seguidas. Una app de inversión para personas
  se parece a la primera.
- **El criterio de Braintree es el que se adopta:** un pedido solo es un posible duplicado si el primero fue exitoso.
  Si el primero falló, reintentar es legítimo.
- **La regla de la SEC no aplica a un broker argentino,** y no se encontró una norma local (CNV, BYMA) que trate las
  órdenes duplicadas en particular. Sirve como evidencia de que el riesgo es real, no como obligación.

## Opciones

**Dónde vive el control**

| Opción | A favor | En contra |
|---|---|---|
| A. No construir nada y dejarlo documentado | cero código | el duplicado pasa siempre |
| B. Un cache en la memoria de la API: usuario y huella, con vencimiento | no toca la base | con dos instancias de la API no funciona, porque cada una tiene su memoria. Hay que cuidar a mano dos pedidos simultáneos y no anotar una orden cuya transacción se deshizo. Suma código: el vencimiento y la limpieza |
| C. Un almacén compartido con vencimiento, como Redis | funciona con varias instancias y antes de llegar a la base | una pieza más para levantar, operar y probar. Sigue separado de la transacción: una orden anotada puede no haberse grabado |
| **D. Una consulta a `orders`, dentro del turno** | funciona con varias instancias, porque el dato está en la base. No hay carrera: el turno hace que la segunda siempre vea a la primera. Usa la huella y el índice que ya existen. Es una consulta y un `if` | una consulta más por orden aceptada (medido abajo) |

**Qué se hace con la orden parecida**

| Opción | A favor | En contra |
|---|---|---|
| **Rechazarla con un mensaje, sin grabarla; pasada la ventana, se acepta** | no cambia el pedido: es un error más del catálogo. La persona decide: si la quería, espera y la reenvía | una orden legítima espera unos segundos |
| Aceptarla si llega con una confirmación explícita (un campo como `confirmDuplicate`) | la persona decide sin esperar | cambia el contrato del envío, y sirve solo si la app arma esa pantalla. Queda como evolución |
| Grabarla como `REJECTED`, con un motivo nuevo | queda registrada | un `REJECTED` dice "tu cuenta o el mercado no lo dejaron" (A-6), y esto no es eso. Además gasta el identificador y suma un valor a una regla de la base |
| Ejecutarla y avisar en la respuesta | no frena a nadie | no evita el daño |

**La ventana.** Lo que frena cada valor, según cuándo llega la segunda orden:

| Falla | Cuándo llega la segunda | 2 segundos | 5 segundos |
|---|---|---|---|
| Doble clic, o un evento que se dispara dos veces | en menos de 1 segundo | la frena | la frena |
| Reintento automático con esperas de 1 y 2 segundos | a los 3 segundos | pasa | la frena |
| La app tarda y la persona toca de nuevo | a los 3 o 4 segundos | pasa | la frena |
| Reintento después de un tiempo de espera agotado | a los 30 segundos | pasa | pasa |

Una ventana de 30 o 120 segundos, como en los pagos, frenaría más, pero también a quien compra dos veces a propósito:
en un minuto una persona sí decide repetir una compra.

**Cuándo se mira: antes o después de evaluar la orden**

- **Antes:** toda orden igual a una reciente se frena. Dos compras iguales de $600.000 que llegan juntas, con $627.500
  disponibles (el caso 2 de GEN), darían una `FILLED` y un "posible duplicado".
- **Después, y solo si iba a quedar aceptada:** una orden que igual iba a quedar `REJECTED` se graba como siempre. No
  hace daño, y el enunciado pide grabar los rechazos. El caso 2 de GEN no cambia: una `FILLED` y una `REJECTED`.

**El código HTTP**

| Código | Por qué sí o por qué no |
|---|---|
| **409** | el pedido está bien armado, pero choca con el estado actual: hay una orden igual recién aceptada. Es el mismo código de "esa orden ya no se puede cancelar" |
| 429 | es el de los límites de frecuencia. Muchos clientes HTTP lo reintentan solos, y acá reintentar solo es justo lo que no se quiere |
| 422 | ya significa "ese identificador se usó con otro contenido" |

## Decisión

- **D: una consulta a `orders`, dentro del turno,** después de evaluar la orden y solo si iba a quedar `FILLED` o `NEW`.
- **Es un posible duplicado** si el mismo usuario tiene otra orden con la misma huella, en estado `FILLED` o `NEW`,
  grabada hace menos de 5 segundos. Las dos horas salen del reloj de la base: la de la orden anterior es la del momento
  en que se grabó (RFC, sección 3.4), y "ahora" es `clock_timestamp()`. Así no importa si los relojes de dos instancias
  de la API difieren.
- **Responde 409,** con el tipo `/problems/possible-duplicate-order` y el número de la orden anterior en
  `existingOrderId`. No graba nada, así que no gasta el identificador: el mismo pedido se puede reenviar tal cual.
- **La ventana es configurable** (`cocos.orders.duplicates.window`, 5 segundos por defecto). Con 0, el control queda
  apagado.

```sql
select id from orders
where userid = :userId
  and datetime > clock_timestamp() - :ventana
  and requestfingerprint = :huella
  and clientorderid <> :clientOrderId
  and status in ('FILLED', 'NEW')
order by datetime desc, id desc
limit 1
```

## Consecuencias

- **Qué frena y qué no:**

  | Caso | Resultado |
  |---|---|
  | La misma orden, con otro identificador, dentro de los 5 segundos | 409, y no se graba |
  | La misma, pasados los 5 segundos | se acepta: se toma como una decisión nueva |
  | La misma, con el mismo identificador | 200, con la orden original: es un reintento (ADR 0005) |
  | La primera quedó `REJECTED` | la segunda se evalúa normalmente |
  | La primera era una límite y se canceló | la segunda se acepta: la primera ya no reserva nada |
  | La segunda igual iba a quedar `REJECTED` | se graba `REJECTED`, como siempre |
  | Otro usuario manda la misma orden | se acepta: el control es por usuario |
  | El mismo pedido escrito de otra forma: 10 PAMP por cantidad, o $9.258,50 por monto | se acepta: son dos huellas distintas |

- **Es una segunda red, no la protección principal.** Frena el duplicado que llega enseguida. Un reintento que llega a
  los 30 segundos con otro identificador sigue pasando: contra eso, la app tiene que repetir el identificador (ADR 0005).
- **La app tiene que mostrar el aviso y no reenviar sola.** Si ante un 409 espera y reenvía por su cuenta, el duplicado
  entra igual, 5 segundos más tarde.
- **Una orden legítima igual a otra espera unos segundos.** Para una persona es casi imposible repetir una orden en
  menos de 5 segundos a propósito. Una cuenta que opere con algoritmos sí lo haría: ahí se cambia la ventana, o se la
  apaga con 0.
- **Cuántas veces actúa** se ve sin una métrica nueva: son las respuestas 409 del envío, que `http_server_requests`
  ya cuenta por dirección, método y código (RFC, sección 8.2). Es el dato para ajustar la ventana.
- **Las órdenes viejas no tienen huella,** así que nunca cuentan como la primera.
- **Una orden con el mismo identificador no cuenta** (encontrado al implementar). Esa es un reintento, y lo contestan
  la búsqueda por identificador o la regla de unicidad (ADR 0005). Sin esa condición, el test del camino sin turno
  recibía un 409 en vez de reintentar y devolver la orden original (RFC, sección 5.3).
- **La prueba de carga mandaba la misma compra 10 veces por segundo** para el mismo usuario. Ahora cada pedido rota los
  centavos del monto o del precio, y así la medición incluye el costo de la consulta nueva
  ([`mediciones/06-tiempo-de-respuesta.md`](../mediciones/06-tiempo-de-respuesta.md)).
- **Queda afuera, documentado:**
  - un límite de órdenes por usuario por segundo, que es el control para una integración que entra en un bucle. En
    producción lo pone la entrada a la API (RFC, sección 9);
  - la confirmación explícita, para repetir una orden sin esperar;
  - lo que le toca a la app: deshabilitar el botón al tocarlo y generar el identificador una sola vez por decisión del
    usuario.
- **Cuándo revisarla:**
  - con datos reales de cada cuánto llegan los duplicados, para ajustar la ventana;
  - si la API la usaran algoritmos, para pasar a contar órdenes iguales seguidas, como Cboe, o a una ventana por cliente;
  - si el control tuviera que actuar antes de llegar a la API, en la entrada: ahí hace falta un almacén compartido
    (la opción C).

## Fuentes

- [SEC, regla 15c3-5, texto](https://codes.findlaw.com/cfr/title-17-commodity-and-securities-exchanges/cfr-sect-17-240-15c3-5/),
  párrafo (c)(1)(ii), y [su adopción en 2010](https://www.sec.gov/rules/final/2010/34-63241.pdf).
- [FIX 4.4, campo 97, `PossResend`](https://www.onixs.biz/fix-dictionary/4.4/tagNum_97.html).
- [Cboe, Duplicative Order Protection](https://www.cboe.com/document/tech-spec/content/technical-specifications/cboe-titanium-u.s.-equitiesoptions-web-portal-port-controls-specification/port-controls/duplicative-order-protection).
- [Braintree, Duplicate Transaction Checking](https://developer.paypal.com/braintree/articles/control-panel/transactions/duplicate-checking).
- [Authorize.Net, Error 11: A duplicate transaction has been submitted](https://support.authorize.net/s/article/Error-11-A-duplicate-transaction-has-been-submitted).
