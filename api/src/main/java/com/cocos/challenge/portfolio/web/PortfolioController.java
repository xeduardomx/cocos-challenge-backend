package com.cocos.challenge.portfolio.web;

import com.cocos.challenge.portfolio.service.PortfolioService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PortfolioController {

  private final PortfolioService service;

  public PortfolioController(PortfolioService service) {
    this.service = service;
  }

  @Operation(
      summary = "Portfolio de un usuario",
      description =
          "Devuelve el valor total de la cuenta, los pesos (saldo, reservado y disponible) y las"
              + " posiciones, ordenadas por ticker.")
  @ApiResponse(
      responseCode = "200",
      description = "El portfolio del usuario",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = PortfolioResponse.class),
              examples = @ExampleObject(value = PortfolioApiExamples.PORTFOLIO)))
  @ApiResponse(responseCode = "400", description = "El userId no es un número válido")
  @ApiResponse(responseCode = "404", description = "El usuario no existe")
  @ApiResponse(responseCode = "500", description = "Error inesperado, sin detalles internos")
  @GetMapping("/users/{userId}/portfolio")
  public PortfolioResponse portfolio(@PathVariable long userId) {
    return PortfolioResponse.from(userId, service.portfolioOf(userId));
  }
}
