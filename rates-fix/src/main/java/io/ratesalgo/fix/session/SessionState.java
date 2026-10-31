package io.ratesalgo.fix.session;

/** Engine-side session states. */
public enum SessionState {
  /** No transport or the session has ended. */
  DISCONNECTED,
  /** Logon sent, waiting for the counterparty's logon. */
  LOGON_SENT,
  /** Logged on; application messages flow. */
  ACTIVE,
  /** Logout sent, waiting for the counterparty's logout. */
  LOGOUT_SENT
}
