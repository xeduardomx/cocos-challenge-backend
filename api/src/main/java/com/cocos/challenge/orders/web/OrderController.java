package com.cocos.challenge.orders.web;

import com.cocos.challenge.orders.cancellation.OrderCancellationService;
import com.cocos.challenge.orders.submission.OrderSubmissionService;
import com.cocos.challenge.orders.submission.SubmissionResult;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrderController {

  private final OrderSubmissionService submissionService;
  private final OrderCancellationService cancellationService;
  private final OrderResponseMapper mapper;

  public OrderController(
      OrderSubmissionService submissionService,
      OrderCancellationService cancellationService,
      OrderResponseMapper mapper) {
    this.submissionService = submissionService;
    this.cancellationService = cancellationService;
    this.mapper = mapper;
  }

  /** 201 for an order that was recorded, 200 for the answer to a retry. */
  @PostMapping("/users/{userId}/orders")
  public ResponseEntity<OrderResponse> submit(
      @PathVariable long userId, @Valid @RequestBody SubmitOrderRequest request) {
    SubmissionResult result = submissionService.submit(request.toCommand(userId));
    HttpStatus status = result.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
    return ResponseEntity.status(status).body(mapper.toResponse(result.order()));
  }

  @PostMapping("/users/{userId}/orders/{orderId}/cancel")
  public OrderResponse cancel(@PathVariable long userId, @PathVariable long orderId) {
    return mapper.toResponse(cancellationService.cancel(userId, orderId));
  }
}
