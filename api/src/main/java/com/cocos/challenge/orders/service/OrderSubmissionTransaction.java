package com.cocos.challenge.orders.service;

import com.cocos.challenge.account.domain.Account;
import com.cocos.challenge.account.service.AccountService;
import com.cocos.challenge.instruments.domain.Instrument;
import com.cocos.challenge.instruments.domain.MarketPrice;
import com.cocos.challenge.instruments.service.InstrumentCatalog;
import com.cocos.challenge.orders.config.DuplicateOrderProperties;
import com.cocos.challenge.orders.domain.NewOrder;
import com.cocos.challenge.orders.domain.StoredOrder;
import com.cocos.challenge.orders.domain.evaluation.OrderCandidate;
import com.cocos.challenge.orders.domain.evaluation.OrderDecision;
import com.cocos.challenge.orders.domain.evaluation.OrderEvaluator;
import com.cocos.challenge.orders.domain.submission.QuantityResolver;
import com.cocos.challenge.orders.domain.submission.RequestFingerprint;
import com.cocos.challenge.orders.domain.submission.ResolvedOrder;
import com.cocos.challenge.orders.domain.submission.SubmissionResult;
import com.cocos.challenge.orders.domain.submission.SubmitOrderCommand;
import com.cocos.challenge.orders.error.ClientOrderIdReusedException;
import com.cocos.challenge.orders.error.InvalidOrderException;
import com.cocos.challenge.orders.error.PossibleDuplicateOrderException;
import com.cocos.challenge.orders.repository.OrderRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * One attempt to submit an order, all in one transaction that starts by taking the turn of the user
 * (RFC 5.2, 5.3 and 5.5). It stays in READ COMMITTED: once a second order of the user gets the
 * turn, its account has to include the order that held it.
 */
@Service
public class OrderSubmissionTransaction {

  private static final String INSTRUMENT_NOT_FOUND_KEY = "order.instrument-not-found";

  private final OrderRepository orders;
  private final InstrumentCatalog catalog;
  private final QuantityResolver quantityResolver;
  private final AccountService accounts;
  private final OrderEvaluator evaluator;
  private final DuplicateOrderProperties duplicates;

  public OrderSubmissionTransaction(
      OrderRepository orders,
      InstrumentCatalog catalog,
      QuantityResolver quantityResolver,
      AccountService accounts,
      OrderEvaluator evaluator,
      DuplicateOrderProperties duplicates) {
    this.orders = orders;
    this.catalog = catalog;
    this.quantityResolver = quantityResolver;
    this.accounts = accounts;
    this.evaluator = evaluator;
    this.duplicates = duplicates;
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
    if (duplicates.enabled() && decision.status().accepted()) {
      refuseIfPossibleDuplicate(command, fingerprint);
    }
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

  /**
   * The second net, after the client order id (RFC 5.5): the user has an equal order, accepted
   * moments ago and sent with another identifier. Inside the turn, that order is already confirmed.
   * An order that would be rejected anyway never gets here: it does no harm, and it is recorded.
   */
  private void refuseIfPossibleDuplicate(
      SubmitOrderCommand command, RequestFingerprint fingerprint) {
    Optional<Long> existingOrderId =
        orders.findRecentEqualOrderId(
            command.userId(), command.clientOrderId(), fingerprint.value(), duplicates.window());
    if (existingOrderId.isPresent()) {
      throw new PossibleDuplicateOrderException(existingOrderId.get());
    }
  }
}
