package io.ratesalgo.common.security;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.List;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;

/**
 * TLS settings for one end of a connection, and the {@link SSLContext} built from them.
 *
 * <p>Key stores are PKCS#12 files named by path; their passwords are {@link Secret}s read from the
 * environment or from a file. A client needs a trust store (whom it believes) and, for mutual TLS,
 * a key store (who it is). A server needs a key store and, to ask clients for a certificate, a
 * trust store. TLS 1.3 is the only protocol unless the caller names others, and a client checks
 * that the certificate was issued to the host it dialled.
 *
 * <p>Nothing here falls back: a store that cannot be read, a wrong password or an unsupported
 * protocol throws when the context or an engine is built, before any connection is made.
 */
public final class TlsConfig {

  /** The default protocol list: TLS 1.3 only. */
  public static final List<String> DEFAULT_PROTOCOLS = List.of("TLSv1.3");

  /** Default time allowed for a handshake: five seconds. */
  public static final long DEFAULT_HANDSHAKE_TIMEOUT_NANOS = 5_000_000_000L;

  private static final String STORE_TYPE = "PKCS12";

  private final Path trustStore;
  private final Secret trustStorePassword;
  private final Path keyStore;
  private final Secret keyStorePassword;
  private final List<String> protocols;
  private final boolean verifyHostname;
  private final boolean requireClientCertificate;
  private final long handshakeTimeoutNanos;
  private final Object lock = new Object();
  private SSLContext context;

  private TlsConfig(final Builder b) {
    this.trustStore = b.trustStore;
    this.trustStorePassword = b.trustStorePassword;
    this.keyStore = b.keyStore;
    this.keyStorePassword = b.keyStorePassword;
    this.protocols = List.copyOf(b.protocols);
    this.verifyHostname = b.verifyHostname;
    this.requireClientCertificate = b.requireClientCertificate;
    this.handshakeTimeoutNanos = b.handshakeTimeoutNanos;
  }

  /** Starts a configuration. */
  public static Builder builder() {
    return new Builder();
  }

  /** Builder of a {@link TlsConfig}. */
  public static final class Builder {
    private Path trustStore;
    private Secret trustStorePassword;
    private Path keyStore;
    private Secret keyStorePassword;
    private List<String> protocols = DEFAULT_PROTOCOLS;
    private boolean verifyHostname = true;
    private boolean requireClientCertificate;
    private long handshakeTimeoutNanos = DEFAULT_HANDSHAKE_TIMEOUT_NANOS;

    private Builder() {}

    /** The certificates this end believes. */
    public Builder trustStore(final Path path, final Secret password) {
      this.trustStore = path;
      this.trustStorePassword = password;
      return this;
    }

    /** This end's own certificate and private key. */
    public Builder keyStore(final Path path, final Secret password) {
      this.keyStore = path;
      this.keyStorePassword = password;
      return this;
    }

    /** Protocols to enable instead of TLS 1.3 alone. */
    public Builder protocols(final List<String> names) {
      this.protocols = List.copyOf(names);
      return this;
    }

    /** Client side: whether the certificate must name the host dialled. On unless turned off. */
    public Builder verifyHostname(final boolean verify) {
      this.verifyHostname = verify;
      return this;
    }

    /** Server side: refuse clients that present no certificate the trust store accepts. */
    public Builder requireClientCertificate(final boolean require) {
      this.requireClientCertificate = require;
      return this;
    }

    /** Time allowed for a handshake before the connection is dropped. */
    public Builder handshakeTimeoutNanos(final long nanos) {
      this.handshakeTimeoutNanos = nanos;
      return this;
    }

    /**
     * Validates and builds.
     *
     * @throws IllegalArgumentException if no store is named, no protocol is listed, the timeout is
     *     not positive, or client certificates are required with no trust store to check them
     */
    public TlsConfig build() {
      if (trustStore == null && keyStore == null) {
        throw new IllegalArgumentException("TLS needs a trust store, a key store or both");
      }
      if (protocols.isEmpty()) {
        throw new IllegalArgumentException("TLS needs at least one protocol");
      }
      if (handshakeTimeoutNanos <= 0) {
        throw new IllegalArgumentException("the handshake timeout must be positive");
      }
      if (requireClientCertificate && trustStore == null) {
        throw new IllegalArgumentException(
            "asking clients for a certificate needs a trust store to check it against");
      }
      return new TlsConfig(this);
    }
  }

  public Path trustStore() {
    return trustStore;
  }

  public Secret trustStorePassword() {
    return trustStorePassword;
  }

  public Path keyStore() {
    return keyStore;
  }

  public Secret keyStorePassword() {
    return keyStorePassword;
  }

  public List<String> protocols() {
    return protocols;
  }

  public boolean verifyHostname() {
    return verifyHostname;
  }

  public boolean requireClientCertificate() {
    return requireClientCertificate;
  }

  public long handshakeTimeoutNanos() {
    return handshakeTimeoutNanos;
  }

  /**
   * The context for these stores, built once.
   *
   * @throws IllegalStateException if a store cannot be read or its password is wrong; the message
   *     names the file, never a password
   */
  public SSLContext context() {
    synchronized (lock) {
      if (context == null) {
        context = build();
      }
      return context;
    }
  }

  private SSLContext build() {
    try {
      KeyManagerFactory keys = null;
      if (keyStore != null) {
        final char[] password = chars(keyStorePassword);
        try {
          keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
          keys.init(load(keyStore, password), password);
        } finally {
          Arrays.fill(password, '\0');
        }
      }
      TrustManagerFactory trust = null;
      if (trustStore != null) {
        final char[] password = chars(trustStorePassword);
        try {
          trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
          trust.init(load(trustStore, password));
        } finally {
          Arrays.fill(password, '\0');
        }
      }
      final SSLContext built = SSLContext.getInstance("TLS");
      built.init(keys == null ? null : keys.getKeyManagers(), trust == null ? null : trust.getTrustManagers(), null);
      return built;
    } catch (final GeneralSecurityException e) {
      throw new IllegalStateException("cannot build the TLS context: " + e.getClass().getSimpleName(), e);
    }
  }

  private static char[] chars(final Secret password) {
    return password == null ? new char[0] : password.chars();
  }

  private static KeyStore load(final Path file, final char[] password) throws GeneralSecurityException {
    final KeyStore store = KeyStore.getInstance(STORE_TYPE);
    try (InputStream in = Files.newInputStream(file)) {
      store.load(in, password);
    } catch (final IOException e) {
      // a wrong password surfaces here too; the cause is kept, and the password is not in it
      throw new IllegalStateException("cannot open the key store " + file + " (missing file or wrong password)", e);
    }
    return store;
  }

  /** A client engine for a connection to {@code host:port}, with these protocols and checks. */
  public SSLEngine clientEngine(final String host, final int port) {
    final SSLEngine engine = context().createSSLEngine(host, port);
    engine.setUseClientMode(true);
    final SSLParameters p = engine.getSSLParameters();
    p.setProtocols(protocols.toArray(new String[0]));
    if (verifyHostname) {
      p.setEndpointIdentificationAlgorithm("HTTPS");
    }
    engine.setSSLParameters(p);
    return engine;
  }

  /** A server engine with these protocols; asks for a client certificate if configured to. */
  public SSLEngine serverEngine() {
    final SSLEngine engine = context().createSSLEngine();
    engine.setUseClientMode(false);
    final SSLParameters p = engine.getSSLParameters();
    p.setProtocols(protocols.toArray(new String[0]));
    p.setNeedClientAuth(requireClientCertificate);
    engine.setSSLParameters(p);
    return engine;
  }

  /** Paths and flags only; no password is part of it. */
  @Override
  public String toString() {
    return "TlsConfig[trustStore=" + trustStore + ", keyStore=" + keyStore + ", protocols=" + protocols
        + ", verifyHostname=" + verifyHostname + ", requireClientCertificate=" + requireClientCertificate + "]";
  }
}
