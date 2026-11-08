package com.cocos.challenge.orders.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cocos.challenge.instruments.Instrument;
import com.cocos.challenge.instruments.MarketPrice;
import com.cocos.challenge.orders.OrderType;
import com.cocos.challenge.shared.display.DisplayFormatter;
import com.cocos.challenge.shared.display.DisplayProperties;
import com.cocos.challenge.shared.domain.OrderSide;
import com.cocos.challenge.shared.error.ProblemType;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.Locale;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class QuantityResolverTest {

  private static final Instrument PAMP =
      new Instrument(47, "PAMP", "Pampa Holding S.A.", "ACCIONES");
  private static final Instrument PGR =
      new Instrument(3, "PGR", "Phoenix Global Resources", "ACCIONES");
  private static final Instrument CARC = new Instrument(30, "CARC", "Carboclor S.A.", "ACCIONES");
  private static final Instrument ARS = new Instrument(66, "ARS", "ARS", "MONEDA");
  private static final MarketPrice PAMP_PRICE =
      new MarketPrice(47, new BigDecimal("925.85"), new BigDecimal("921.80"));
  private static final MarketPrice CARC_PRICE =
      new MarketPrice(30, new BigDecimal("7.00"), new BigDecimal("7.10"));

  private final QuantityResolver resolver =
      new QuantityResolver(
          new DisplayFormatter(
              new DisplayProperties(
                  ZoneId.of("America/Argentina/Buenos_Aires"), Locale.forLanguageTag("es-AR"))));

  private static SubmitOrderCommand byQuantity(
      Instrument instrument, OrderType type, int quantity, @Nullable BigDecimal limitPrice) {
    return new SubmitOrderCommand(
        1, UUID.randomUUID(), instrument.id(), OrderSide.BUY, type, quantity, null, limitPrice);
  }

  private static SubmitOrderCommand byAmount(
      Instrument instrument, OrderType type, String amount, @Nullable BigDecimal limitPrice) {
    return new SubmitOrderCommand(
        1,
        UUID.randomUUID(),
        instrument.id(),
        OrderSide.BUY,
        type,
        null,
        new BigDecimal(amount),
        limitPrice);
  }

  @Test
  void keepsTheQuantityAndRecordsTheMarketPriceOfAMarketOrder() {
    ResolvedOrder resolved =
        resolver.resolve(byQuantity(PAMP, OrderType.MARKET, 10, null), PAMP, PAMP_PRICE);

    assertThat(resolved).isEqualTo(new ResolvedOrder(10, new BigDecimal("925.85")));
  }

  @Test
  void recordsTheLimitPriceOfALimitOrder() {
    ResolvedOrder resolved =
        resolver.resolve(
            byQuantity(PAMP, OrderType.LIMIT, 10, new BigDecimal("900.00")), PAMP, PAMP_PRICE);

    assertThat(resolved).isEqualTo(new ResolvedOrder(10, new BigDecimal("900.00")));
  }

  @Test
  void recordsNoPriceWhenAMarketOrderHasNoQuote() {
    ResolvedOrder resolved =
        resolver.resolve(byQuantity(PGR, OrderType.MARKET, 10, null), PGR, null);

    assertThat(resolved).isEqualTo(new ResolvedOrder(10, null));
  }

  @Test
  void recordsTheLimitPriceOfAnInstrumentWithoutQuote() {
    ResolvedOrder resolved =
        resolver.resolve(byQuantity(PGR, OrderType.LIMIT, 10, new BigDecimal("50.00")), PGR, null);

    assertThat(resolved).isEqualTo(new ResolvedOrder(10, new BigDecimal("50.00")));
  }

  @Test
  void turnsAnAmountIntoWholeSharesAtTheMarketPrice() {
    ResolvedOrder resolved =
        resolver.resolve(byAmount(PAMP, OrderType.MARKET, "10000", null), PAMP, PAMP_PRICE);

    assertThat(resolved).isEqualTo(new ResolvedOrder(10, new BigDecimal("925.85")));
  }

  @Test
  void givesExactlyTenSharesForTheAmountOfTenSharesAtTheMarketPrice() {
    ResolvedOrder resolved =
        resolver.resolve(byAmount(PAMP, OrderType.MARKET, "9258.50", null), PAMP, PAMP_PRICE);

    assertThat(resolved.quantity()).isEqualTo(10);
  }

  @Test
  void turnsAnAmountIntoSharesAtTheLimitPrice() {
    ResolvedOrder resolved =
        resolver.resolve(
            byAmount(PAMP, OrderType.LIMIT, "10000", new BigDecimal("900")), PAMP, PAMP_PRICE);

    assertThat(resolved).isEqualTo(new ResolvedOrder(11, new BigDecimal("900")));
  }

  @Test
  void rejectsAnAmountThatDoesNotBuyOneShare() {
    assertThatThrownBy(
            () -> resolver.resolve(byAmount(PAMP, OrderType.MARKET, "500", null), PAMP, PAMP_PRICE))
        .isInstanceOfSatisfying(
            InvalidOrderException.class,
            error -> {
              assertThat(error.problemType()).isEqualTo(ProblemType.INVALID_ORDER);
              assertThat(error.detailKey()).isEqualTo("order.quantity-zero");
              assertThat(error.detailArgs()).containsExactly("500", "PAMP", "925,85");
            });
  }

  @Test
  void rejectsAnAmountOrderOnAnInstrumentWithoutQuote() {
    assertThatThrownBy(
            () -> resolver.resolve(byAmount(PGR, OrderType.MARKET, "10000", null), PGR, null))
        .isInstanceOfSatisfying(
            InvalidOrderException.class,
            error -> {
              assertThat(error.detailKey()).isEqualTo("order.no-price-for-amount");
              assertThat(error.detailArgs()).containsExactly("PGR");
            });
  }

  @Test
  void sizesAnAmountAtTheLimitPriceOfAnInstrumentWithoutQuote() {
    ResolvedOrder resolved =
        resolver.resolve(
            byAmount(PGR, OrderType.LIMIT, "1000", new BigDecimal("50.00")), PGR, null);

    assertThat(resolved).isEqualTo(new ResolvedOrder(20, new BigDecimal("50.00")));
  }

  @Test
  void rejectsAnAmountThatBuysMoreSharesThanAnOrderCanHold() {
    assertThatThrownBy(() ->
            resolver.resolve(
                byAmount(CARC, OrderType.MARKET, "15100000000", null), CARC, CARC_PRICE))
        .isInstanceOfSatisfying(
            InvalidOrderException.class,
            error -> {
              assertThat(error.detailKey()).isEqualTo("order.quantity-too-large");
              assertThat(error.detailArgs()).containsExactly("2.147.483.647");
            });
  }

  @Test
  void acceptsTheLargestQuantityAnOrderCanHold() {
    ResolvedOrder resolved =
        resolver.resolve(byAmount(CARC, OrderType.MARKET, "15032385529", null), CARC, CARC_PRICE);

    assertThat(resolved.quantity()).isEqualTo(Integer.MAX_VALUE);
  }

  @Test
  void rejectsTheCurrencyOfTheAccount() {
    assertThatThrownBy(
            () -> resolver.resolve(byQuantity(ARS, OrderType.MARKET, 10, null), ARS, null))
        .isInstanceOfSatisfying(
            InvalidOrderException.class,
            error -> {
              assertThat(error.problemType()).isEqualTo(ProblemType.INVALID_ORDER);
              assertThat(error.detailKey()).isEqualTo("order.instrument-is-currency");
            });
  }
}
