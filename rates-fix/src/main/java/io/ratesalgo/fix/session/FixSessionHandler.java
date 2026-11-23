package io.ratesalgo.fix.session;

import io.ratesalgo.fix.codec.FixParser;

/** Application callbacks from a {@link FixSession}. Called on the session's thread. */
public interface FixSessionHandler {

  /** An application message arrived in sequence; {@code possDup} is true for a resent message. */
  void onApplicationMessage(FixSession session, FixParser message, boolean possDup);

  /** The counterparty accepted our logon. */
  default void onLogon(final FixSession session) {}

  /**
   * The counterparty sent a Logout. {@code logonRefused} is true when it came in answer to our
   * Logon, which is how a venue refuses credentials; Text (58), if present, carries its reason.
   * The disconnect follows through {@link #onDisconnect}.
   */
  default void onLogout(final FixSession session, final FixParser message, final boolean logonRefused) {}

  /** The session ended (logout exchanged, timeout or transport loss). */
  default void onDisconnect(final FixSession session) {}

  /** The counterparty rejected one of our messages at session level (35=3). */
  default void onSessionReject(final FixSession session, final FixParser message) {}
}
