/**
 * Simulator v2: the one market model of docs/research/SIM_V2_DESIGN.md. The venue simulators and
 * the back-test day both read {@link io.ratesalgo.sim.MarketModel}, so there is one specification
 * of rates, RFQ flow, competitors and order books. Doubles inside the model; ticks and fixed-point
 * prices only where a price leaves it.
 */
package io.ratesalgo.sim;
