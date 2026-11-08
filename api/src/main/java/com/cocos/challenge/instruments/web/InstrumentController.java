package com.cocos.challenge.instruments.web;

import com.cocos.challenge.instruments.search.InstrumentSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/instruments")
public class InstrumentController {

  private final InstrumentSearchService searchService;

  public InstrumentController(InstrumentSearchService searchService) {
    this.searchService = searchService;
  }

  @Operation(
      summary = "Buscar activos",
      description =
          "Busca por ticker o por nombre, sin distinguir mayúsculas. Cada activo trae su último"
              + " precio y su variación del día, o null si no cotiza.")
  @ApiResponse(
      responseCode = "200",
      description = "Los activos que coinciden, hasta el tope configurado",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = InstrumentSearchResponse.class),
              examples = @ExampleObject(value = InstrumentApiExamples.SEARCH_RESULT)))
  @ApiResponse(responseCode = "400", description = "El texto de búsqueda está vacío o es muy largo")
  @ApiResponse(responseCode = "500", description = "Error inesperado, sin detalles internos")
  @GetMapping
  public InstrumentSearchResponse search(@RequestParam(required = false) @Nullable String query) {
    return new InstrumentSearchResponse(
        searchService.search(query).stream().map(InstrumentSummary::from).toList());
  }
}
