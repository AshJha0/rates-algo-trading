package io.ratesalgo.quoting;

import static io.ratesalgo.quoting.TestMarket.FACE_1M;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.api.CurrencyCode;
import io.ratesalgo.api.Decimal9;
import io.ratesalgo.api.Dv01Vector;
import io.ratesalgo.api.KeyRateTenors;
import io.ratesalgo.hedge.Hedger;
import io.ratesalgo.messages.sbe.KillReason;
import io.ratesalgo.messages.sbe.KillSwitchDecoder;
import io.ratesalgo.messages.sbe.OrdStatus;
import io.ratesalgo.messages.sbe.QuoteStatus;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.risk.KillState;
import io.ratesalgo.risk.RiskEngine;
import io.ratesalgo.risk.RiskLimits;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * P3 gate: ten thousand seeded random scenarios of RFQs, fills, curve shocks, gateway disconnects,
 * feed gaps, heartbeat loss, stale inputs, kill-switch triggers and resets, hedging and CLOB
 * activity. After every step the event loop's housekeeping runs and the test checks: no limit
 * breach reached the gateway or router (independent auditor); the kill switch is in the state the
 * scenario's events imply; no quote is alive beyond its time to live or priced off an input older
 * than the maximum age; nothing is quoted while killed or unhealthy; reservations equal what is
 * outstanding. Each scenario ends by comparing the incremental ladder with full revaluation.
 */
class ChaosTest {

  static final int SCENARIOS = 10_000;
  static final int STEPS = 40;
  static final long SEED = 20_261_031L;

  private final List<String> failures = new ArrayList<>();
  private long killsSeen;
  private long resets;
  private long quotes;

  @Test
  void tenThousandRandomScenariosHoldEveryInvariant() {
    final SplittableRandom root = new SplittableRandom(SEED);
    for (int s = 0; s < SCENARIOS && failures.size() < 20; s++) {
      scenario(s, root.split());
    }
    System.out.println(
        "ChaosTest: scenarios=" + SCENARIOS + " kills=" + killsSeen + " resets=" + resets + " quotes=" + quotes);
    assertEquals(List.of(), failures);
    assertTrue(killsSeen > 100, "kill switch exercised " + killsSeen);
    assertTrue(resets > 10, "kill switch reset " + resets);
    assertTrue(quotes > 20_000, "quotes " + quotes);
  }

  static RiskLimits chaosLimits() {
    return new RiskLimits()
        .defaultMaxPosition(150 * FACE_1M)
        .bucketPv01(60_000.0)
        .netPv01(100_000.0)
        .grossPv01(300_000.0)
        .dailyLoss(300_000.0)
        .quoteRatePerSecond(50.0)
        .orderRatePerSecond(20.0)
        .messageRatePerSecond(100.0)
        .maxOrderSize(100 * FACE_1M)
        .fatFingerQuantity(150 * FACE_1M)
        .fatFingerFraction(0.05)
        .priceCollarBp(15.0);
  }

  private void scenario(final int number, final SplittableRandom rnd) {
    final RiskLimits limits = chaosLimits();
    final SinkAuditor auditor = new SinkAuditor(limits);
    final TestMarket m =
        new TestMarket(
            limits,
            new QuotingConfig().maxLiveQuotes(64).duplicateWindow(256),
            Hedger.config().triggerStdDev(20_000.0),
            null,
            auditor);
    auditor.bind(m);
    final QuotingEngine engine = m.desk.quoting();
    final RiskEngine risk = m.desk.risk();
    final InputHealth health = m.desk.health();
    final ClobQuoter clob = m.desk.clob();
    final KillState[] model = {KillState.ACTIVE};
    m.control.listener(
        (templateId, b, o, l) -> {
          if (templateId == KillSwitchDecoder.TEMPLATE_ID) {
            model[0] = KillState.KILLED;
            killsSeen++;
          }
        });
    health.requireHeartbeat(1, m.clock.epochNanos());
    health.trackVenue(7);
    clob.enable(TestMarket.UST10);
    final Map<Long, Integer> parentInstrument = new HashMap<>();
    long seq = 100;
    long rfqId = 0;
    int resetRequester = 0;
    boolean sessionUp = true;
    for (int step = 0; step < STEPS; step++) {
      final long now = m.clock.epochNanos();
      final int r = rnd.nextInt(100);
      if (r < 35) {
        final int id = TestMarket.RFQ_INSTRUMENTS[rnd.nextInt(TestMarket.RFQ_INSTRUMENTS.length)];
        final int client = rnd.nextInt(10) < 3 ? 1 + rnd.nextInt(3) : 5 + rnd.nextInt(20);
        final long expiry = rnd.nextBoolean() ? 0L : now + rnd.nextLong(3_000_000_000L);
        final long id64 = rnd.nextInt(10) == 0 && rfqId > 0 ? rfqId : ++rfqId; // some duplicates
        engine.onQuoteRequest(
            7, id64, client, id, rnd.nextBoolean() ? Side.Buy : Side.Sell, (1 + rnd.nextInt(80)) * FACE_1M,
            rnd.nextInt(7), expiry, 0L);
      } else if (r < 50) {
        final List<long[]> held = new ArrayList<>();
        engine.forEachHeld((q, inst, buy, qty, until, oldest) -> held.add(new long[] {q, qty, until}));
        if (!held.isEmpty()) {
          final long[] h = held.get(rnd.nextInt(held.size()));
          final int u = rnd.nextInt(10);
          final Long px = auditor.quotePrice.get(h[0]);
          if (u < 6 && now <= h[2] && px != null) {
            engine.onQuoteResponse(h[0], QuoteStatus.Done, px, h[1], px + Decimal9.round(rnd.nextDouble(-0.05, 0.05)));
          } else if (u < 9) {
            engine.onQuoteResponse(h[0], QuoteStatus.TradedAway, 0L, 0L, 0L);
          } else {
            engine.onQuoteResponse(h[0], QuoteStatus.Expired, 0L, 0L, 0L);
          }
        }
      } else if (r < 62) {
        m.clock.advance(rnd.nextLong(2_500_000_000L));
        if (rnd.nextInt(4) != 0) {
          m.refreshAll(m.clock.epochNanos());
          health.onHeartbeat(1, m.clock.epochNanos());
        }
      } else if (r < 70) {
        m.shift(rnd.nextInt(4) == 0 ? CurrencyCode.EUR : CurrencyCode.USD, rnd.nextDouble(-15.0, 15.0));
      } else if (r < 74) {
        sessionUp = !sessionUp;
        health.onSession(7, sessionUp);
      } else if (r < 78) {
        if (rnd.nextBoolean()) {
          seq += 1 + (rnd.nextInt(3) == 0 ? 5 : 0);
          health.onBookSequence(7, seq);
        } else {
          health.onFeedRecovered(7);
        }
      } else if (r < 83) {
        health.onHeartbeat(1, now);
      } else if (r < 86) {
        m.desk.killSwitch().trigger(KillReason.Manual, 0, 1 + rnd.nextInt(3));
        model[0] = KillState.KILLED;
      } else if (r < 90) {
        if (model[0] == KillState.KILLED) {
          resetRequester = 1 + rnd.nextInt(3);
          m.desk.killSwitch().requestReset(resetRequester);
          model[0] = KillState.RESET_PENDING;
        } else if (model[0] == KillState.RESET_PENDING) {
          final int approver = 1 + rnd.nextInt(3);
          final boolean healthy = health.healthy(now);
          if (m.desk.killSwitch().approveReset(approver, healthy)) {
            resets++;
          }
          if (approver != resetRequester && healthy) {
            model[0] = KillState.ACTIVE;
          }
        }
      } else if (r < 96) {
        for (final CurrencyCode c : new CurrencyCode[] {CurrencyCode.USD, CurrencyCode.EUR}) {
          final Hedger h = m.desk.hedger(c);
          h.onTimer(now);
          for (final long[] p : auditor.parents) {
            parentInstrument.put(p[0], (int) p[1]);
          }
          for (int p = h.workingParents() - 1; p >= 0; p--) {
            final long pid = h.workingParentId(p);
            final int u = rnd.nextInt(3);
            if (u == 0) {
              h.onHedgeFill(pid, m.fair.fairMid(parentInstrument.get(pid)), Math.abs(h.remaining(pid)));
            } else if (u == 1) {
              h.onHedgeFill(pid, m.fair.fairMid(parentInstrument.get(pid)), Math.max(1L, Math.abs(h.remaining(pid)) / 2));
            } else {
              h.onParentDone(pid);
            }
          }
        }
      } else {
        clob.update(TestMarket.UST10, now);
        for (final boolean bid : new boolean[] {true, false}) {
          final long id = clob.restingOrder(TestMarket.UST10, bid);
          if (id != 0L) {
            if (clob.cancelPending(TestMarket.UST10, bid) || rnd.nextBoolean()) {
              clob.onExecution(id, 0L, 0L, OrdStatus.Cancelled);
            } else {
              clob.onExecution(id, FACE_1M, clob.restingPrice(TestMarket.UST10, bid), OrdStatus.PartiallyFilled);
            }
          }
        }
      }
      final long t = m.clock.epochNanos();
      engine.poll(t);
      check(number, step, m, model[0], parentInstrument, auditor);
    }
    quotes += auditor.quotesSeen;
    for (final String v : auditor.violations()) {
      failures.add("scenario " + number + ": " + v);
    }
    for (final CurrencyCode c : new CurrencyCode[] {CurrencyCode.USD, CurrencyCode.EUR}) {
      final Dv01Vector inc = new Dv01Vector(c);
      final Dv01Vector full = new Dv01Vector(c);
      risk.ladder(c, inc);
      risk.fullLadder(c, full);
      double notional = 1.0;
      for (final int id : TestMarket.ALL) {
        if (m.directory.byId(id).currency() == c) {
          notional += Math.abs(m.directory.byId(id).major(risk.position(id)));
        }
      }
      for (int k = 0; k < KeyRateTenors.COUNT; k++) {
        if (Math.abs(inc.get(k) - full.get(k)) > 1e-6 * notional) {
          failures.add("scenario " + number + ": ladder " + c + " pillar " + k + " " + inc.get(k) + " vs " + full.get(k));
        }
      }
    }
  }

  private void check(
      final int scenario,
      final int step,
      final TestMarket m,
      final KillState expected,
      final Map<Long, Integer> parentInstrument,
      final SinkAuditor auditor) {
    final String at = "scenario " + scenario + " step " + step + ": ";
    final long now = m.clock.epochNanos();
    final QuotingEngine engine = m.desk.quoting();
    final RiskEngine risk = m.desk.risk();
    final long maxAge = engine.config().maxInputAgeNanos();
    engine.forEachLive(
        (q, inst, buy, qty, until, oldest) -> {
          if (until <= now) {
            failures.add(at + "quote " + q + " alive past its time to live");
          }
          if (now - oldest > maxAge) {
            failures.add(at + "quote " + q + " alive on a stale input");
          }
        });
    final KillState state = m.desk.killSwitch().state();
    if (state != expected) {
      failures.add(at + "kill switch " + state + ", expected " + expected);
    }
    if (state != KillState.ACTIVE) {
      if (engine.liveQuotes() != 0) {
        failures.add(at + "live quotes while " + state);
      }
      // a daily-loss kill keeps the hedges flattening (PNL_AUDIT item 14); any other kill stops them
      if (!m.desk.risk().flattenOnly()
          && m.desk.hedger(CurrencyCode.USD).workingParents() + m.desk.hedger(CurrencyCode.EUR).workingParents() != 0) {
        failures.add(at + "hedge parents working while " + state);
      }
      for (final boolean bid : new boolean[] {true, false}) {
        if (m.desk.clob().restingOrder(TestMarket.UST10, bid) != 0 && !m.desk.clob().cancelPending(TestMarket.UST10, bid)) {
          failures.add(at + "resting order without a cancel while " + state);
        }
      }
    }
    if (!m.desk.health().healthy(now) && engine.liveQuotes() != 0) {
      failures.add(at + "live quotes while inputs unhealthy");
    }
    final Map<Integer, long[]> expectedReserved = new HashMap<>();
    engine.forEachHeld(
        (q, inst, buy, qty, until, oldest) -> {
          final long[] r = expectedReserved.computeIfAbsent(inst, k -> new long[2]);
          r[buy ? 0 : 1] += qty;
        });
    final long[] clob = expectedReserved.computeIfAbsent(TestMarket.UST10, k -> new long[2]);
    clob[0] += m.desk.clob().reserved(TestMarket.UST10, true);
    clob[1] += m.desk.clob().reserved(TestMarket.UST10, false);
    for (final CurrencyCode c : new CurrencyCode[] {CurrencyCode.USD, CurrencyCode.EUR}) {
      final Hedger h = m.desk.hedger(c);
      for (int p = 0; p < h.workingParents(); p++) {
        final long pid = h.workingParentId(p);
        Integer inst = parentInstrument.get(pid);
        if (inst == null) {
          for (final long[] a : auditor.parents) {
            if (a[0] == pid) {
              inst = (int) a[1];
              parentInstrument.put(pid, inst);
            }
          }
        }
        final long rem = h.remaining(pid);
        final long[] r = expectedReserved.computeIfAbsent(inst, k -> new long[2]);
        r[rem > 0 ? 0 : 1] += Math.abs(rem);
      }
    }
    for (final int id : TestMarket.ALL) {
      final long[] e = expectedReserved.getOrDefault(id, new long[2]);
      if (risk.pendingBuy(id) != e[0] || risk.pendingSell(id) != e[1]) {
        failures.add(
            at + "reservation on " + id + " " + risk.pendingBuy(id) + "/" + risk.pendingSell(id) + " expected " + e[0] + "/" + e[1]);
      }
    }
  }
}
