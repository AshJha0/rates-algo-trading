package io.ratesalgo.fix.session;

import io.ratesalgo.fix.codec.FixParser;

/** Application callbacks from a {@link FixSession}. Called on the session's thread. */
public interface FixSessionHandler {

  /** An application message arrived in sequence; {@code possDup} is true for a resent message. */
  void onApplicationMessage(FixSession session, FixParser message, boolean possDup);

  /** The counterparty accepted our logon. */
  default void onLogon(final FixSession session) {}

  /** The session ended (logout exchanged, timeout or transport loss). */
  default void onDisconnect(final FixSession session) {}

  /** The counterparty rejected one of our messages at session level (35=3). */
  default void onSessionReject(final FixSession session, final FixParser message) {}
}
