package io.ratesalgo.core;

/**
 * Builds the exceptions for breaches of the ring-buffer contract. Kept apart from the message paths
 * so that the string building happens only when something is already wrong (API_SPEC.md Section 4:
 * a contract break throws and stops the component).
 */
final class ContractBreach {

  private ContractBreach() {}

  static IllegalArgumentException length(final int length) {
    return new IllegalArgumentException(
        "message length "
            + length
            + " outside ["
            + RingBufferContract.SBE_HEADER_LENGTH
            + ", "
            + RingBufferContract.MAX_MESSAGE_LENGTH
            + "]");
  }

  static IllegalStateException typeMismatch(final int msgTypeId, final int templateId) {
    return new IllegalStateException(
        "ring record type " + msgTypeId + " does not equal SBE template id " + templateId);
  }

  static IllegalStateException schema(final int schemaId, final int version, final int templateId) {
    return new IllegalStateException(
        "template "
            + templateId
            + ": schema id "
            + schemaId
            + " version "
            + version
            + "; expected schema 1 version 0 or control schema "
            + ControlMessages.SCHEMA_ID
            + " version "
            + ControlMessages.SCHEMA_VERSION);
  }

  static IllegalStateException reserved(final int templateId) {
    return new IllegalStateException(
        "template "
            + templateId
            + " is written by the loop only and is not accepted from a ring"
            + " (Heartbeat with sourceId 0, LoopError, Backpressure)");
  }

  static IllegalArgumentException gateway(final int gatewayId, final int gateways) {
    return new IllegalArgumentException(
        "gateway " + gatewayId + " not configured; this loop has " + gateways);
  }
}
