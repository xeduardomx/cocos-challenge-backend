package com.cocos.challenge.orders.submission;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * What an order request means, as a SHA-256 of a fixed shape: version, instrument, side, type,
 * quantity, amount and limit price. The user and the client order id are left out, and numbers are
 * written without trailing zeros, so {@code 10000}, {@code 10000.00} and {@code 1E+4} are the same
 * request (RFC 5.3).
 */
public record RequestFingerprint(String value) {

  static final String VERSION = "v1";
  static final String SEPARATOR = "|";
  private static final String ALGORITHM = "SHA-256";
  private static final String MISSING = "";

  public static RequestFingerprint of(SubmitOrderCommand command) {
    String shape =
        Stream.of(
                VERSION,
                String.valueOf(command.instrumentId()),
                command.side().name(),
                command.type().name(),
                text(command.quantity()),
                text(command.amount()),
                text(command.limitPrice()))
            .collect(Collectors.joining(SEPARATOR));
    return new RequestFingerprint(hash(shape));
  }

  private static String text(@Nullable Integer number) {
    return number == null ? MISSING : number.toString();
  }

  private static String text(@Nullable BigDecimal number) {
    return number == null ? MISSING : number.stripTrailingZeros().toPlainString();
  }

  private static String hash(String shape) {
    try {
      MessageDigest digest = MessageDigest.getInstance(ALGORITHM);
      return HexFormat.of().formatHex(digest.digest(shape.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      // every Java platform must provide SHA-256
      throw new IllegalStateException(ALGORITHM + " is not available", e);
    }
  }
}
