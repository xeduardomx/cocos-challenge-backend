# ADR 0008 — Especificación OpenAPI con springdoc-openapi

> Estado: **aceptada** · 2026-11-07 · Se aplica en el
> [RFC, sección 6.7](../03-rfc.md#67-la-especificación-openapi)

## Contexto

- Quien evalúa tiene que poder recorrer las cinco funciones sin leer el código. La colección de Postman, decidida en
  el PRD, muestra el recorrido, pero no un contrato para consultar.
- `CLAUDE.md` pide justificar en un ADR cada librería que se agrega.

## Opciones

**A. springdoc-openapi.** Genera la especificación desde el código, en `/v3/api-docs`, y una página para probar la API
desde el navegador, en `/swagger-ui.html`.

- A favor: la especificación no se desactualiza, porque sale del código. La versión 3.2.0 está construida sobre Spring
  Boot 4.1.2, la misma que usa la API.
- En contra: es una dependencia más, que hay que actualizar junto con Spring Boot, y documentar los ejemplos agrega
  anotaciones en los controllers.

**B. Una especificación escrita a mano** (`openapi.yaml`). No agrega librerías, pero se desactualiza en cuanto cambia
un campo y nadie la toca.

**C. Ninguna:** solo la colección de Postman.

## Decisión

**A.** La API publica su especificación y la página para probarla. Los ejemplos de la documentación usan los números
del usuario 1, como el RFC (sección 6.2).

## Consecuencias

- La página de prueba queda en la API local (puerto 8080). En producción se apaga o se protege, porque muestra todo el
  contrato.
- Los controllers llevan las anotaciones de la documentación. Se limitan a lo que la especificación no puede deducir:
  descripciones y ejemplos.
- springdoc 3.2.0 trae Jackson 2 (`jackson-databind` 2.21, a través de `swagger-core`) para armar su propio modelo de
  la especificación. La API sigue leyendo y escribiendo JSON con Jackson 3: lo prueban los tests que comparan el
  JSON exacto de las respuestas (encontrado al implementar, fase 5).
- Para que la especificación muestre los errores como Problem Details, la configuración de OpenAPI les asigna el
  esquema `Problem` a todas las respuestas que no son 2xx. Sin eso, springdoc los mostraba con el esquema de la
  respuesta exitosa.
- **Cuándo revisarla:** si springdoc dejara de seguir a Spring Boot en una actualización. La alternativa sería generar
  la especificación en el build, o escribirla a mano.
