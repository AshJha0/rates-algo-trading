package io.ratesalgo.refdata;

/**
 * A standard swap of one market and tenor.
 *
 * @param instrumentId id used on the wire
 * @param market template family, for example USD-SOFR
 * @param currency ISO 4217
 * @param index overnight index name
 * @param tenor tenor code such as 10Y
 * @param fixedFrequency fixed leg payments per year
 * @param fixedDayCount fixed leg day count
 * @param floatFrequency floating leg payments per year
 * @param floatDayCount floating leg day count
 * @param paymentLagDays business days between period end and payment
 * @param calendar holiday calendar name
 * @param spotLagDays business days from trade to effective date
 * @param madeAvailableToTrade true for CFTC made-available-to-trade tenors (Required Transactions)
 * @param blockThreshold notional in currency units at or above which a trade is a block
 */
public record SwapTemplate(
    int instrumentId,
    String market,
    String currency,
    String index,
    String tenor,
    int fixedFrequency,
    String fixedDayCount,
    int floatFrequency,
    String floatDayCount,
    int paymentLagDays,
    String calendar,
    int spotLagDays,
    boolean madeAvailableToTrade,
    long blockThreshold) {}
