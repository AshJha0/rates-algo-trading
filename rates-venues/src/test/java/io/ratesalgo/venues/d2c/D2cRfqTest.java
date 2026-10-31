package io.ratesalgo.venues.d2c;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.fix.codec.FixParser;
import io.ratesalgo.fix.codec.FixTags;
import io.ratesalgo.fix.codec.MsgType;
import io.ratesalgo.fix.session.FixSession;
import io.ratesalgo.refdata.RefData;
import io.ratesalgo.venues.sim.EngineSession;
import io.ratesalgo.venues.sim.Instrument;
import io.ratesalgo.venues.sim.Instruments;
import io.ratesalgo.venues.sim.Px;
import io.ratesalgo.venues.sim.VenueHarness;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class D2cRfqTest {

  static final long T0 = 1_793_629_800_000_000_000L; // 2026-11-02T13:30:00Z, 08:30 New York

  @Test
  void oneRfqHitWithCover() {
    final ManualClock clock = new ManualClock(T0);
    final List<Instrument> bonds = Instruments.treasuries(RefData.loadDefault(), LocalDate.of(2026, 11, 2));
    final D2cRfqSimulator sim = new D2cRfqSimulator(D2cConfig.defaults(), bonds, List.of("RALGO"), clock, 7);
    final List<String> inbound = new ArrayList<>();
    try (VenueHarness h = new VenueHarness(sim, clock);
        EngineSession e = h.connect("RALGO", (FixSession s, FixParser m, boolean d) -> inbound.add(m.toString()))) {
      final String isin = bonds.get(0).securityId();
      final long rfq =
          sim.injectRfq(new RfqSpec(isin, true, 10_000_000, 4, List.of(Px.price("99.53"), Px.price("99.52"), Px.price("99.55")), 2003, ClientDecision.BEST, false));
      e.quiesce();
      assertTrue(inbound.get(0).contains("|35=R|"), inbound.get(0));
      e.session().begin(MsgType.QUOTE).put(FixTags.QUOTE_REQ_ID, rfq).put(FixTags.QUOTE_ID, 1)
          .putText(55, isin).putText(48, isin).putChar(22, (byte) '4')
          .putDecimal(FixTags.BID_PX, Px.price("99.49"), 9).putDecimal(FixTags.OFFER_PX, Px.price("99.515"), 9)
          .put(FixTags.BID_SIZE, 10_000_000).put(FixTags.OFFER_SIZE, 10_000_000);
      e.session().send();
      e.quiesce();
      sim.decide(rfq);
      e.quiesce();
      assertEquals(4, inbound.size(), String.join("\n", inbound));
      assertTrue(inbound.get(1).contains("|35=AI|") && inbound.get(1).contains("|297=0|"), inbound.get(1));
      assertTrue(inbound.get(2).contains("|35=AJ|") && inbound.get(2).contains("|694=1|"), inbound.get(2));
      assertTrue(inbound.get(2).contains("|44=99.515|") && inbound.get(2).contains("|9502=99.52|"), inbound.get(2));
      assertTrue(inbound.get(3).contains("|35=8|") && inbound.get(3).contains("|150=F|"), inbound.get(3));
      assertEquals(0, e.session().stats().sequenceErrors());
    }
  }
}
