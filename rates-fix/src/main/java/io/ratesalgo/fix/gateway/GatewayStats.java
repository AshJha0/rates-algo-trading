package io.ratesalgo.fix.gateway;

/** Counters kept by a {@link VenueGateway}. */
public final class GatewayStats {

  long inboundTranslated;
  long outboundTranslated;
  long unknownInstrument;
  long unsupportedInbound;
  long unsupportedOutbound;
  long droppedNotActive;
  long sinkRejected;
  long quoteAcks;
  long cancelRejects;
  long businessRejects;
  long marketDataRejects;
  long lastQuoteStatus = -1;
  long massStatusEmpty;

  /** FIX messages turned into SBE messages. */
  public long inboundTranslated() {
    return inboundTranslated;
  }

  /** SBE messages turned into FIX messages. */
  public long outboundTranslated() {
    return outboundTranslated;
  }

  /** Inbound messages naming a SecurityID that is not registered. */
  public long unknownInstrument() {
    return unknownInstrument;
  }

  /** Inbound application messages of a type the gateway does not translate. */
  public long unsupportedInbound() {
    return unsupportedInbound;
  }

  /** Outbound SBE templates the gateway does not translate. */
  public long unsupportedOutbound() {
    return unsupportedOutbound;
  }

  /** Outbound messages refused because the session was not active. */
  public long droppedNotActive() {
    return droppedNotActive;
  }

  /** Inbound translations the engine-side sink refused. */
  public long sinkRejected() {
    return sinkRejected;
  }

  /** QuoteStatusReports that acknowledged a quote, cancel or status request. */
  public long quoteAcks() {
    return quoteAcks;
  }

  /** OrderCancelReject (35=9) messages received. */
  public long cancelRejects() {
    return cancelRejects;
  }

  /** BusinessMessageReject (35=j) messages received. */
  public long businessRejects() {
    return businessRejects;
  }

  /** MarketDataRequestReject (35=Y) messages received. */
  public long marketDataRejects() {
    return marketDataRejects;
  }

  /** Order mass status replies reporting no open orders (TotNumReports 0). */
  public long massStatusEmpty() {
    return massStatusEmpty;
  }

  /** QuoteStatus (297) of the last QuoteStatusReport, -1 before the first. */
  public long lastQuoteStatus() {
    return lastQuoteStatus;
  }
}
