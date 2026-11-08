package com.cocos.challenge.portfolio;

import com.cocos.challenge.account.Account;
import com.cocos.challenge.account.Position;
import com.cocos.challenge.instruments.Instrument;
import com.cocos.challenge.instruments.MarketPrice;
import com.cocos.challenge.shared.domain.Decimals;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Values the account of a user at the latest prices. Pure calculation, no I/O. */
public class PortfolioValuator {

  public Portfolio value(
      Account account, Map<Long, Instrument> instruments, Map<Long, MarketPrice> prices) {
    List<PositionValuation> positions =
        account.heldPositions().stream()
            .map(position ->
                valuePosition(
                    position,
                    Objects.requireNonNull(instruments.get(position.instrumentId())),
                    prices.get(position.instrumentId())))
            .sorted(Comparator.comparing(PositionValuation::ticker))
            .toList();
    return new Portfolio(
        totalValue(account.cashBalance(), positions),
        account.cashBalance(),
        account.reservedCash(),
        account.availableCash(),
        positions);
  }

  private static PositionValuation valuePosition(
      Position position, Instrument instrument, @Nullable MarketPrice price) {
    BigDecimal averagePrice = position.averagePrice();
    BigDecimal lastPrice = price == null ? null : price.close();
    return new PositionValuation(
        instrument.id(),
        instrument.ticker(),
        instrument.name(),
        position.quantity(),
        position.reservedQuantity(),
        lastPrice,
        lastPrice == null ? null : lastPrice.multiply(BigDecimal.valueOf(position.quantity())),
        averagePrice == null ? null : Decimals.roundMoney(averagePrice),
        averagePrice == null || lastPrice == null
            ? null
            : Decimals.percentChange(averagePrice, lastPrice),
        price == null ? null : price.dailyChangePercent());
  }

  private static BigDecimal totalValue(BigDecimal cash, List<PositionValuation> positions) {
    return positions.stream()
        .map(PositionValuation::marketValue)
        .filter(Objects::nonNull)
        .reduce(cash, BigDecimal::add);
  }
}
