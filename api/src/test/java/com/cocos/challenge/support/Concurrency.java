package com.cocos.challenge.support;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Makes simultaneous requests cross always the same way, so a test fails every time the code lets
 * them step on each other, not only when the threads happen to cross (RFC 10.3).
 */
@Component
public class Concurrency {

  /** The longest a test waits for any step of a choreography. */
  public static final Duration WAIT_LIMIT = Duration.ofSeconds(5);

  private static final Duration POLL_INTERVAL = Duration.ofMillis(10);
  private static final String ANYONE_BLOCKED_ON_LOCK =
      "select exists (select 1 from pg_stat_activity where wait_event_type = 'Lock')";

  private final JdbcClient jdbc;

  public Concurrency(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Waits until another connection waits for a lock, as Postgres shows in pg_stat_activity. */
  public void awaitBlockedOnLock(Duration limit) {
    awaitUntil("another connection waiting for a lock", this::anyoneBlockedOnLock, limit);
  }

  /**
   * Waits until another connection waits for a lock or a second call passes {@code pause}:
   * whichever comes first.
   */
  public void awaitBlockedOnLockOrSecondCall(PausePoint pause, Duration limit) {
    awaitUntil(
        "another connection waiting for a lock, or a second call past the pause point",
        () -> anyoneBlockedOnLock() || pause.calls() > 1,
        limit);
  }

  /**
   * Polls on a thread of its own, outside any transaction: pg_stat_activity keeps one snapshot for
   * the whole of a transaction, so each look has to be a statement of its own.
   */
  private static void awaitUntil(String description, Callable<Boolean> condition, Duration limit) {
    await(description).atMost(limit).pollInterval(POLL_INTERVAL).until(condition);
  }

  private boolean anyoneBlockedOnLock() {
    return jdbc.sql(ANYONE_BLOCKED_ON_LOCK).query(Boolean.class).single();
  }

  /**
   * Holds the first call of a method spied with {@code @MockitoSpyBean} once the real method has
   * run, until the test releases it. Later calls run the real method without waiting. It counts the
   * calls whose real method ran, so the test knows whether a second one came.
   */
  public static final class PausePoint implements Answer<Object> {

    private final CountDownLatch reached = new CountDownLatch(1);
    private final CountDownLatch released = new CountDownLatch(1);
    private final AtomicInteger calls = new AtomicInteger();

    private PausePoint() {}

    /**
     * Pauses the first call of the method that {@code method} calls on {@code spy}, for example
     * {@code PausePoint.after(accounts, repository -> repository.findEntries(anyLong()))}.
     */
    public static <T> PausePoint after(T spy, Consumer<T> method) {
      PausePoint pause = new PausePoint();
      method.accept(Mockito.doAnswer(pause).when(spy));
      return pause;
    }

    @Override
    public Object answer(InvocationOnMock invocation) throws Throwable {
      Object result = invocation.callRealMethod();
      if (calls.incrementAndGet() == 1) {
        reached.countDown();
        if (!released.await(WAIT_LIMIT.toMillis(), TimeUnit.MILLISECONDS)) {
          throw new IllegalStateException("The test never released the pause point");
        }
      }
      return result;
    }

    /** Waits until the first call is held at the pause point. */
    public void awaitReached(Duration limit) throws InterruptedException {
      if (!reached.await(limit.toMillis(), TimeUnit.MILLISECONDS)) {
        throw new AssertionError("No call reached the pause point within " + limit);
      }
    }

    public void release() {
      released.countDown();
    }

    public int calls() {
      return calls.get();
    }
  }
}
