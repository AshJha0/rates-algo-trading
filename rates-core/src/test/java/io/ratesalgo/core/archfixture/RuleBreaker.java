package io.ratesalgo.core.archfixture;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/** Every hot-path violation in one class. Never used outside HotPathRulesTest. */
public final class RuleBreaker {

  private final ReentrantLock lock = new ReentrantLock();
  private final List<Long> seen = new ArrayList<>();
  private String last = "";

  public synchronized int size() {
    return seen.size();
  }

  public void onMessage(final long value) {
    lock.lock();
    try {
      seen.add(value);
      last = "value " + value;
    } finally {
      lock.unlock();
    }
  }

  public String last() {
    return last;
  }
}
