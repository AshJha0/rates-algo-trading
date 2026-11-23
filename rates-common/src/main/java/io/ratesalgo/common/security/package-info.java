/**
 * Secrets and TLS material shared by the FIX transport, the venue simulators and the control
 * endpoint: a {@link io.ratesalgo.common.security.Secret} that never prints itself, a {@link
 * io.ratesalgo.common.security.TlsConfig} that builds an {@code SSLContext} from key store files,
 * and a keytool wrapper that makes throwaway certificates for tests and rehearsals.
 */
package io.ratesalgo.common.security;
