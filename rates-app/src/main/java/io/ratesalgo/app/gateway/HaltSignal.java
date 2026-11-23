package io.ratesalgo.app.gateway;

/**
 * A venue session's halt state as other threads see it: written by the session's owner thread when
 * a value changes, read by the supervisor, the kill call and the status endpoint.
 */
final class HaltSignal {

  private volatile int state;
  private volatile int unconfirmed;

  void state(final int value) {
    state = value;
  }

  int state() {
    return state;
  }

  void unconfirmed(final int value) {
    unconfirmed = value;
  }

  int unconfirmed() {
    return unconfirmed;
  }
}
