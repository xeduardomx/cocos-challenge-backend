package com.cocos.challenge.shared.error;

/** One entry of the {@code errors} member of a validation problem. */
public record FieldViolation(String field, String message) {}
