# Diagramas

Los ocho diagramas del RFC, juntos. Cada uno se toma de su sección al generar el portal, así nunca dicen algo distinto
de lo que dice el RFC.

## Todo el sistema

Quién habla con quién cuando la API está corriendo. Detalle en el
[resumen del RFC](../03-rfc.md#resumen).

--8<-- "03-rfc.md:sistema"

## Las funciones y de qué dependen

Cada función tiene sus capas, y las dependencias van en un solo sentido. La cuenta del usuario se calcula en un solo
lugar, `account`, que usan el portfolio y el envío. Detalle en el [RFC, sección 2](../03-rfc.md#2-arquitectura).

--8<-- "03-rfc.md:funciones"

## El modelo de datos

Las cuatro tablas de Cocos, con lo que se les agregó. Detalle en el
[RFC, sección 3.1](../03-rfc.md#31-el-modelo-con-los-cambios).

--8<-- "03-rfc.md:modelo"

## Los estados de una orden

Solo una orden `NEW` cambia de estado; las otras tres son finales. Detalle en el
[RFC, sección 5.1](../03-rfc.md#51-los-estados-de-una-orden).

--8<-- "03-rfc.md:estados"

## Dos ventas al mismo tiempo

El caso más peligroso: dos ventas de 15 acciones con 20 en la cuenta. La segunda espera su turno y, cuando lee la
cuenta, ya ve la primera. Detalle en el [RFC, sección 5.2](../03-rfc.md#52-el-turno-del-usuario).

--8<-- "03-rfc.md:turno"

## Un reintento no compra dos veces

La respuesta se pierde, la app reintenta con el mismo identificador y recibe la misma orden. Detalle en el
[RFC, sección 5.3](../03-rfc.md#53-reintentos).

--8<-- "03-rfc.md:reintento"

## La misma orden con otro identificador

Si la app manda la misma orden con un identificador nuevo, no es un reintento: una segunda red la frena si llega dentro
de los 5 segundos. Detalle en el [RFC, sección 5.5](../03-rfc.md#55-la-misma-orden-con-otro-identificador).

--8<-- "03-rfc.md:duplicado"

## Qué levanta `docker compose up`

Con un solo comando quedan corriendo la API, su base, Prometheus y Grafana. Detalle en el
[RFC, sección 11.3](../03-rfc.md#113-docker-y-docker-compose-up).

--8<-- "03-rfc.md:compose"
