package io.ratesalgo.risk;

import io.ratesalgo.messages.sbe.KillReason;

/** A component that must withdraw everything it has outstanding when the kill switch fires. */
@FunctionalInterface
public interface KillSwitchListener {

  /** Cancel resting orders, pull quotes, cancel parents; called once per kill on the loop. */
  void onKill(KillReason reason, long epochNanos);
}
