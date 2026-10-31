package io.ratesalgo.risk;

/** States of the kill switch (docs/diagrams/kill_switch_state.mmd). */
public enum KillState {
  /** Trading allowed. */
  ACTIVE,
  /** Everything withdrawn; new quotes and orders refused. */
  KILLED,
  /** An operator asked to resume; waiting for a second person and healthy inputs. */
  RESET_PENDING
}
