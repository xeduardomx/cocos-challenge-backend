package com.cocos.challenge.account;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** The pesos, reservations and positions of a user, by instrument id. */
public record Account(
    BigDecimal cashBalance, BigDecimal reservedCash, Map<Long, Position> positions) {

  public BigDecimal availableCash() {
    return cashBalance.subtract(reservedCash);
  }

  public long availableShares(long instrumentId) {
    Position position = positions.get(instrumentId);
    return position == null ? 0 : position.availableQuantity();
  }

  /** The positions with a quantity other than zero. */
  public List<Position> heldPositions() {
    return positions.values().stream().filter(position -> position.quantity() != 0).toList();
  }
}
