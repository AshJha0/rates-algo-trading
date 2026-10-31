/**
 * Engine-side FIX 4.4 session layer: logon, heartbeat, test request, resend request answered by
 * gap fill, sequence reset, and gap detection with resend requests. Hot-path package: no
 * allocation per message after construction.
 */
package io.ratesalgo.fix.session;
