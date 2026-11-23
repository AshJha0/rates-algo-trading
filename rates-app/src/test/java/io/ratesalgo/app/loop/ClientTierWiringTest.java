package io.ratesalgo.app.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.app.posttrade.JournalFeed;
import io.ratesalgo.app.sim.V2DeskProcess;
import io.ratesalgo.messages.sbe.MessageHeaderDecoder;
import io.ratesalgo.messages.sbe.QuoteRequestDecoder;
import io.ratesalgo.quoting.TierTable;
import io.ratesalgo.sim.MarketSpec;
import io.ratesalgo.sim.SessionSeeds;
import io.ratesalgo.sim.SimParams;
import io.ratesalgo.sim.SimRfq;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The desk's tier table against the simulator itself (SIM_V2_DESIGN.md Section 14, D12): the
 * wired v2 desk runs against the simulator v2 venues, and every RFQ the D2C venue sends reaches
 * the desk with the client id of its model tier, which the desk's tier table places in that tier.
 * The model's RFQ list is the reference: its tiers, in arrival order, are compared with the client
 * ids in the desk's inbound journal. The back-test's half is in {@code DeskSimV2Test}.
 */
class ClientTierWiringTest {

  private static final long SECOND = 1_000_000_000L;
  private static final long MINUTE = 60 * SECOND;
  private static final long SEED = SessionSeeds.seed(SessionSeeds.ACCEPTANCE, 91_007);
  private static final long LOG_ON = 120 * MINUTE;

  @TempDir Path dir;

  @Test
  void anRfqFromEachSimulatorClientReachesTheTierTheDesignStates() {
    final List<SimRfq> model =
        new ArrayList<>(SessionSeeds.session(new MarketSpec(SimParams.loadDefault()), SEED).rfqs());
    model.sort(Comparator.comparingLong(SimRfq::arrivalNanos));
    // the shortest stretch after log-on (and a logon margin) in which every tier has sent an RFQ,
    // ended in a quiet gap so that no arrival sits on the edge of the run
    final long from = LOG_ON + 5 * SECOND;
    final boolean[] seen = new boolean[3];
    int count = 0;
    long end = -1;
    for (int i = 0; i < model.size() && end < 0; i++) {
      final SimRfq r = model.get(i);
      if (r.arrivalNanos() < from) {
        continue;
      }
      if (!seen[r.tier()]) {
        seen[r.tier()] = true;
        count++;
      }
      final long next = i + 1 < model.size() ? model.get(i + 1).arrivalNanos() : Long.MAX_VALUE;
      if (count == 3 && next - r.arrivalNanos() > 20 * SECOND) {
        end = r.arrivalNanos() + 10 * SECOND;
      }
    }
    assertTrue(end > 0, "the session has every tier after log-on");
    final List<Integer> expected = new ArrayList<>();
    for (final SimRfq r : model) {
      if (r.arrivalNanos() >= LOG_ON && r.arrivalNanos() < end) {
        expected.add(DeskSettings.v2ClientId(r.tier()));
      }
    }

    final V2DeskProcess p = V2DeskProcess.start(new V2DeskProcess.Options(dir, SEED, DeskProfile.v2(), LOG_ON));
    final List<Integer> received = new ArrayList<>();
    try {
      p.step(end - LOG_ON, 20_000_000L);
      final QuoteRequestDecoder rfq = new QuoteRequestDecoder();
      final MessageHeaderDecoder header = new MessageHeaderDecoder();
      JournalFeed.feed(
          p.groupDir(), "in", "out",
          (sequence, timestampNanos, templateId, schemaVersion, buffer, offset, length) -> {
            if (templateId == QuoteRequestDecoder.TEMPLATE_ID) {
              rfq.wrapAndApplyHeader(buffer, offset, header);
              received.add(rfq.clientId());
            }
          });
    } finally {
      p.close();
    }
    // the venue sent the model's RFQs in order, each with its tier's wire id
    assertEquals(expected, received);
    // and the desk's tier table puts each id in the tier the design gives it
    final TierTable tiers = p.profile().settings().tiers();
    final boolean[] reached = new boolean[3];
    for (final int client : received) {
      final int tier = client - DeskSettings.V2_CLIENT_ID_BASE;
      assertEquals(DeskSettings.V2_TIERS.get(tier), tiers.tierOf(client).tier(), "client " + client);
      reached[tier] = true;
    }
    assertTrue(reached[0] && reached[1] && reached[2], "an RFQ from each of the three clients");
  }
}
