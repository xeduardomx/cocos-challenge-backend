package com.cocos.challenge.orders.web;

import com.cocos.challenge.orders.domain.listing.OrderPage;
import com.cocos.challenge.orders.domain.submission.SubmissionResult;
import com.cocos.challenge.orders.service.OrderCancellationService;
import com.cocos.challenge.orders.service.OrderQueryService;
import com.cocos.challenge.orders.service.OrderSubmissionService;
import com.cocos.challenge.shared.domain.OrderStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrderController {

  private final OrderSubmissionService submissionService;
  private final OrderCancellationService cancellationService;
  private final OrderQueryService queryService;
  private final OrderResponseMapper mapper;

  public OrderController(
      OrderSubmissionService submissionService,
      OrderCancellationService cancellationService,
      OrderQueryService queryService,
      OrderResponseMapper mapper) {
    this.submissionService = submissionService;
    this.cancellationService = cancellationService;
    this.queryService = queryService;
    this.mapper = mapper;
  }

  /** 201 for an order that was recorded, 200 for the answer to a retry. */
  @Operation(
      summary = "Enviar una orden",
      description =
          "Compra o vende un activo, a mercado o con precio límite, por cantidad o por monto. Una"
              + " orden que no se puede ejecutar queda grabada como REJECTED y responde 201. El"
              + " clientOrderId hace seguro el reintento: repetir el mismo pedido devuelve la orden"
              + " original con 200. La misma orden con otro clientOrderId, a los pocos segundos de"
              + " una aceptada, se frena con 409 como posible duplicado.")
  @io.swagger.v3.oas.annotations.parameters.RequestBody(
      content =
          @Content(
              examples = {
                @ExampleObject(
                    name = "Comprar a mercado",
                    value = OrderApiExamples.MARKET_BUY_REQUEST),
                @ExampleObject(
                    name = "Comprar por monto, con precio límite",
                    value = OrderApiExamples.LIMIT_BUY_BY_AMOUNT_REQUEST)
              }))
  @ApiResponse(
      responseCode = "201",
      description = "La orden se grabó: FILLED, NEW o REJECTED",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = OrderResponse.class),
              examples = {
                @ExampleObject(name = "Ejecutada", value = OrderApiExamples.FILLED_ORDER),
                @ExampleObject(name = "Rechazada", value = OrderApiExamples.REJECTED_ORDER)
              }))
  @ApiResponse(
      responseCode = "200",
      description = "Reintento: la orden original, en su estado actual",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = OrderResponse.class),
              examples = @ExampleObject(value = OrderApiExamples.FILLED_ORDER)))
  @ApiResponse(
      responseCode = "400",
      description = "El pedido no es válido, o el activo no existe o es la moneda")
  @ApiResponse(responseCode = "404", description = "El usuario no existe")
  @ApiResponse(
      responseCode = "409",
      description =
          "Posible duplicado: el usuario tiene una orden igual, aceptada hace instantes y con otro"
              + " clientOrderId. Trae su número en existingOrderId")
  @ApiResponse(responseCode = "422", description = "El clientOrderId ya se usó con otro contenido")
  @ApiResponse(
      responseCode = "503",
      description = "La cuenta está ocupada con otra orden: intentar de nuevo")
  @ApiResponse(responseCode = "500", description = "Error inesperado, sin detalles internos")
  @PostMapping("/users/{userId}/orders")
  public ResponseEntity<OrderResponse> submit(
      @PathVariable long userId, @Valid @RequestBody SubmitOrderRequest request) {
    SubmissionResult result = submissionService.submit(request.toCommand(userId));
    HttpStatus status = result.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
    return ResponseEntity.status(status).body(mapper.toResponse(result.order()));
  }

  @Operation(
      summary = "Cancelar una orden",
      description = "Solo se cancela una orden pendiente (NEW) del usuario.")
  @ApiResponse(
      responseCode = "200",
      description = "La orden, ya CANCELLED",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = OrderResponse.class),
              examples = @ExampleObject(value = OrderApiExamples.CANCELLED_ORDER)))
  @ApiResponse(responseCode = "400", description = "El userId o el orderId no son números válidos")
  @ApiResponse(
      responseCode = "404",
      description = "No existe el usuario, o la orden (o es de otro)")
  @ApiResponse(responseCode = "409", description = "La orden ya no está NEW")
  @ApiResponse(responseCode = "500", description = "Error inesperado, sin detalles internos")
  @PostMapping("/users/{userId}/orders/{orderId}/cancel")
  public OrderResponse cancel(@PathVariable long userId, @PathVariable long orderId) {
    return mapper.toResponse(cancellationService.cancel(userId, orderId));
  }

  @Operation(
      summary = "Listar las órdenes de un usuario",
      description =
          "De la más nueva a la más vieja, con paginación por cursor: para la página siguiente se"
              + " manda el nextCursor de la respuesta anterior. Se puede filtrar por estado.")
  @ApiResponse(
      responseCode = "200",
      description = "Una página de órdenes, con nextCursor en null en la última",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = OrderPageResponse.class),
              examples = @ExampleObject(value = OrderApiExamples.PENDING_ORDERS)))
  @ApiResponse(
      responseCode = "400",
      description = "El estado, el tamaño de página o el cursor no son válidos")
  @ApiResponse(responseCode = "404", description = "El usuario no existe")
  @ApiResponse(responseCode = "500", description = "Error inesperado, sin detalles internos")
  @GetMapping("/users/{userId}/orders")
  public OrderPageResponse list(
      @PathVariable long userId,
      @RequestParam(required = false) @Nullable OrderStatus status,
      @RequestParam(required = false) @Nullable Integer limit,
      @RequestParam(required = false) @Nullable String cursor) {
    OrderPage page = queryService.list(userId, status, limit, cursor);
    return new OrderPageResponse(
        page.orders().stream().map(mapper::toResponse).toList(), page.nextCursor());
  }
}
