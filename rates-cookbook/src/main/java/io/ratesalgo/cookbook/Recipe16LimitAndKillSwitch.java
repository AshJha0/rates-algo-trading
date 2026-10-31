package io.ratesalgo.cookbook;

import io.ratesalgo.api.CurrencyCode;
import io.ratesalgo.api.Decimal9;
import io.ratesalgo.api.InMemoryFairValueSource;
import io.ratesalgo.api.InMemoryInstrumentDirectory;
import io.ratesalgo.api.Instrument;
import io.ratesalgo.api.ParametricSensitivityProvider;
import io.ratesalgo.api.RecordingOutboundSink;
import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.messages.sbe.KillReason;
import io.ratesalgo.messages.sbe.KillSwitchDecoder;
import io.ratesalgo.messages.sbe.MessageHeaderDecoder;
import io.ratesalgo.messages.sbe.RiskLimitBreachDecoder;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.risk.KillSwitchController;
import io.ratesalgo.risk.RiskEngine;
import io.ratesalgo.risk.RiskLimits;
import org.agrona.DirectBuffer;

/** COOKBOOK.md recipe 16: set a risk limit, see a breach, and exercise the kill switch. */
public final class Recipe16LimitAndKillSwitch {

  private Recipe16LimitAndKillSwitch() {}

  public static void main(final String[] args) {
    final ManualClock clock = new ManualClock();
    final InMemoryInstrumentDirectory instruments = new InMemoryInstrumentDirectory();
    instruments.add(Instrument.bond(103, "UST10", CurrencyCode.USD, 10.0, 2_500_000_000L));
    final ParametricSensitivityProvider pv01 = new ParametricSensitivityProvider(instruments);
    pv01.defineAtTenor(103, -8.2e-4, 0.0);
    final InMemoryFairValueSource fair = new InMemoryFairValueSource();
    fair.set(103, Decimal9.round(98.25), clock.epochNanos());

    final RecordingOutboundSink control = new RecordingOutboundSink();
    control.listener((templateId, buffer, offset, length) -> print(templateId, buffer, offset));
    final KillSwitchController killSwitch = new KillSwitchController(control, clock);
    killSwitch.register((reason, nanos) -> System.out.println("listener: pull quotes, cancel orders (" + reason + ")"));
    final RiskLimits limits = new RiskLimits().bucketPv01(100_000.0).priceCollarBp(5.0);
    final RiskEngine risk = new RiskEngine(instruments, pv01, fair, limits, killSwitch, control, clock);

    final long px = fair.fairMid(103);
    System.out.println("buy 100m: " + risk.checkOrder(103, Side.Buy, 10_000_000_000L, px, 41));
    System.out.println("buy 150m: " + risk.checkOrder(103, Side.Buy, 15_000_000_000L, px, 42));
    System.out.println("buy 1m 6bp through fair: " + risk.checkOrder(103, Side.Buy, 100_000_000L, px + Decimal9.round(6 * 0.082), 43));

    killSwitch.trigger(KillReason.Manual, 0, 7);
    System.out.println("state " + killSwitch.state() + ", buy 1m: " + risk.checkOrder(103, Side.Buy, 100_000_000L, px, 44));
    killSwitch.requestReset(7);
    System.out.println("same person approves: " + killSwitch.approveReset(7, true) + ", state " + killSwitch.state());
    System.out.println("second person approves: " + killSwitch.approveReset(8, true) + ", state " + killSwitch.state());
    System.out.println("buy 1m: " + risk.checkOrder(103, Side.Buy, 100_000_000L, px, 45));
  }

  private static void print(final int templateId, final DirectBuffer buffer, final int offset) {
    final int body = offset + MessageHeaderDecoder.ENCODED_LENGTH;
    if (templateId == RiskLimitBreachDecoder.TEMPLATE_ID) {
      final RiskLimitBreachDecoder d = new RiskLimitBreachDecoder().wrap(buffer, body, RiskLimitBreachDecoder.BLOCK_LENGTH, 0);
      System.out.println(
          "  RiskLimitBreach "
              + d.limitKind()
              + " scope="
              + d.scopeId()
              + " limit="
              + Decimal9.toDouble(d.limitValue().mantissa())
              + " observed="
              + Decimal9.toDouble(d.observedValue().mantissa())
              + " seq="
              + d.rejectedMessageSeqNo());
    } else if (templateId == KillSwitchDecoder.TEMPLATE_ID) {
      final KillSwitchDecoder d = new KillSwitchDecoder().wrap(buffer, body, KillSwitchDecoder.BLOCK_LENGTH, 0);
      System.out.println("  KillSwitch " + d.reason() + " requestedBy=" + d.requestedBy());
    }
  }
}
