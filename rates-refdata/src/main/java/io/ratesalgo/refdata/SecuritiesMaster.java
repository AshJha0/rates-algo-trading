package io.ratesalgo.refdata;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bonds, swap templates and futures contracts by instrument id and by identifier, with the
 * on-the-run rule.
 *
 * <p>On-the-run rule (API_SPEC.md Section 11): for a market and tenor, the on-the-run bond on date
 * {@code d} is the bond with the latest {@code onTheRunFromDate} that is on or before {@code d}. A
 * bond announced but not yet on the run is when-issued from its announcement date. So the
 * on-the-run rolls on the new issue's issue date, and when-issued trading starts at announcement.
 */
public final class SecuritiesMaster {

  private final Map<Integer, Bond> bondsById = new HashMap<>();
  private final Map<String, Bond> bondsByIsin = new HashMap<>();
  private final Map<String, List<Bond>> bondsByMarketTenor = new HashMap<>();
  private final Map<Integer, SwapTemplate> swapsById = new HashMap<>();
  private final Map<Integer, FuturesContract> futuresById = new HashMap<>();
  private final Map<String, FuturesContract> futuresBySymbol = new HashMap<>();

  SecuritiesMaster(
      final List<Bond> bonds, final List<SwapTemplate> swaps, final List<FuturesContract> futures) {
    for (final Bond b : bonds) {
      putUnique(b.instrumentId(), b.isin());
      bondsById.put(b.instrumentId(), b);
      bondsByIsin.put(b.isin(), b);
      bondsByMarketTenor.computeIfAbsent(b.market() + "/" + b.tenor(), k -> new ArrayList<>()).add(b);
    }
    for (final List<Bond> list : bondsByMarketTenor.values()) {
      list.sort(Comparator.comparing(Bond::onTheRunFromDate));
    }
    for (final SwapTemplate s : swaps) {
      putUnique(s.instrumentId(), s.market() + " " + s.tenor());
      swapsById.put(s.instrumentId(), s);
    }
    for (final FuturesContract f : futures) {
      putUnique(f.instrumentId(), f.symbol());
      futuresById.put(f.instrumentId(), f);
      futuresBySymbol.put(f.symbol(), f);
    }
  }

  private void putUnique(final int id, final String what) {
    if (id <= 0) {
      throw new RefDataException("instrument id must be positive: " + id + " (" + what + ")");
    }
    if (bondsById.containsKey(id) || swapsById.containsKey(id) || futuresById.containsKey(id)) {
      throw new RefDataException("duplicate instrument id " + id + " (" + what + ")");
    }
  }

  /** Bond by instrument id. */
  public Optional<Bond> bond(final int instrumentId) {
    return Optional.ofNullable(bondsById.get(instrumentId));
  }

  /** Bond by ISIN. */
  public Optional<Bond> bondByIsin(final String isin) {
    return Optional.ofNullable(bondsByIsin.get(isin));
  }

  /** Swap template by instrument id. */
  public Optional<SwapTemplate> swap(final int instrumentId) {
    return Optional.ofNullable(swapsById.get(instrumentId));
  }

  /** Futures contract by instrument id. */
  public Optional<FuturesContract> future(final int instrumentId) {
    return Optional.ofNullable(futuresById.get(instrumentId));
  }

  /** Futures contract by exchange symbol. */
  public Optional<FuturesContract> futureBySymbol(final String symbol) {
    return Optional.ofNullable(futuresBySymbol.get(symbol));
  }

  /** All bonds, ordered by instrument id. */
  public List<Bond> bonds() {
    final List<Bond> all = new ArrayList<>(bondsById.values());
    all.sort(Comparator.comparingInt(Bond::instrumentId));
    return Collections.unmodifiableList(all);
  }

  /** All swap templates, ordered by instrument id. */
  public List<SwapTemplate> swaps() {
    final List<SwapTemplate> all = new ArrayList<>(swapsById.values());
    all.sort(Comparator.comparingInt(SwapTemplate::instrumentId));
    return Collections.unmodifiableList(all);
  }

  /** All futures contracts, ordered by instrument id. */
  public List<FuturesContract> futures() {
    final List<FuturesContract> all = new ArrayList<>(futuresById.values());
    all.sort(Comparator.comparingInt(FuturesContract::instrumentId));
    return Collections.unmodifiableList(all);
  }

  /** The on-the-run bond of a market and tenor on a date, if any has been issued by then. */
  public Optional<Bond> onTheRun(final String market, final String tenor, final LocalDate date) {
    final List<Bond> list = bondsByMarketTenor.get(market + "/" + tenor);
    if (list == null) {
      return Optional.empty();
    }
    Bond current = null;
    for (final Bond b : list) {
      if (!b.onTheRunFromDate().isAfter(date) && date.isBefore(b.maturity())) {
        current = b;
      }
    }
    return Optional.ofNullable(current);
  }

  /** Life-cycle state of a bond on a date. */
  public BondStatus status(final Bond bond, final LocalDate date) {
    if (!date.isBefore(bond.maturity())) {
      return BondStatus.MATURED;
    }
    if (date.isBefore(bond.announcementDate())) {
      return BondStatus.NOT_ANNOUNCED;
    }
    if (date.isBefore(bond.onTheRunFromDate())) {
      return BondStatus.WHEN_ISSUED;
    }
    final Optional<Bond> otr = onTheRun(bond.market(), bond.tenor(), date);
    return otr.isPresent() && otr.get().instrumentId() == bond.instrumentId()
        ? BondStatus.ON_THE_RUN
        : BondStatus.OFF_THE_RUN;
  }

  /** True if a trade in the bond may be agreed on the date (when-issued, on or off the run). */
  public boolean isTradable(final Bond bond, final LocalDate date) {
    final BondStatus s = status(bond, date);
    return s == BondStatus.WHEN_ISSUED || s == BondStatus.ON_THE_RUN || s == BondStatus.OFF_THE_RUN;
  }
}
