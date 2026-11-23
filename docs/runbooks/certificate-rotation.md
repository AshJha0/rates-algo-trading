# Runbook: certificate and password rotation

Since 1.1.0 FIX runs over TLS 1.3 with Logon credentials and the control endpoint over HTTPS (API_SPEC.md 17.11, 17.12; deploy/README.md, "Certificate rotation"). Certificates and keys are PKCS#12 files named by path in the configuration. Passwords are named by an environment variable or a file, never written in a configuration file (`Secret`). A process reads its stores and secrets once, at start, so every rotation is a restart.

## When

- Before a certificate expires. Check the dates: `keytool -list -v -keystore STORE.p12 | grep Valid`. An expired certificate fails the FIX handshake (`TcpInitiatorTlsTest.anExpiredServerCertificateIsRefusedEvenWhenItIsInTheTrustStore`) and the health checks.
- When a key or a password may have been exposed: at once.
- When the issuing certificate authority changes.

## Steps

1. Get the new stores from the deployment's certificate authority: the server key store (HTTP endpoints and the simulators' FIX acceptors) and the trust store (FIX initiators). Do not commit them.
2. Put them in place under the same paths the configuration names, by default `/run/rates/certs/server-keystore.p12` and `/run/rates/certs/truststore.p12`, or change the paths through a reviewed configuration change.
3. If a store password or the FIX password changes, update the secret store that feeds `RATES_HTTP_KEYSTORE_PASSWORD`, `RATES_FIX_TRUSTSTORE_PASSWORD`, `RATES_FIX_ACCEPTOR_KEYSTORE_PASSWORD` or `RATES_FIX_PASSWORD`, or the file a `...File` key names. A FIX password change must be agreed with the venue for the same time.
4. Restart one colour at a time with `deploy/bluegreen.sh`, so quoting never depends on a process that is restarting. Give it `BLUE_URL` and `GREEN_URL` as `https://` addresses and point `CURL_CA_BUNDLE` at `ca.pem`.
5. Check the restarted colour: `/health/ready` answers 200 over HTTPS, both FIX sessions logged on, and `GET /control/status` shows `"ready":true`. The restart recovers from the journals as any restart does (restart.md).

When the issuer changes, rotate in three restarts: first a trust store holding both the old and the new issuer; then the new key stores; then a trust store with the new issuer only.

## What fails, and how it shows

- A variable that is unset or a file that cannot be read: the process exits 2 at start with a message naming the variable or file, never the value (`SecurityConfigTest`).
- A server certificate the trust store does not hold, an expired one, or one for another host name: the handshake fails and the session does not log on (`TcpInitiatorTlsTest`). The session retries under `fix.reconnect` and then gives up.
- A wrong FIX password: the venue answers the Logon with a Logout carrying a reason, then disconnects (`VenueSecurityTest.aWrongPasswordGetsALogoutWithAReasonAndADisconnect`).
- A key store that cannot be opened: the HTTPS server stops before it binds (`HttpsEndpointTest.aKeyStoreThatCannotBeOpenedStopsTheServerBeforeItBinds`).

## Limits

- No rotation without a restart: nothing reloads a store in a running process.
- The TLS KeyUpdate path inside a long-lived session is untested.

## Post-incident

For a rotation forced by exposure, complete [post-incident-template.md](post-incident-template.md) with what was exposed, when it was replaced and which sessions used it.
