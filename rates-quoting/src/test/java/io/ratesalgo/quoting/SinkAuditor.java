package io.ratesalgo.quoting;

import io.ratesalgo.api.CurrencyCode;
import io.ratesalgo.api.Decimal9;
import io.ratesalgo.api.Dv01Vector;
import io.ratesalgo.api.HedgeOrderSink;
import io.ratesalgo.api.Instrument;
import io.ratesalgo.api.KeyRateTenors;
import io.ratesalgo.api.ProductType;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.messages.sbe.NewOrderDecoder;
import io.ratesalgo.messages.sbe.QuoteDecoder;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.risk.RiskEngine;
import io.ratesalgo.risk.RiskLimits;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.agrona.DirectBuffer;

/**
 * Independent check of every Quote and NewOrder that reaches the gateway and every parent that
 * reaches the router. It recomputes the risk envelope by brute force from positions, reservations
 * and the sensitivity provider (not from the risk engine's incremental aggregates), and checks the
 * kill switch, the price collar, input age, time to live and the quote rate. Each failure is
 * recorded with a description.
 *
 * <p>A limit already breached may be left breached by a message that does not add to it
 * (PNL_AUDIT.md item 14): a breach counts only where the message's own PV01 pushes the breached
 * extreme further out. Hedge parents may reach the router during a daily-loss kill when they
 * reduce net PV01.
 */
final class SinkAuditor implements HedgeOrderSink {

  private static final int K = KeyRateTenors.COUNT;
  private static final double REL = 1e-9;

  private TestMarket m;
  private final RiskLimits limits;
  private final QuoteDecoder quote = new QuoteDecoder();
  private final NewOrderDecoder order = new NewOrderDecoder();
  private final Dv01Vector v = new Dv01Vector(CurrencyCode.USD);
  private final List<String> violations = new ArrayList<>();
  private final ArrayDeque<Long> quoteTimes = new ArrayDeque<>();
  final Map<Long, Long> quotePrice = new HashMap<>();
  final List<long[]> parents = new ArrayList<>();
  long quotesSeen;
  long ordersSeen;
  long parentsSeen;

  SinkAuditor(final RiskLimits limits) {
    this.limits = limits;
  }

  /** Attaches to a market whose gateway this auditor listens to. */
  void bind(final TestMarket market) {
    this.m = market;
    market.gateway.keep(false);
    market.control.keep(false);
    market.gateway.listener((templateId, b, offset, length) -> onGateway(templateId, b, offset));
  }

  List<String> violations() {
    return violations;
  }

  void onGateway(final int templateId, final DirectBuffer b, final int offset) {
    final long now = m.clock.epochNanos();
    if (templateId == QuoteDecoder.TEMPLATE_ID) {
      quotesSeen++;
      quote.wrap(b, offset + MessageHeaderEncoder.ENCODED_LENGTH, QuoteDecoder.BLOCK_LENGTH, 0);
      final int id = quote.instrumentId();
      final boolean weBuy = quote.bidQuantity() > 0;
      final long px = weBuy ? quote.bidPrice().mantissa() : quote.askPrice().mantissa();
      quotePrice.put(quote.quoteId(), px);
      final double own = m.directory.byId(id).major(weBuy ? quote.bidQuantity() : -quote.askQuantity());
      common("quote " + quote.quoteId(), id, px, own);
      if (quote.validUntilNanos() > now + m.desk.quoting().config().firmTtlNanos()) {
        fail("quote " + quote.quoteId() + " ttl beyond config");
      }
      inputsFresh("quote " + quote.quoteId(), id, now);
      quoteTimes.addLast(now);
      while (quoteTimes.peekFirst() <= now - 1_000_000_000L) {
        quoteTimes.removeFirst();
      }
      if (quoteTimes.size() > 2.0 * limits.quoteRatePerSecond() + 1) {
        fail("quote rate " + quoteTimes.size() + " in one second");
      }
    } else if (templateId == NewOrderDecoder.TEMPLATE_ID) {
      ordersSeen++;
      order.wrap(b, offset + MessageHeaderEncoder.ENCODED_LENGTH, NewOrderDecoder.BLOCK_LENGTH, 0);
      final double own =
          m.directory.byId(order.instrumentId()).major(order.side() == Side.Buy ? order.quantity() : -order.quantity());
      common("order " + order.clOrdId(), order.instrumentId(), order.price().mantissa(), own);
      if (order.quantity() > limits.maxOrderSize()) {
        fail("order size");
      }
    }
  }

  @Override
  public boolean submit(
      final long parentId,
      final int instrumentId,
      final Side side,
      final long quantityMinor,
      final long limitPrice,
      final long urgencyNanos,
      final long sentNanos) {
    parentsSeen++;
    positions(instrumentId, "parent " + parentId);
    final Instrument inst = m.directory.byId(instrumentId);
    final double own = inst.major(side == Side.Buy ? quantityMinor : -quantityMinor);
    final double orderPv01 = unitParallel(instrumentId) * own;
    final double net = full(inst.currency()).parallel();
    final boolean reducing = orderPv01 * net < 0.0 && Math.abs(orderPv01) <= Math.abs(net) * (1 + REL);
    if (!m.desk.killSwitch().isActive() && !(m.desk.risk().flattenOnly() && reducing)) {
      fail("parent " + parentId + " while killed");
    }
    if (!reducing) {
      envelope(inst.currency(), "parent " + parentId, instrumentId, own);
    }
    collar("parent " + parentId, instrumentId, limitPrice);
    parents.add(new long[] {parentId, instrumentId, side == Side.Buy ? quantityMinor : -quantityMinor, limitPrice});
    return true;
  }

  @Override
  public void cancel(final long parentId) {}

  @Override
  public void cancelAll() {
    parents.clear();
  }

  private void common(final String what, final int id, final long px, final double own) {
    if (!m.desk.killSwitch().isActive()) {
      fail(what + " sent while kill switch " + m.desk.killSwitch().state());
    }
    positions(id, what);
    envelope(m.directory.byId(id).currency(), what, id, own);
    collar(what, id, px);
  }

  private void collar(final String what, final int id, final long px) {
    final Instrument inst = m.directory.byId(id);
    final double fair = Decimal9.toDouble(m.fair.fairMid(id));
    final double bp = Math.abs(Decimal9.toDouble(px) - fair) * inst.quoteValue() / Math.abs(unitParallel(id));
    if (bp > limits.priceCollarBp() * (1 + REL) + 1e-9) {
      fail(what + " collar " + bp);
    }
    if (Math.abs(Decimal9.toDouble(px) / fair - 1.0) > limits.fatFingerFraction()) {
      fail(what + " fat finger");
    }
  }

  private void inputsFresh(final String what, final int id, final long now) {
    final long max = m.desk.quoting().config().maxInputAgeNanos();
    final Instrument inst = m.directory.byId(id);
    if (now - m.fair.referenceNanos(id) > max && inst.type() == ProductType.SWAP) {
      fail(what + " priced off a stale curve input");
    }
    if (inst.type() != ProductType.SWAP && now - m.book.updateNanos(id) > max && now - m.fair.referenceNanos(id) > max) {
      fail(what + " priced off a stale book");
    }
    if (inst.signalInstrumentId() != 0 && m.book.updateNanos(inst.signalInstrumentId()) != Long.MIN_VALUE
        && now - m.book.updateNanos(inst.signalInstrumentId()) > max) {
      fail(what + " priced off a stale futures signal");
    }
  }

  private void positions(final int id, final String what) {
    final RiskEngine risk = m.desk.risk();
    final long max = limits.maxPosition(id);
    if (risk.position(id) + risk.pendingBuy(id) > max || risk.position(id) - risk.pendingSell(id) < -max) {
      fail(what + " position envelope");
    }
  }

  private Dv01Vector full(final CurrencyCode ccy) {
    final Dv01Vector out = new Dv01Vector(ccy);
    for (final int id : TestMarket.ALL) {
      if (m.directory.byId(id).currency() == ccy) {
        m.sensitivities.pv01(id, m.desk.risk().position(id), v);
        out.addScaled(v, 1.0);
      }
    }
    return out;
  }

  /** {@code own} is the message's signed quantity in major units of instrument {@code sent}. */
  private void envelope(final CurrencyCode ccy, final String what, final int sent, final double own) {
    m.sensitivities.pv01(sent, m.directory.byId(sent).minorPerMajor(), v);
    final double[] mine = new double[K];
    for (int k = 0; k < K; k++) {
      mine[k] = v.get(k) * own;
    }
    final double mineNet = v.parallel() * own;
    final RiskEngine risk = m.desk.risk();
    final Dv01Vector cur = full(ccy);
    final double[] hi = new double[K];
    final double[] lo = new double[K];
    double net = cur.parallel();
    double netHi = 0.0;
    double netLo = 0.0;
    for (final int id : TestMarket.ALL) {
      final Instrument inst = m.directory.byId(id);
      if (inst.currency() != ccy) {
        continue;
      }
      final double b = inst.major(risk.pendingBuy(id));
      final double s = inst.major(risk.pendingSell(id));
      m.sensitivities.pv01(id, inst.minorPerMajor(), v);
      for (int k = 0; k < K; k++) {
        hi[k] += Math.max(0.0, Math.max(v.get(k) * b, -v.get(k) * s));
        lo[k] += Math.min(0.0, Math.min(v.get(k) * b, -v.get(k) * s));
      }
      final double u = v.parallel();
      netHi += Math.max(0.0, Math.max(u * b, -u * s));
      netLo += Math.min(0.0, Math.min(u * b, -u * s));
    }
    double gross = 0.0;
    boolean grossGrows = false;
    for (int k = 0; k < K; k++) {
      final double h = cur.get(k) + hi[k];
      final double l = cur.get(k) + lo[k];
      final double lim = limits.bucketPv01(k) * (1 + REL) + 1e-6;
      if ((h > lim && mine[k] > 0.0) || (l < -lim && mine[k] < 0.0)) {
        fail(what + " bucket " + KeyRateTenors.label(k) + " " + l + ".." + h);
      }
      gross += Math.max(Math.abs(h), Math.abs(l));
      grossGrows |= Math.abs(h) >= Math.abs(l) ? mine[k] > 0.0 : mine[k] < 0.0;
    }
    final double netLim = limits.netPv01() * (1 + REL) + 1e-6;
    if ((net + netHi > netLim && mineNet > 0.0) || (net + netLo < -netLim && mineNet < 0.0)) {
      fail(what + " net " + (net + netLo) + ".." + (net + netHi));
    }
    if (gross > limits.grossPv01() * (1 + REL) + 1e-6 && grossGrows) {
      fail(what + " gross " + gross);
    }
  }

  private double unitParallel(final int id) {
    m.sensitivities.pv01(id, m.directory.byId(id).minorPerMajor(), v);
    return v.parallel();
  }

  private void fail(final String s) {
    if (violations.size() < 50) {
      violations.add(s);
    } else if (violations.size() == 50) {
      violations.add("...");
    }
  }
}
