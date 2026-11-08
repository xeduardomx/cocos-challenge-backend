package com.cocos.challenge.shared.web;

import com.cocos.challenge.shared.error.FieldViolation;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** The shape of every error response, for the OpenAPI specification only (RFC 9457). */
@Schema(name = "Problem", description = "Error en formato Problem Details (RFC 9457)")
record ProblemBody(
    @Schema(description = "Identifica el error y no cambia", example = "/problems/validation-error")
        String type,
    @Schema(example = "El pedido no es válido") String title,
    @Schema(example = "400") int status,
    @Schema(description = "Qué está mal y cómo arreglarlo, en español") String detail,
    @Schema(example = "/users/1/orders") String instance,
    @Schema(description = "El id del pedido, el mismo que está en los logs") String requestId,
    @Schema(description = "Los campos con problemas, en un 400")
        @Nullable List<FieldViolation> errors,
    @Schema(description = "El estado actual de la orden, en un 409", example = "FILLED")
        @Nullable String currentStatus) {}
