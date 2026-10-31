package io.ratesalgo.backtest.synth;

import io.ratesalgo.backtest.Instrument;
import io.ratesalgo.backtest.InstrumentSet;
import io.ratesalgo.backtest.model.Preset;
import io.ratesalgo.backtest.model.Presets;
import io.ratesalgo.backtest.util.Prices;
import io.ratesalgo.backtest.util.SplitMix64;
import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.journal.JournalFormat;
import io.ratesalgo.journal.JournalWriter;
import io.ratesalgo.messages.sbe.BookAction;
import io.ratesalgo.messages.sbe.BookUpdateEncoder;
import io.ratesalgo.messages.sbe.CurveInputEncoder;
import io.ratesalgo.messages.sbe.CurveInputKind;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.messages.sbe.QuoteRequestEncoder;
import io.ratesalgo.messages.sbe.Side;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Generates one synthetic trading session as a journal of SBE messages: QuoteRequests,
 * BookUpdates and CurveInputs (API_SPEC.md Section 16.5).
 *
 * <p>Plain idea: simulate a one-second grid of rates driven by a common level factor with jumps at
 * scheduled releases, derive swap mids, Treasury prices and the note future (with a mean-reverting
 * futures-cash basis), then lay RFQs on top with an intensity that clusters around fixings and
 * releases. Some clients are informed: their side is the direction the price moves over the next
 * few minutes. Everything is drawn from one {@link SplitMix64} seeded by the caller, so a seed and
 * a date give the same journal byte for byte.
 *
 * <p>Formal statement of the market (time in seconds, s):
 *
 * <pre>
 *   L(s+1) = L(s) + volL * Z + sum_k [s == event_k] * jump_k * Z'          common level, bp
 *   r_j(s) = r_j(0) + beta_j * L(s) + e_j(s),  e_j a random walk with volIdio   swap and bond yields
 *   P_bond(s) = 100 + pcpb_bond * (y(s) - y(0))                           pcpb = price change per bp
 *   B(s+1) = B(s) - kappa * B(s) + volB * Z''                              futures-cash basis, points
 *   F(s) = F(0) + pcpb_fut * (r_10y(s) - r_10y(0)) + B(s)
 * </pre>
 *
 * <p>RFQ arrivals per second are Poisson with rate {@code share * lambda(m) / 60}, where
 * {@code lambda(m) = exp(b0 + sum_k a_k exp(-(m - m_k)^2 / (2 w^2)))} per minute at minute
 * {@code m} after midnight New York time, fitted from DTCC SDR public dissemination by
 * {@code ratesresearch.flow} (preset {@code usd_rfq_flow}). Sizes are lognormal in millions,
 * capped. Market and client parameters are declared assumptions in preset
 * {@code usd_synthetic_market}.
 */
public final class SyntheticDay {

  /** Venue id of the D2C RFQ venue in the synthetic world. */
  public static final int RFQ_VENUE = 1;

  /** Venue id of the CLOB for Treasuries and futures in the synthetic world. */
  public static final int CLOB_VENUE = 2;

  /** Instrument id used for the SOFR fixing curve input (not tradable). */
  public static final int SOFR_INDEX = 100;

  private static final int[] SWAPS = {
    InstrumentSet.SOFR_2Y, InstrumentSet.SOFR_5Y, InstrumentSet.SOFR_10Y, InstrumentSet.SOFR_30Y
  };
  private static final String[] SWAP_TENORS = {"2Y", "5Y", "10Y", "30Y"};
  private static final String[] SWAP_KEYS = {"2y", "5y", "10y", "30y"};
  private static final int[] BOOKS = {InstrumentSet.UST_2Y, InstrumentSet.UST_10Y, InstrumentSet.TY_FUTURE};
  private static final int[] RFQ_PRODUCTS = {
    InstrumentSet.SOFR_2Y,
    InstrumentSet.SOFR_5Y,
    InstrumentSet.SOFR_10Y,
    InstrumentSet.SOFR_30Y,
    InstrumentSet.UST_2Y,
    InstrumentSet.UST_10Y
  };

  /** What a generated day contains. */
  public record Summary(
      LocalDate date, long seed, int rfqs, int informedRfqs, int bookUpdates, int curveInputs, long frames, long chainHash) {}

  /** The parameters of a synthetic day, read from presets. */
  public record Params(Preset market, Preset flow, InstrumentSet instruments) {
    public static Params fromPresets(final Presets presets) {
      return new Params(
          presets.get("usd_synthetic_market"), presets.get("usd_rfq_flow"), InstrumentSet.usdDefault());
    }
  }

  private enum Type {
    CURVE,
    BOOK,
    RFQ
  }

  private record Event(
      long time,
      long order,
      Type type,
      int instrumentId,
      long price,
      long quantity,
      boolean buy,
      int clientId,
      int dealers,
      boolean informed,
      CurveInputKind kind,
      String tenor) {}

  private final Params params;

  public SyntheticDay(final Params params) {
    this.params = params;
  }

  /**
   * Generates the session of {@code date} with {@code seed} into a new journal file, and writes
   * the informed flag of each RFQ to {@code truthCsv} if it is not null.
   */
  public Summary generate(final LocalDate date, final long seed, final Path journal, final Path truthCsv) {
    final Preset m = params.market();
    final Preset f = params.flow();
    final InstrumentSet instruments = params.instruments();
    final SplitMix64 rng = new SplitMix64(seed);

    final int utcOffset = (int) f.value("utc_offset_hours");
    final long midnight = date.atStartOfDay().toEpochSecond(ZoneOffset.ofHours(utcOffset)) * 1_000_000_000L;
    final int openMin = (int) m.value("session_open_minute_et");
    final int closeMin = (int) m.value("session_close_minute_et");
    final int seconds = (closeMin - openMin) * 60;
    final long open = midnight + openMin * 60_000_000_000L;

    // scheduled events and their jumps
    final int eventCount = (int) f.value("event_count");
    final int[] eventSecond = new int[eventCount];
    final double[] eventMinute = new double[eventCount];
    final double[] eventAmp = new double[eventCount];
    final double[] eventJump = new double[eventCount];
    for (int k = 0; k < eventCount; k++) {
      eventMinute[k] = f.value("event_" + k + "_minute_et");
      eventAmp[k] = f.value("event_" + k + "_log_amplitude");
      eventJump[k] = m.value("event_" + k + "_jump_bp", 0.0);
      eventSecond[k] = (int) Math.round((eventMinute[k] - openMin) * 60);
    }
    final double width = f.value("event_bump_width_minutes");

    // market grid
    final double volLevel = m.value("level_vol_bp_per_sqrt_min") / Math.sqrt(60.0);
    final double volIdio = m.value("idio_vol_bp_per_sqrt_min") / Math.sqrt(60.0);
    final double kappa = m.value("basis_mean_reversion_per_min") / 60.0;
    final double volBasis = m.value("basis_vol_points_per_sqrt_min") / Math.sqrt(60.0);
    final double[] level = new double[seconds + 1];
    final double[][] idio = new double[6][seconds + 1];
    final double[] basis = new double[seconds + 1];
    for (int s = 1; s <= seconds; s++) {
      double dl = volLevel * rng.nextGaussian();
      for (int k = 0; k < eventCount; k++) {
        if (eventSecond[k] == s && eventJump[k] > 0) {
          dl += eventJump[k] * rng.nextGaussian();
        }
      }
      level[s] = level[s - 1] + dl;
      for (int j = 0; j < 6; j++) {
        idio[j][s] = idio[j][s - 1] + volIdio * rng.nextGaussian();
      }
      basis[s] = basis[s - 1] - kappa * basis[s - 1] + volBasis * rng.nextGaussian();
    }
    // quoted values per instrument on the grid: index 0..3 swaps, 4..5 bonds, 6 future
    final double[][] quoted = new double[7][seconds + 1];
    final double[] beta = new double[4];
    for (int j = 0; j < 4; j++) {
      beta[j] = m.value("loading_" + SWAP_KEYS[j]);
      final double r0 = m.value("rate_" + SWAP_KEYS[j] + "_pct");
      for (int s = 0; s <= seconds; s++) {
        quoted[j][s] = r0 + (beta[j] * level[s] + idio[j][s]) * 0.01;
      }
    }
    final Instrument ust2 = instruments.get(InstrumentSet.UST_2Y);
    final Instrument ust10 = instruments.get(InstrumentSet.UST_10Y);
    final Instrument ty = instruments.get(InstrumentSet.TY_FUTURE);
    final double futurePrice0 = m.value("future_price_0");
    for (int s = 0; s <= seconds; s++) {
      quoted[4][s] = 100.0 + ust2.priceChangePerBp() * (beta[0] * level[s] + idio[4][s]);
      quoted[5][s] = 100.0 + ust10.priceChangePerBp() * (beta[2] * level[s] + idio[5][s]);
      quoted[6][s] = futurePrice0 + ty.priceChangePerBp() * (beta[2] * level[s] + idio[2][s]) + basis[s];
    }

    final List<Event> events = new ArrayList<>(100_000);
    long order = 0;

    // SOFR fixing at the open, and swap mids every interval
    events.add(
        new Event(open, order++, Type.CURVE, SOFR_INDEX, Prices.toMantissa(m.value("sofr_fixing_pct")),
            0, false, 0, 0, false, CurveInputKind.Fixing, "ON"));
    final int curveEvery = (int) m.value("curve_input_interval_s");
    for (int s = 0; s <= seconds; s += curveEvery) {
      for (int j = 0; j < 4; j++) {
        final long px =
            Prices.roundToTick(Prices.toMantissa(quoted[j][s]), instruments.get(SWAPS[j]).tickMantissa(), false);
        events.add(
            new Event(open + s * 1_000_000_000L, order++, Type.CURVE, SWAPS[j], px, 0, false, 0, 0, false,
                CurveInputKind.ParSwap, SWAP_TENORS[j]));
      }
    }

    // CLOB books
    final double[] bookRate = {
      m.value("bond_book_updates_per_s"), m.value("bond_book_updates_per_s"), m.value("future_book_updates_per_s")
    };
    final double[] spreadTicks = {
      m.value("bond_spread_ticks"), m.value("bond_spread_ticks"), m.value("future_spread_ticks")
    };
    final double depthMu = Math.log(m.value("book_depth_median_mm"));
    for (int b = 0; b < BOOKS.length; b++) {
      final Instrument inst = instruments.get(BOOKS[b]);
      for (int s = 0; s < seconds; s++) {
        if (s > 0 && rng.nextDouble() >= bookRate[b]) {
          continue;
        }
        final long t = open + s * 1_000_000_000L + (s == 0 ? 0 : (long) (rng.nextDouble() * 1e9));
        final long tick = inst.tickMantissa();
        final long width2 = Math.max(1, Math.round(spreadTicks[b])) * tick;
        final long mid = Prices.toMantissa(quoted[4 + b][s]);
        final long bid = Prices.roundToTick(mid - width2 / 2, tick, false);
        final long ask = bid + width2;
        final long bidQty = lots(depthMu, inst, rng);
        final long askQty = lots(depthMu, inst, rng);
        events.add(new Event(t, order++, Type.BOOK, BOOKS[b], bid, bidQty, true, 0, 0, false, null, null));
        events.add(new Event(t, order++, Type.BOOK, BOOKS[b], ask, askQty, false, 0, 0, false, null, null));
      }
    }

    // RFQs
    final double b0 = f.value("log_base_rate_per_min");
    final double share = m.value("rfq_share_of_market");
    final double bondRatio = m.value("bond_rfq_ratio");
    final double[] mix = productMix(m);
    final int perTier = (int) m.value("clients_per_tier");
    final double[] tierWeight = {m.value("tier_1_weight"), m.value("tier_2_weight"), m.value("tier_3_weight")};
    final double[] informedShare = {
      m.value("tier_1_informed_share"), m.value("tier_2_informed_share"), m.value("tier_3_informed_share")
    };
    final int horizon = (int) m.value("informed_horizon_s");
    final int dealersMin = (int) m.value("dealers_min");
    final int dealersMax = (int) m.value("dealers_max");
    final double sizeMu = f.value("size_mu_ln_mm");
    final double sizeSigma = f.value("size_sigma_ln_mm");
    final double sizeCap = f.value("size_cap_mm");
    final double bondSizeMu = m.value("bond_size_mu_ln_mm");
    int informedCount = 0;
    int rfqCount = 0;
    for (int s = 0; s < seconds; s++) {
      final double minute = openMin + s / 60.0;
      double z = b0;
      for (int k = 0; k < eventCount; k++) {
        final double d = (minute - eventMinute[k]) / width;
        z += eventAmp[k] * Math.exp(-0.5 * d * d);
      }
      final double ratePerSecond = share * (1.0 + bondRatio) * Math.exp(z) / 60.0;
      final int n = poisson(ratePerSecond, rng);
      for (int i = 0; i < n; i++) {
        final long t = open + s * 1_000_000_000L + (long) (rng.nextDouble() * 1e9);
        final int product = RFQ_PRODUCTS[pick(mix, rng)];
        final int tier = pick(tierWeight, rng);
        final int clientId = tier * perTier + 1 + rng.nextInt(perTier);
        final boolean informed = rng.nextDouble() < informedShare[tier];
        final int idx = indexOf(product);
        final boolean buy;
        if (informed) {
          final int ahead = Math.min(seconds, s + horizon);
          final double move = quoted[idx][ahead] - quoted[idx][s];
          buy = move > 0 || (move == 0 && rng.nextDouble() < 0.5);
        } else {
          buy = rng.nextDouble() < 0.5;
        }
        final Instrument inst = instruments.get(product);
        final double mu = inst.kind() == Instrument.Kind.SWAP ? sizeMu : bondSizeMu;
        final double sizeMm = Math.max(1.0, Math.min(sizeCap, Math.rint(Math.exp(mu + sizeSigma * rng.nextGaussian()))));
        final int dealers = dealersMin + rng.nextInt(dealersMax - dealersMin + 1);
        events.add(
            new Event(t, order++, Type.RFQ, product, 0, Math.round(sizeMm) * InstrumentSet.MILLION, buy,
                clientId, dealers, informed, null, null));
        rfqCount++;
        if (informed) {
          informedCount++;
        }
      }
    }

    events.sort(Comparator.comparingLong(Event::time).thenComparingLong(Event::order));
    final long capacity = capacityFor(events);
    if (capacity > Integer.MAX_VALUE) {
      throw new IllegalStateException("synthetic day too large for one segment: " + capacity);
    }
    return write(date, seed, journal, truthCsv, events, (int) capacity, rfqCount, informedCount);
  }

  private static long lots(final double depthMu, final Instrument inst, final SplitMix64 rng) {
    final double mm = Math.exp(depthMu + 0.5 * rng.nextGaussian());
    final long units = Math.round(mm * InstrumentSet.MILLION / inst.lotSize());
    return Math.max(1, units) * inst.lotSize();
  }

  private static double[] productMix(final Preset m) {
    return new double[] {
      m.value("mix_sofr_2y"), m.value("mix_sofr_5y"), m.value("mix_sofr_10y"), m.value("mix_sofr_30y"),
      m.value("mix_ust_2y"), m.value("mix_ust_10y")
    };
  }

  private static int indexOf(final int product) {
    for (int j = 0; j < 4; j++) {
      if (SWAPS[j] == product) {
        return j;
      }
    }
    return product == InstrumentSet.UST_2Y ? 4 : 5;
  }

  /** Index drawn with probability proportional to the weights. */
  static int pick(final double[] weights, final SplitMix64 rng) {
    double total = 0;
    for (final double w : weights) {
      total += w;
    }
    final double u = rng.nextDouble() * total;
    double acc = 0;
    for (int i = 0; i < weights.length; i++) {
      acc += weights[i];
      if (u < acc) {
        return i;
      }
    }
    return weights.length - 1;
  }

  /** Poisson draw by inversion; fine for the small per-second rates used here. */
  static int poisson(final double mean, final SplitMix64 rng) {
    final double u = rng.nextDouble();
    double p = Math.exp(-mean);
    double cdf = p;
    int k = 0;
    while (u > cdf && k < 1000) {
      k++;
      p *= mean / k;
      cdf += p;
    }
    return k;
  }

  private static long capacityFor(final List<Event> events) {
    long bytes = JournalFormat.FILE_HEADER_LENGTH + Integer.BYTES;
    for (final Event e : events) {
      final int payload =
          MessageHeaderEncoder.ENCODED_LENGTH
              + switch (e.type()) {
                case CURVE -> CurveInputEncoder.BLOCK_LENGTH;
                case BOOK -> BookUpdateEncoder.BLOCK_LENGTH;
                case RFQ -> QuoteRequestEncoder.BLOCK_LENGTH;
              };
      bytes += JournalFormat.alignedFrameLength(payload);
    }
    return (bytes + 4095) & -4096L;
  }

  private Summary write(
      final LocalDate date,
      final long seed,
      final Path journal,
      final Path truthCsv,
      final List<Event> events,
      final int capacity,
      final int rfqCount,
      final int informedCount) {
    final ManualClock clock = new ManualClock(0L);
    final UnsafeBuffer buf = new UnsafeBuffer(ByteBuffer.allocateDirect(256));
    final MessageHeaderEncoder header = new MessageHeaderEncoder();
    final CurveInputEncoder curve = new CurveInputEncoder();
    final BookUpdateEncoder bookEnc = new BookUpdateEncoder();
    final QuoteRequestEncoder rfqEnc = new QuoteRequestEncoder();
    long venueSeq = 1;
    long rfqId = 1;
    int books = 0;
    int curves = 0;
    final StringBuilder truth = new StringBuilder("rfq_id,instrument_id,client_id,informed\n");
    final long hash;
    final long frames;
    try (JournalWriter writer = JournalWriter.create(journal, capacity, clock)) {
      for (final Event e : events) {
        clock.setEpochNanos(e.time());
        switch (e.type()) {
          case CURVE -> {
            curve
                .wrapAndApplyHeader(buf, 0, header)
                .curveId(1)
                .kind(e.kind())
                .instrumentId(e.instrumentId())
                .tenor(String.format(Locale.ROOT, "%-4s", e.tenor()))
                .timestampNanos(e.time());
            curve.value().mantissa(e.price());
            writer.append(CurveInputEncoder.TEMPLATE_ID, CurveInputEncoder.SCHEMA_VERSION, buf, 0, curve.limit());
            curves++;
          }
          case BOOK -> {
            bookEnc
                .wrapAndApplyHeader(buf, 0, header)
                .venueId(CLOB_VENUE)
                .instrumentId(e.instrumentId())
                .venueSeqNo(venueSeq++)
                .side(e.buy() ? Side.Buy : Side.Sell)
                .action(BookAction.Change)
                .level((short) 0)
                .quantity(e.quantity())
                .orderCount(1 + (int) (e.quantity() / (10 * InstrumentSet.MILLION)))
                .timestampNanos(e.time());
            bookEnc.price().mantissa(e.price());
            writer.append(BookUpdateEncoder.TEMPLATE_ID, BookUpdateEncoder.SCHEMA_VERSION, buf, 0, bookEnc.limit());
            books++;
          }
          case RFQ -> {
            final long id = rfqId++;
            rfqEnc
                .wrapAndApplyHeader(buf, 0, header)
                .venueId(RFQ_VENUE)
                .rfqId(id)
                .clientId(e.clientId())
                .instrumentId(e.instrumentId())
                .side(e.buy() ? Side.Buy : Side.Sell)
                .quantity(e.quantity())
                .currency("USD")
                .dealerCount((short) e.dealers())
                .receivedNanos(e.time())
                .expiryNanos(e.time() + 30_000_000_000L);
            writer.append(QuoteRequestEncoder.TEMPLATE_ID, QuoteRequestEncoder.SCHEMA_VERSION, buf, 0, rfqEnc.limit());
            truth.append(id).append(',').append(e.instrumentId()).append(',').append(e.clientId()).append(',')
                .append(e.informed() ? 1 : 0).append('\n');
          }
        }
      }
      hash = writer.chainHash();
      frames = writer.nextSequence() - 1;
    }
    if (truthCsv != null) {
      try (BufferedWriter w = Files.newBufferedWriter(truthCsv, StandardCharsets.UTF_8)) {
        w.write(truth.toString());
      } catch (final IOException ex) {
        throw new UncheckedIOException(ex);
      }
    }
    return new Summary(date, seed, rfqCount, informedCount, books, curves, frames, hash);
  }
}
