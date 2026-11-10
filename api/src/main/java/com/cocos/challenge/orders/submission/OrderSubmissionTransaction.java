package com.cocos.challenge.orders.submission;

import com.cocos.challenge.account.Account;
import com.cocos.challenge.account.AccountService;
import com.cocos.challenge.instruments.Instrument;
import com.cocos.challenge.instruments.InstrumentCatalog;
import com.cocos.challenge.instruments.MarketPrice;
import com.cocos.challenge.orders.NewOrder;
import com.cocos.challenge.orders.OrderRepository;
import com.cocos.challenge.orders.StoredOrder;
import com.cocos.challenge.orders.evaluation.OrderCandidate;
import com.cocos.challenge.orders.evaluation.OrderDecision;
import com.cocos.challenge.orders.evaluation.OrderEvaluator;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * One attempt to submit an order, all in one transaction that starts by taking the turn of the user
 * (RFC 5.2 and 5.3). It stays in READ COMMITTED: once a second order of the user gets the turn, its
 * account has to include the order that held it.
 */
@Service
public class OrderSubmissionTransaction {

  private static final String INSTRUMENT_NOT_FOUND_KEY = "order.instrument-not-found";

  private final OrderRepository orders;
  private final InstrumentCatalog catalog;
  private final QuantityResolver quantityResolver;
  private final AccountService accounts;
  private final OrderEvaluator evaluator;

  public OrderSubmissionTransaction(
      OrderRepository orders,
      InstrumentCatalog catalog,
      QuantityResolver quantityResolver,
      AccountService accounts,
      OrderEvaluator evaluator) {
    this.orders = orders;
    this.catalog = catalog;
    this.quantityResolver = quantityResolver;
    this.accounts = accounts;
    this.evaluator = evaluator;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public SubmissionResult submit(SubmitOrderCommand command) {
    accounts.takeTurn(command.userId());
    RequestFingerprint fingerprint = RequestFingerprint.of(command);
    Optional<StoredOrder> existing =
        orders.findByClientOrderId(command.userId(), command.clientOrderId());
    if (existing.isPresent()) {
      return replay(existing.get(), fingerprint);
    }
    return new SubmissionResult(record(command, fingerprint), false);
  }

  private static SubmissionResult replay(StoredOrder existing, RequestFingerprint fingerprint) {
    if (!fingerprint.value().equals(existing.requestFingerprint())) {
      throw new ClientOrderIdReusedException();
    }
    return new SubmissionResult(existing, true);
  }

  private StoredOrder record(SubmitOrderCommand command, RequestFingerprint fingerprint) {
    Instrument instrument =
        catalog
            .find(command.instrumentId())
            .orElseThrow(() ->
                new InvalidOrderException(
                    INSTRUMENT_NOT_FOUND_KEY, String.valueOf(command.instrumentId())));
    MarketPrice marketPrice = catalog.findLatestPrice(instrument.id()).orElse(null);
    ResolvedOrder resolved = quantityResolver.resolve(command, instrument, marketPrice);
    Account account = accounts.load(command.userId());
    OrderCandidate candidate =
        new OrderCandidate(
            instrument.id(),
            command.side(),
            command.type(),
            resolved.quantity(),
            resolved.price(),
            marketPrice != null);
    OrderDecision decision = evaluator.evaluate(candidate, account);
    return orders.insert(
        new NewOrder(
            command.userId(),
            instrument.id(),
            command.side(),
            command.type(),
            resolved.quantity(),
            resolved.price(),
            decision.status(),
            command.clientOrderId(),
            fingerprint.value(),
            decision.rejectionReason()));
  }
}
