package io.ratesalgo.venues.sim;

import io.ratesalgo.refdata.Bond;
import io.ratesalgo.refdata.RefData;
import io.ratesalgo.refdata.SwapTemplate;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Builds the simulators' instrument lists from reference data. */
public final class Instruments {

  private Instruments() {}

  /** On-the-run Treasuries on a date, mids at 100 minus a small tenor-dependent discount. */
  public static List<Instrument> treasuries(final RefData ref, final LocalDate date) {
    final List<Instrument> out = new ArrayList<>();
    long step = 0;
    for (final String tenor : List.of("2Y", "5Y", "10Y", "30Y")) {
      final var bond = ref.securities().onTheRun("UST", tenor, date);
      if (bond.isPresent()) {
        out.add(bond(bond.get(), 99_500_000_000L - step));
        step += 750_000_000L;
      }
    }
    return out;
  }

  /** A bond with a given starting mid. */
  public static Instrument bond(final Bond b, final long mid) {
    return new Instrument(b.instrumentId(), b.isin(), "bond", mid, 7_812_500L, b.currency(), false, 0);
  }

  /** USD SOFR swap templates, mids at 3.75 percent plus a small tenor step. */
  public static List<Instrument> sofrSwaps(final RefData ref) {
    final List<Instrument> out = new ArrayList<>();
    long rate = 3_600_000_000L;
    for (final SwapTemplate s : ref.securities().swaps()) {
      if ("USD-SOFR".equals(s.market())) {
        out.add(
            new Instrument(
                s.instrumentId(),
                "USD-SOFR-" + s.tenor(),
                "swap",
                rate,
                100_000L,
                s.currency(),
                s.madeAvailableToTrade(),
                s.blockThreshold()));
        rate += 50_000_000L;
      }
    }
    return out;
  }
}
