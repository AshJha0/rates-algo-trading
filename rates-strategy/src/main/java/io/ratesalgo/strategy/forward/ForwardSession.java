package io.ratesalgo.strategy.forward;

import io.ratesalgo.backtest.BacktestConfig;
import io.ratesalgo.backtest.BacktestResult;
import io.ratesalgo.backtest.BacktestRunner;
import io.ratesalgo.backtest.FillRecord;
import io.ratesalgo.backtest.Instrument;
import io.ratesalgo.backtest.InstrumentSet;
import io.ratesalgo.backtest.model.BandPoint;
import io.ratesalgo.backtest.model.Presets;
import io.ratesalgo.backtest.pnl.CarryModel;
import io.ratesalgo.backtest.synth.SyntheticDay;
import io.ratesalgo.backtest.util.Yaml;
import io.ratesalgo.strategy.experiment.BandScenario;
import io.ratesalgo.strategy.experiment.DeskFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Runs one forward session: the adopted desk ({@link DeskFactory#deskDefault()}) quotes a day of
 * synthetic client RFQs priced off the committed mids of the previous close.
 *
 * <p>Plain idea: take the synthetic day generator and the back-tester the experiments use, pin
 * the synthetic market to yesterday's real close and switch its intraday moves off, so that the
 * only things made up are the clients, their RFQs and the fills. The book that results is marked
 * afterwards at the real close of the session day by {@link ForwardCycle}.
 *
 * <p>Formally, with {@code m_i} the committed mid of instrument {@code i}: the generator's
 * {@code rate_<tenor>_pct} are the swap mids, {@code future_price_0} the future's mid, {@code
 * sofr_fixing_pct} the committed SOFR, and every intraday volatility and event jump is zero, so
 * every curve input of the session carries {@code m_i} (bond prices stay at the generator's 100,
 * a level only; the close mark uses price changes). Bonds are funded at the committed SOFR; carry
 * is the declared bond coupons plus swap carry {@code (position / 100) * (SOFR - rate) / 100 *
 * dt / 360 days} (a long swap gains when the rate rises, so it pays fixed and receives SOFR).
 */
public final class ForwardSession {

  /** What a session produced. */
  public record Result(BacktestResult backtest, Map<Integer, Long> endPositions) {
    public Result {
      endPositions = Map.copyOf(endPositions);
    }
  }

  private static final String MARKET = "usd_synthetic_market";
  private static final String[] ZERO_PARAMS = {
    "level_vol_bp_per_sqrt_min", "idio_vol_bp_per_sqrt_min", "basis_vol_points_per_sqrt_min",
    "event_0_jump_bp", "event_1_jump_bp", "event_2_jump_bp", "event_3_jump_bp"
  };
  private static final Map<Integer, String> RATE_PARAMS =
      Map.of(InstrumentSet.SOFR_2Y, "rate_2y_pct", InstrumentSet.SOFR_5Y, "rate_5y_pct",
          InstrumentSet.SOFR_10Y, "rate_10y_pct", InstrumentSet.SOFR_30Y, "rate_30y_pct");

  private final ForwardSettings settings;

  public ForwardSession(final ForwardSettings settings) {
    this.settings = settings;
  }

  /** Presets with the market pinned to {@code mids} and, if configured, no intraday moves. */
  Presets presets(final Map<Integer, Double> mids, final double sofrPct) {
    final Path file = settings.presetsFile();
    final Map<String, Object> root = Yaml.loadMap(file);
    final Map<String, Object> experiment = Yaml.loadMap(settings.experimentConfig());
    for (final Object o : Yaml.asList(Yaml.require(root, "presets", file.toString()), "presets")) {
      final Map<String, Object> p = Yaml.asMap(o, "preset");
      if (!MARKET.equals(p.get("name"))) {
        continue;
      }
      final Map<String, Object> params = Yaml.map(p, "parameters", MARKET);
      for (final Map.Entry<Integer, String> e : RATE_PARAMS.entrySet()) {
        Yaml.map(params, e.getValue(), MARKET).put("value", mids.get(e.getKey()));
      }
      Yaml.map(params, "future_price_0", MARKET).put("value", mids.get(InstrumentSet.TY_FUTURE));
      Yaml.map(params, "sofr_fixing_pct", MARKET).put("value", sofrPct);
      if (settings.flatIntraday()) {
        for (final String k : ZERO_PARAMS) {
          Yaml.map(params, k, MARKET).put("value", 0.0);
        }
      }
    }
    return BandScenario.presets(
        root, file + " pinned to the forward close", Yaml.map(experiment, "band", "experiment config"),
        settings.bandPoint());
  }

  /** The default instruments with bonds funded at {@code sofrPct}. */
  static InstrumentSet instruments(final double sofrPct) {
    final List<Instrument> list = new ArrayList<>();
    for (final Instrument i : InstrumentSet.usdDefault().all()) {
      list.add(i.kind() == Instrument.Kind.BOND
          ? new Instrument(i.id(), i.name(), i.kind(), i.tenorYears(), i.valuePerUnit(), i.priceChangePerBp(),
              i.tickMantissa(), i.lotSize(), sofrPct / 100.0)
          : i);
    }
    return new InstrumentSet(list);
  }

  /** Bond coupons from the factory plus swap carry at {@code sofrPct}. */
  static CarryModel carry(final CarryModel bonds, final double sofrPct) {
    return (instrument, position, mid, fromNanos, toNanos) -> {
      if (instrument.kind() == Instrument.Kind.SWAP) {
        if (toNanos <= fromNanos) {
          return 0.0;
        }
        return (position / 100.0) * (sofrPct - mid) / 100.0 * ((toNanos - fromNanos) / (360.0 * 86_400e9));
      }
      return bonds.carry(instrument, position, mid, fromNanos, toNanos);
    };
  }

  /** Runs the session of {@code session} with {@code seed}, using {@code workDir} for the journal. */
  public Result run(
      final LocalDate session, final long seed, final Map<Integer, Double> mids, final double sofrPct,
      final Path workDir) {
    final Presets presets = presets(mids, sofrPct);
    final InstrumentSet instruments = instruments(sofrPct);
    final DeskFactory factory = new DeskFactory(Yaml.loadMap(settings.experimentConfig()), presets, instruments);
    try {
      Files.createDirectories(workDir);
      final Path journal = workDir.resolve("session-" + session + ".raj");
      Files.deleteIfExists(journal);
      new SyntheticDay(new SyntheticDay.Params(presets.get(MARKET), presets.get("usd_rfq_flow"), instruments))
          .generate(session, seed, journal, null);
      final BandPoint point = settings.bandPoint();
      final BacktestConfig c = BacktestConfig.fromPresets(presets, List.of(journal), seed, point, null);
      final BacktestConfig config =
          new BacktestConfig(c.journals(), instruments, c.seed(), c.bandPoint(), c.outcomes(), c.strategyHitRate(),
              c.hedgeCost(), c.clob(), carry(factory.carry(), sofrPct), c.calendar(), c.clientsPerTier(),
              c.quoteLatencyNanos(), c.clientDecisionNanos(), c.orderLatencyNanos(), c.markoutHorizonsNanos(), null);
      final BacktestResult result = new BacktestRunner(config).run(factory.deskDefault());
      Files.deleteIfExists(journal);
      final Map<Integer, Long> end = new TreeMap<>();
      for (final Instrument i : instruments.all()) {
        end.put(i.id(), 0L);
      }
      for (final FillRecord f : result.fills()) {
        end.merge(f.instrumentId(), f.signedQuantity(), Long::sum);
      }
      return new Result(result, end);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
