/**
 * Translation between FIX 4.4 and the SBE messages of {@code rates-messages}, and the small
 * {@link io.ratesalgo.fix.gateway.MessageSink} and {@link io.ratesalgo.fix.gateway.MessageSource}
 * interfaces through which the coordinator wires a gateway to the event-loop ring buffers
 * (API_SPEC.md Section 9). Hot-path package: no allocation per message.
 */
package io.ratesalgo.fix.gateway;
