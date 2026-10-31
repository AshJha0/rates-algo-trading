package io.ratesalgo.refdata;

import java.time.LocalDate;

/**
 * A bond futures contract specification.
 *
 * @param instrumentId id used on the wire
 * @param symbol exchange symbol with month and year code
 * @param exchange listing exchange
 * @param underlying deliverable basket family
 * @param currency ISO 4217
 * @param contractSize face value per contract in currency units
 * @param tickSizeMantissa minimum price increment, mantissa with exponent -9
 * @param notionalCouponPercent notional coupon used for conversion factors
 * @param deliveryMonth contract month as YYYY-MM
 * @param firstDeliveryDate first delivery day
 * @param lastTradingDate last trading day
 */
public record FuturesContract(
    int instrumentId,
    String symbol,
    String exchange,
    String underlying,
    String currency,
    long contractSize,
    long tickSizeMantissa,
    int notionalCouponPercent,
    String deliveryMonth,
    LocalDate firstDeliveryDate,
    LocalDate lastTradingDate) {}
