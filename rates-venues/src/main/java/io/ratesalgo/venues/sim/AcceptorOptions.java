package io.ratesalgo.venues.sim;

import io.ratesalgo.common.security.Secret;
import io.ratesalgo.common.security.TlsConfig;
import java.nio.file.Path;

/**
 * How a simulator's acceptor listens.
 *
 * @param tls TLS settings (key store; trust store when client certificates are required), or null
 *     for plain FIX, which listens on loopback only
 * @param username Username (553) every Logon must carry, or null to ask for no credentials
 * @param password Password (554) every Logon must carry; set with the username or not at all
 * @param storeDir directory where the venue keeps its sequence numbers, sent messages and id
 *     generation across restarts, or null to keep them in memory and start ids at 1
 */
public record AcceptorOptions(TlsConfig tls, String username, Secret password, Path storeDir) {

  /** Plain FIX on loopback, no credentials, nothing kept across restarts. */
  public static final AcceptorOptions PLAIN = new AcceptorOptions(null, null, null, null);

  /** Validates the options. */
  public AcceptorOptions {
    if ((username == null) != (password == null)) {
      throw new IllegalArgumentException("give a username and a password, or neither");
    }
    if (username != null && username.isEmpty()) {
      throw new IllegalArgumentException("the username must not be empty");
    }
    if (tls != null && tls.keyStore() == null) {
      throw new IllegalArgumentException("the TLS settings of an acceptor need a key store");
    }
  }
}
