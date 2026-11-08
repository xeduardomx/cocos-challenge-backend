package com.cocos.challenge.account;

import com.cocos.challenge.shared.domain.Decimals;
import com.cocos.challenge.shared.domain.OrderSide;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Calculates the account of a user from its FILLED and NEW orders, in chronological order (RFC
 * 4.1). Plain Java: the same calculation serves the portfolio and the order submission.
 */
public class AccountCalculator {

  public Account calculate(List<AccountEntry> entries) {
    BigDecimal cash = Decimals.ZERO_MONEY;
    BigDecimal reservedCash = Decimals.ZERO_MONEY;
    Map<Long, PositionTally> tallies = new HashMap<>();

    for (AccountEntry entry : entries) {
      switch (entry.status()) {
        case FILLED -> {
          cash = cash.add(cashEffect(entry));
          if (entry.side().isTrade()) {
            tallies.computeIfAbsent(entry.instrumentId(), id -> new PositionTally()).fill(entry);
          }
        }
        case NEW -> {
          if (entry.side() == OrderSide.BUY) {
            reservedCash = reservedCash.add(entry.amount());
          } else if (entry.side() == OrderSide.SELL) {
            tallies
                .computeIfAbsent(entry.instrumentId(), id -> new PositionTally())
                .reserve(entry.size());
          }
        }
        case REJECTED, CANCELLED -> {
          // These orders do not touch the account.
        }
      }
    }

    Map<Long, Position> positions = new HashMap<>();
    tallies.forEach((id, tally) -> positions.put(id, tally.toPosition(id)));
    return new Account(cash, reservedCash, Map.copyOf(positions));
  }

  private static BigDecimal cashEffect(AccountEntry entry) {
    return switch (entry.side()) {
      case CASH_IN, SELL -> entry.amount();
      case CASH_OUT, BUY -> entry.amount().negate();
    };
  }

  /** The running quantity, reservation and average price of one instrument. */
  private static final class PositionTally {
    private long quantity;
    private long reservedQuantity;
    private BigDecimal averagePrice = BigDecimal.ZERO;

    void fill(AccountEntry entry) {
      if (entry.side() == OrderSide.SELL) {
        quantity -= entry.size();
        return;
      }
      if (quantity <= 0) {
        averagePrice = entry.price();
      } else {
        BigDecimal held = averagePrice.multiply(BigDecimal.valueOf(quantity));
        averagePrice =
            Decimals.divide(held.add(entry.amount()), BigDecimal.valueOf(quantity + entry.size()));
      }
      quantity += entry.size();
    }

    void reserve(int size) {
      reservedQuantity += size;
    }

    Position toPosition(long instrumentId) {
      return new Position(
          instrumentId, quantity, reservedQuantity, quantity > 0 ? averagePrice : null);
    }
  }
}
