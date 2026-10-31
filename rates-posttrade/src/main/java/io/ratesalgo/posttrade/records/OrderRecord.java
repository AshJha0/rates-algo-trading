package io.ratesalgo.posttrade.records;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * One order record in the layout of UK RTS 6 Article 28 and Annex II (API_SPEC.md Section 17.4).
 * Annex II field numbers are given as {@code T2.n} (Table 2, initial decisions and incoming client
 * orders) and {@code T3.n} (Table 3, outgoing and executed orders). Fields that do not apply to
 * the simulated flow (options, short selling, waivers, pegged prices) are not carried; the
 * inventory in docs/compliance/RTS6_INVENTORY.md lists them.
 *
 * @param recordSequence T3.25 sequence number, ascending per keeper
 * @param tradingDate the UTC trading date, ISO 8601
 * @param table {@code T2} for an incoming client request, {@code T3} for our orders, quotes and
 *     executions
 * @param eventType T3.26 order event type (NEWO, PARF, FILL, CAME, REMO, EXPI, and the
 *     venue-specific codes BUST and AMND)
 * @param buySell T3.1 / T2.8 {@code BUYI} or {@code SELL}, our side for T3, the client's for T2
 * @param tradingCapacity T3.2, always {@code DEAL} (principal)
 * @param liquidityProvision T3.3, true for quotes and RFQ trades
 * @param executionWithinFirm T3.4 / T2.6 algorithm inventory id
 * @param internalOrderId T3.5 our clOrdId or quoteId, or T2.7 the venue's rfqId
 * @param venueOrderId T3.6, 0 when the venue assigns none
 * @param orderReceiver T3.7 venue code; the MIC is bound by the venue gateway (P2)
 * @param orderType T3.8 {@code LMT}, {@code MKT}, {@code QUOTE} or {@code RFQ}
 * @param limitPrice T3.9 / T2.10 Decimal9 mantissa, 0 when not applicable
 * @param priceCurrency T3.10 / T2.13
 * @param priceNotation T3.11 / T2.11
 * @param remainingQuantity T3.15
 * @param tradedQuantity T3.17
 * @param submissionNanos T3.23 / T2.27 epoch nanoseconds
 * @param receiptNanos T3.24 epoch nanoseconds of the venue message, 0 for our own submissions
 * @param venueTransactionId T3.30 the venue's execution id, empty if none
 * @param validityPeriod T3.31 {@code GDAY}, {@code IOCA}, {@code FOKV}, {@code GTDT}
 * @param instrumentIdentifier T2.9 ISIN or placeholder from the securities master
 * @param instrumentId internal instrument id
 * @param clientId T2.3 client id for incoming requests, 0 otherwise
 * @param journalSequence the journal frame the record was derived from
 * @param tradeKey the trade key for execution events, empty otherwise
 */
public record OrderRecord(
    long recordSequence,
    String tradingDate,
    String table,
    String eventType,
    String buySell,
    String tradingCapacity,
    boolean liquidityProvision,
    String executionWithinFirm,
    long internalOrderId,
    long venueOrderId,
    String orderReceiver,
    String orderType,
    long limitPrice,
    String priceCurrency,
    String priceNotation,
    long remainingQuantity,
    long tradedQuantity,
    long submissionNanos,
    long receiptNanos,
    String venueTransactionId,
    String validityPeriod,
    String instrumentIdentifier,
    int instrumentId,
    int clientId,
    long journalSequence,
    String tradeKey) {

  /** CSV header, in component order. */
  public static final String CSV_HEADER =
      "record_sequence,trading_date,table,event_type,buy_sell,trading_capacity,"
          + "liquidity_provision,execution_within_firm,internal_order_id,venue_order_id,"
          + "order_receiver,order_type,limit_price,price_currency,price_notation,"
          + "remaining_quantity,traded_quantity,submission_time,receipt_time,"
          + "venue_transaction_id,validity_period,instrument_identifier,instrument_id,client_id,"
          + "journal_sequence,trade_key";

  private static final Pattern COMMA = Pattern.compile(",");

  /** One CSV line; prices as decimals, times as ISO 8601 UTC. No field contains a comma. */
  public String toCsv() {
    final List<String> f = new ArrayList<>(26);
    f.add(Long.toString(recordSequence));
    f.add(tradingDate);
    f.add(table);
    f.add(eventType);
    f.add(buySell);
    f.add(tradingCapacity);
    f.add(Boolean.toString(liquidityProvision));
    f.add(executionWithinFirm);
    f.add(Long.toString(internalOrderId));
    f.add(Long.toString(venueOrderId));
    f.add(orderReceiver);
    f.add(orderType);
    f.add(BigDecimal.valueOf(limitPrice, 9).toPlainString());
    f.add(priceCurrency);
    f.add(priceNotation);
    f.add(Long.toString(remainingQuantity));
    f.add(Long.toString(tradedQuantity));
    f.add(isoTime(submissionNanos));
    f.add(isoTime(receiptNanos));
    f.add(venueTransactionId);
    f.add(validityPeriod);
    f.add(instrumentIdentifier);
    f.add(Integer.toString(instrumentId));
    f.add(Integer.toString(clientId));
    f.add(Long.toString(journalSequence));
    f.add(tradeKey);
    for (final String s : f) {
      if (s.indexOf(',') >= 0) {
        throw new IllegalStateException("field contains a comma: " + s);
      }
    }
    return String.join(",", f);
  }

  /** Parses {@link #toCsv()}. */
  public static OrderRecord fromCsv(final String line) {
    final String[] f = COMMA.split(line, -1);
    if (f.length != 26) {
      throw new IllegalArgumentException("order record needs 26 fields, got " + f.length);
    }
    return new OrderRecord(
        Long.parseLong(f[0]),
        f[1],
        f[2],
        f[3],
        f[4],
        f[5],
        Boolean.parseBoolean(f[6]),
        f[7],
        Long.parseLong(f[8]),
        Long.parseLong(f[9]),
        f[10],
        f[11],
        new BigDecimal(f[12]).movePointRight(9).longValueExact(),
        f[13],
        f[14],
        Long.parseLong(f[15]),
        Long.parseLong(f[16]),
        parseTime(f[17]),
        parseTime(f[18]),
        f[19],
        f[20],
        f[21],
        Integer.parseInt(f[22]),
        Integer.parseInt(f[23]),
        Long.parseLong(f[24]),
        f[25]);
  }

  /** ISO 8601 UTC with nanoseconds, or empty for 0. */
  public static String isoTime(final long epochNanos) {
    if (epochNanos == 0) {
      return "";
    }
    return Instant.ofEpochSecond(
            Math.floorDiv(epochNanos, 1_000_000_000L), Math.floorMod(epochNanos, 1_000_000_000L))
        .toString();
  }

  private static long parseTime(final String text) {
    if (text.isEmpty()) {
      return 0;
    }
    final Instant i = Instant.parse(text);
    return Math.addExact(Math.multiplyExact(i.getEpochSecond(), 1_000_000_000L), i.getNano());
  }
}
