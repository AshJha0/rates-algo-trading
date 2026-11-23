package io.ratesalgo.sim;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The simulator's parameters: P of the design document, read from
 * {@code data/calibration/sim_v2_params.json}, and the constants the design fixes in its text,
 * read from {@code sim_v2_structure.json}. No simulation constant is declared in code.
 *
 * <p>Loading is strict. A missing key, an unknown key, a duplicate key, a unit text that does not
 * start with the expected unit, or a value that breaks a stated identity (unit-length loadings, shares
 * that sum to one, move = adverse move / informed fraction) refuses the file. Values that the
 * model does not read (the evidence entries of the calibration) must still be present, so that a
 * renamed entry is noticed.
 *
 * <p>Immutable. The {@code with...} methods return a changed copy for the sensitivity bands of
 * design section 12.
 */
public final class SimParams {

  /** Number of curve factors: level, slope, curvature. */
  public static final int FACTORS = 3;

  /** Client tiers A, B, C. */
  public static final int TIERS = 3;

  private static final String PARAMS_RESOURCE = "/io/ratesalgo/sim/sim_v2_params.json";
  private static final String STRUCTURE_RESOURCE = "/io/ratesalgo/sim/sim_v2_structure.json";
  private static final Set<String> ENTRY_KEYS =
      Set.of("value", "unit", "interval", "window", "fitted_from", "status", "note");
  private static final Set<String> STATUSES = Set.of("fitted", "bounded", "assumption");
  private static final Map<DayType, String> EVENT_NAMES =
      Map.of(DayType.RELEASE, "release_0830", DayType.FOMC, "fomc_1400", DayType.AUCTION, "auction_1300");

  /** A scalar parameter. The unit is in the name or in the comment. */
  public enum Scalar {
    /** Session length, minutes. */
    SESSION_MINUTES,
    /** Session start, minutes after midnight New York. */
    SESSION_START_MINUTE_NY,
    /** Variance multiplier outside event windows; the design uses 1 (flat). */
    TIME_OF_DAY_MULTIPLIER("curve_factors", "time_of_day_multiplier", "multiplier of the per-minute variance"),
    /** Extra variance per minute inside the release window, multiple of the normal variance. */
    RELEASE_EXTRA_VARIANCE,
    /** Extra variance per minute inside the FOMC window, multiple of the normal variance. */
    FOMC_EXTRA_VARIANCE,
    /** Extra variance per minute inside the auction window, multiple of the normal variance. */
    AUCTION_EXTRA_VARIANCE,
    /** Swap-spread factor, bp per sqrt-minute. */
    SPREAD_SD_PER_SQRT_MINUTE("swap_spread_factor", "sd_per_sqrt_minute", "bp per sqrt-minute"),
    /** Bp of swap rate per bp of spread factor, every tenor. */
    SPREAD_LOADING("swap_spread_factor", "loading_by_tenor", "bp of swap rate per bp of factor"),
    /** Stationary sd of the futures basis, bp of yield. */
    BASIS_STATIONARY_SD_BP("futures_basis", "stationary_sd", "bp of yield"),
    /** Half-life of the futures basis, minutes. */
    BASIS_HALF_LIFE_MINUTES("futures_basis", "half_life", "minutes"),
    /** Swap RFQs per session. */
    SWAP_RFQS_PER_DAY("rfq", "swap_rfqs_per_day", "RFQs per session"),
    /** Treasury RFQs per swap RFQ. */
    TREASURY_RFQS_PER_SWAP_RFQ("rfq", "treasury_rfqs", "Treasury RFQs per swap RFQ"),
    /** Log sd of a competitor's half-spread. */
    COMPETITOR_LOG_SD("competitors", "log_sd", "log standard deviation"),
    /** Fewest dealers asked, the desk included. */
    DEALERS_MIN,
    /** Most dealers asked, the desk included. */
    DEALERS_MAX,
    /** Probability that a competitor answers. */
    RESPONSE_PROBABILITY("competitors", "response_probability", "probability"),
    /** Competitor median half-spread on swaps, bp of yield. */
    MEDIAN_HALF_SPREAD_BP("competitors", "median_half_spread", "bp of yield"),
    /** Realised half-spread a break-even competitor needs, bp of client DV01. */
    ALL_IN_COST_BP("competitors", "all_in_cost", "bp of client DV01"),
    /** Treasury competitor median half-spread as a multiple of the swap median. */
    TREASURY_HALF_SPREAD_MULTIPLE("competitors", "treasury_half_spread_multiple", "Treasury competitor median half-spread in bp of yield"),
    /** Exponent of size and tenor in the median half-spread; the design uses 0. */
    SIZE_AND_TENOR_SCALING("competitors", "size_and_tenor_scaling", "exponent"),
    /** Expected move against the winning dealer per trade, bp of yield. */
    EXPECTED_ADVERSE_MOVE_BP("informed_flow", "expected_adverse_move", "bp of yield per trade"),
    /** Probability that an RFQ is informed, averaged over tiers. */
    INFORMED_FRACTION("informed_flow", "informed_fraction", "probability"),
    /** Move per informed RFQ at the traded tenor, bp of yield. */
    MOVE_PER_INFORMED_TRADE_BP("informed_flow", "move_per_informed_trade", "bp of yield at the traded tenor"),
    /** Time constant of the information drift, seconds. */
    DRIFT_TIME_CONSTANT_S("informed_flow", "drift_time_constant", "seconds"),
    /** Impact coefficient Y, dimensionless. */
    IMPACT_Y("clob", "impact_coefficient_Y", "dimensionless"),
    /** Share of peak impact that is permanent. */
    IMPACT_PERMANENT_SHARE("clob", "impact_permanent_share", "share of peak impact"),
    /** Half-life of transient impact and of depth refill, seconds. */
    IMPACT_HALF_LIFE_S("clob", "impact_half_life", "seconds"),
    /** Daily volume of one on-the-run Treasury on the book, USD billions. */
    TREASURY_DAILY_VOLUME_USD_BN("clob", "daily_volume_usd_bn", "USD billions per day"),
    /** Daily volume of the note future, contracts. */
    FUTURES_DAILY_VOLUME_CONTRACTS("clob", "futures_daily_volume_contracts", "contracts per day"),
    /** Probability above one half that a taker trades with the next move. */
    TAKER_SIGN_SKILL("clob", "taker_sign_skill", "probability above one half"),
    /** Provider size at the touch of the reference instrument, USD millions. */
    TOUCH_DEPTH_USD_MM("clob", "touch_depth_usd_mm", "USD millions"),
    /** Median size of another taker's trade, USD millions. */
    TAKER_TRADE_SIZE_USD_MM("clob", "taker_trade_size_usd_mm", "USD millions"),
    /** Grid step, seconds. */
    STEP_SECONDS("grid", "step_seconds"),
    /** Width of an arrival bin, minutes. */
    RFQ_BIN_MINUTES("rfq", "bin_minutes"),
    /** Smallest RFQ and its size increment, USD millions. */
    MIN_SIZE_MM("rfq", "min_size_mm"),
    /** Redraws of a swap size above the cap before the cap is used. */
    SIZE_REDRAWS("rfq", "size_redraws"),
    /** Median competitor response time, milliseconds. */
    DEALER_DELAY_MEDIAN_MS("competitors", "delay_median_ms"),
    /** Log sd of the competitor response time. */
    DEALER_DELAY_LOG_SD("competitors", "delay_log_sd"),
    /** Time dealers have to answer, milliseconds. */
    RESPONSE_WINDOW_MS("competitors", "response_window_ms"),
    /** Median client reserve as a multiple of the median half-spread. */
    RESERVE_MULTIPLE_MEDIAN("competitors", "reserve_multiple_median"),
    /** Log sd of the client reserve. */
    RESERVE_LOG_SD("competitors", "reserve_log_sd"),
    /** Probability that the client trades elsewhere. */
    DONE_AWAY_PROBABILITY("competitors", "done_away_probability"),
    /** Markout horizon of the zero-profit condition, seconds. */
    MARKOUT_HORIZON_S("competitors", "markout_horizon_s"),
    /** Log sd of another taker's trade size. */
    TAKER_SIZE_LOG_SD("clob", "taker_size_log_sd"),
    /** How far ahead a taker's sign looks, seconds. */
    TAKER_LOOK_AHEAD_S("clob", "taker_look_ahead_s"),
    /** Largest error of the zero-profit identity that still starts, bp. */
    ZERO_PROFIT_TOLERANCE_BP("checks", "zero_profit_tolerance_bp"),
    /** Largest share of the level variance that informed flow may supply. */
    MAX_INFORMED_VARIANCE_SHARE("checks", "max_informed_variance_share"),
    /** Largest distance of a loading column's length from one. */
    LOADING_NORM_TOLERANCE("checks", "loading_norm_tolerance");

    private final String group;
    private final String key;
    private final String unit;

    Scalar() {
      this(null, null, null);
    }

    Scalar(final String group, final String key) {
      this(group, key, null);
    }

    Scalar(final String group, final String key, final String unit) {
      this.group = group;
      this.key = key;
      this.unit = unit;
    }
  }

  private final EnumMap<Scalar, Double> scalars;
  private final double[] tenorsYears;
  private final double[][] loadings;
  private final double[] factorSd;
  private final double[] factorDailySd;
  private final double[] idioSd;
  private final double[] tenorMix;
  private final double[] timeOfDayShare;
  private final double[] sizeLogMean;
  private final double[] sizeLogSd;
  private final double[] sizeCapMm;
  private final double[] tierWeight;
  private final double[] tierInformedBase;
  private final double informedFractionBase;
  private final double[] ladderSizeMm;
  private final double[] ladderWeight;
  private final double[] depthMultiple;
  private final double[] venueShare;
  private final EnumMap<DayType, int[]> eventWindowMinutes;
  private final List<SimInstrument> instruments;
  private final String depthReferenceKey;

  private SimParams(final Builder b) {
    this.scalars = new EnumMap<>(b.scalars);
    this.tenorsYears = b.tenorsYears;
    this.loadings = b.loadings;
    this.factorSd = b.factorSd.clone();
    this.factorDailySd = b.factorDailySd.clone();
    this.idioSd = b.idioSd.clone();
    this.tenorMix = b.tenorMix;
    this.timeOfDayShare = b.timeOfDayShare;
    this.sizeLogMean = b.sizeLogMean;
    this.sizeLogSd = b.sizeLogSd;
    this.sizeCapMm = b.sizeCapMm;
    this.tierWeight = b.tierWeight;
    this.tierInformedBase = b.tierInformedBase;
    this.informedFractionBase = b.informedFractionBase;
    this.ladderSizeMm = b.ladderSizeMm;
    this.ladderWeight = b.ladderWeight;
    this.depthMultiple = b.depthMultiple;
    this.venueShare = b.venueShare;
    this.eventWindowMinutes = b.eventWindowMinutes;
    this.instruments = List.copyOf(b.instruments);
    this.depthReferenceKey = b.depthReferenceKey;
    validate();
  }

  /** Mutable carrier used while loading and when copying; the arrays are shared, never written after load. */
  private static final class Builder {
    EnumMap<Scalar, Double> scalars = new EnumMap<>(Scalar.class);
    double[] tenorsYears;
    double[][] loadings;
    double[] factorSd;
    double[] factorDailySd;
    double[] idioSd;
    double[] tenorMix;
    double[] timeOfDayShare;
    double[] sizeLogMean;
    double[] sizeLogSd;
    double[] sizeCapMm;
    double[] tierWeight;
    double[] tierInformedBase;
    double informedFractionBase;
    double[] ladderSizeMm;
    double[] ladderWeight;
    double[] depthMultiple;
    double[] venueShare;
    EnumMap<DayType, int[]> eventWindowMinutes;
    List<SimInstrument> instruments;
    String depthReferenceKey;
  }

  private Builder copy() {
    final Builder b = new Builder();
    b.scalars = new EnumMap<>(scalars);
    b.tenorsYears = tenorsYears;
    b.loadings = loadings;
    b.factorSd = factorSd.clone();
    b.factorDailySd = factorDailySd.clone();
    b.idioSd = idioSd.clone();
    b.tenorMix = tenorMix;
    b.timeOfDayShare = timeOfDayShare;
    b.sizeLogMean = sizeLogMean;
    b.sizeLogSd = sizeLogSd;
    b.sizeCapMm = sizeCapMm;
    b.tierWeight = tierWeight;
    b.tierInformedBase = tierInformedBase;
    b.informedFractionBase = informedFractionBase;
    b.ladderSizeMm = ladderSizeMm;
    b.ladderWeight = ladderWeight;
    b.depthMultiple = depthMultiple;
    b.venueShare = venueShare;
    b.eventWindowMinutes = eventWindowMinutes;
    b.instruments = instruments;
    b.depthReferenceKey = depthReferenceKey;
    return b;
  }

  // ------------------------------------------------------------------ loading

  /** The parameters packaged with the build: the committed calibration file and structure file. */
  public static SimParams loadDefault() {
    return parse(resource(PARAMS_RESOURCE), resource(STRUCTURE_RESOURCE));
  }

  /** Text of the packaged calibration file (tests edit it to prove the loader is strict). */
  public static String defaultParamsText() {
    return resource(PARAMS_RESOURCE);
  }

  /** Text of the packaged structure file. */
  public static String defaultStructureText() {
    return resource(STRUCTURE_RESOURCE);
  }

  private static String resource(final String name) {
    try (InputStream in = SimParams.class.getResourceAsStream(name)) {
      if (in == null) {
        throw new IllegalStateException(name + " missing from the class path");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Parses the two files. */
  public static SimParams parse(final String paramsJson, final String structureJson) {
    final Builder b = new Builder();
    readParams(new Obj(StrictJson.parse(paramsJson), "sim_v2_params"), b);
    readStructure(new Obj(StrictJson.parse(structureJson), "sim_v2_structure"), b);
    return new SimParams(b);
  }

  /** One JSON object with a record of which keys were read, so unknown keys can be refused. */
  private static final class Obj {
    private final Map<String, Object> map;
    private final String where;
    private final Set<String> seen = new HashSet<>();

    @SuppressWarnings("unchecked")
    Obj(final Object o, final String where) {
      if (!(o instanceof Map)) {
        throw new IllegalArgumentException(where + ": an object is required");
      }
      this.map = (Map<String, Object>) o;
      this.where = where;
    }

    Object take(final String key) {
      if (!map.containsKey(key)) {
        throw new IllegalArgumentException(where + ": missing key '" + key + "'");
      }
      seen.add(key);
      return map.get(key);
    }

    Obj child(final String key) {
      return new Obj(take(key), where + "." + key);
    }

    double number(final String key) {
      final Object v = take(key);
      if (!(v instanceof Double d)) {
        throw new IllegalArgumentException(where + "." + key + ": a number is required");
      }
      return d;
    }

    String text(final String key) {
      final Object v = take(key);
      if (!(v instanceof String text)) {
        throw new IllegalArgumentException(where + "." + key + ": text is required");
      }
      return text;
    }

    @SuppressWarnings("unchecked")
    List<Object> list(final String key) {
      final Object v = take(key);
      if (!(v instanceof List)) {
        throw new IllegalArgumentException(where + "." + key + ": a list is required");
      }
      return (List<Object>) v;
    }

    /** A calibration entry: checks its shape, its status and that the unit text starts with {@code unit}. */
    Obj entry(final String key, final String unit) {
      final Obj e = child(key);
      e.checkEntry(unit);
      return e;
    }

    /** An entry the model does not read (evidence of the calibration): it must be there and well formed. */
    void evidence(final String key, final String unit) {
      child(key).checkEntry(unit);
    }

    private void checkEntry(final String unit) {
      for (final String k : map.keySet()) {
        if (!ENTRY_KEYS.contains(k)) {
          throw new IllegalArgumentException(where + ": unknown key '" + k + "'");
        }
      }
      if (!map.containsKey("value")) {
        throw new IllegalArgumentException(where + ": missing key 'value'");
      }
      final String status = text("status");
      if (!STATUSES.contains(status)) {
        throw new IllegalArgumentException(where + ": unknown status '" + status + "'");
      }
      final String u = text("unit");
      if (!u.startsWith(unit)) {
        throw new IllegalArgumentException(where + ": unit '" + u + "' does not start with '" + unit + "'");
      }
    }

    /** Requires the keys to be present without reading them. */
    void present(final String... keys) {
      for (final String k : keys) {
        take(k);
      }
    }

    /** Refuses any key that was not read. */
    void done() {
      for (final String k : map.keySet()) {
        if (!seen.contains(k)) {
          throw new IllegalArgumentException(where + ": unknown key '" + k + "'");
        }
      }
    }
  }

  private static double num(final Object o, final String where) {
    if (!(o instanceof Double d)) {
      throw new IllegalArgumentException(where + ": a number is required");
    }
    return d;
  }

  private static double[] numbers(final Object o, final int length, final String where) {
    if (!(o instanceof List<?> list) || list.size() != length) {
      throw new IllegalArgumentException(where + ": a list of " + length + " numbers is required");
    }
    final double[] out = new double[length];
    for (int i = 0; i < length; i++) {
      out[i] = num(list.get(i), where);
    }
    return out;
  }

  private static String tenorKey(final double years) {
    return (int) years + "y";
  }

  /** The value of a by-tenor entry as one number per tenor, in tenor order; no other key allowed. */
  private static double[] byTenor(final Obj value, final double[] tenors) {
    final double[] out = new double[tenors.length];
    for (int n = 0; n < tenors.length; n++) {
      out[n] = value.number(tenorKey(tenors[n]));
    }
    value.done();
    return out;
  }

  private static int minuteOfDay(final String hhmm, final String where) {
    if (!hhmm.matches("\\d\\d:\\d\\d")) {
      throw new IllegalArgumentException(where + ": time of day HH:MM is required, got '" + hhmm + "'");
    }
    return Integer.parseInt(hhmm.substring(0, 2)) * 60 + Integer.parseInt(hhmm.substring(3));
  }

  private static void put(final Builder b, final Scalar s, final double v) {
    b.scalars.put(s, v);
  }

  private static void readParams(final Obj root, final Builder b) {
    if (!"sim_v2_params/1".equals(root.text("schema"))) {
      throw new IllegalArgumentException("sim_v2_params: schema must be sim_v2_params/1");
    }
    root.present("pre_analysis_note", "evidence", "specification", "reproduce", "old_constants_check");
    final List<Object> tenors = root.list("tenors_years");
    b.tenorsYears = numbers(tenors, tenors.size(), "sim_v2_params.tenors_years");
    final int nodes = b.tenorsYears.length;

    final Obj session = root.child("session");
    final int start = minuteOfDay(session.text("start_ny"), "session.start_ny");
    final int end = minuteOfDay(session.text("end_ny"), "session.end_ny");
    put(b, Scalar.SESSION_START_MINUTE_NY, start);
    put(b, Scalar.SESSION_MINUTES, session.number("minutes"));
    if (Math.abs(end - start - b.scalars.get(Scalar.SESSION_MINUTES)) > 1e-9) {
      throw new IllegalArgumentException("session: minutes is not end_ny minus start_ny");
    }
    session.done();

    final Obj curve = root.child("curve_factors");
    final Obj loadingValue = curve.entry("loadings", "bp of par yield per unit of factor").child("value");
    b.loadings = new double[nodes][];
    for (int n = 0; n < nodes; n++) {
      final String key = tenorKey(b.tenorsYears[n]);
      b.loadings[n] = numbers(loadingValue.take(key), FACTORS, "loadings." + key);
    }
    loadingValue.done();
    b.factorDailySd = numbers(curve.entry("daily_sd", "bp per day").take("value"), FACTORS, "daily_sd");
    curve.evidence("session_variance_share", "share of the daily variance");
    b.factorSd =
        numbers(curve.entry("sd_per_sqrt_minute", "bp per sqrt-minute").take("value"), FACTORS, "sd_per_sqrt_minute");
    b.idioSd =
        byTenor(curve.entry("idiosyncratic_sd_per_sqrt_minute", "bp per sqrt-minute").child("value"), b.tenorsYears);
    final Obj windows = curve.child("event_windows");
    final String extra = "extra variance per minute";
    put(b, Scalar.RELEASE_EXTRA_VARIANCE, windows.entry(EVENT_NAMES.get(DayType.RELEASE), extra).number("value"));
    put(b, Scalar.FOMC_EXTRA_VARIANCE, windows.entry(EVENT_NAMES.get(DayType.FOMC), extra).number("value"));
    put(b, Scalar.AUCTION_EXTRA_VARIANCE, windows.entry(EVENT_NAMES.get(DayType.AUCTION), extra).number("value"));
    windows.done();

    final Obj spread = root.child("swap_spread_factor");
    spread.evidence("daily_sd", "bp per day");
    final Obj basis = root.child("futures_basis");

    final Obj rfq = root.child("rfq");
    rfq.evidence("market_prints_per_day_by_tenor", "D2C facility prints per day");
    rfq.evidence("desk_inquiry_share", "desk swap RFQs per market D2C print");
    b.tenorMix = byTenor(rfq.entry("tenor_mix", "probability").child("value"), b.tenorsYears);
    final Obj share = rfq.entry("time_of_day_share", "share of the day's prints per 30-minute bin");
    final List<Object> shares = share.list("value");
    b.timeOfDayShare = numbers(shares, shares.size(), "time_of_day_share");
    final Obj sizes = rfq.entry("size_lognormal_by_tenor", "notional in USD millions").child("value");
    b.sizeLogMean = new double[nodes];
    b.sizeLogSd = new double[nodes];
    b.sizeCapMm = new double[nodes];
    for (int n = 0; n < nodes; n++) {
      final Obj s = sizes.child(tenorKey(b.tenorsYears[n]));
      b.sizeLogMean[n] = s.number("log_mean_mm");
      b.sizeLogSd[n] = s.number("log_sd");
      b.sizeCapMm[n] = s.number("cap_mm");
      s.present("capped_share");
      s.done();
    }
    sizes.done();

    final Obj comp = root.child("competitors");
    comp.evidence("winner_half_spread_rms", "bp of yield");
    final Obj dealers = comp.entry("dealers_min_max", "dealers asked, uniform from min to max inclusive");
    put(b, Scalar.DEALERS_MIN, dealers.number("value"));
    put(b, Scalar.DEALERS_MAX, numbers(dealers.take("interval"), 2, "dealers_min_max.interval")[1]);

    final Obj informed = root.child("informed_flow");
    informed.evidence("measured_raw_realised_half_spread_5min", "bp");
    final Obj tiers = informed.entry("tier_weights_and_informed_probability", "weights of client tiers A, B, C");
    final double[] rest = numbers(tiers.take("interval"), TIERS - 1, "tier weights");
    b.tierWeight = new double[] {tiers.number("value"), rest[0], rest[1]};

    final Obj clob = root.child("clob");
    final Map<String, Obj> groups =
        Map.of(
            "curve_factors", curve,
            "swap_spread_factor", spread,
            "futures_basis", basis,
            "rfq", rfq,
            "competitors", comp,
            "informed_flow", informed,
            "clob", clob);
    for (final Scalar s : Scalar.values()) {
      if (s.unit != null) {
        put(b, s, groups.get(s.group).entry(s.key, s.unit).number("value"));
      }
    }
    for (final Obj g : groups.values()) {
      g.done();
    }
    root.done();
  }

  private static void readStructure(final Obj root, final Builder b) {
    if (!"sim_v2_structure/1".equals(root.text("schema"))) {
      throw new IllegalArgumentException("sim_v2_structure: schema must be sim_v2_structure/1");
    }
    root.present("specification", "note");
    final Obj windows = root.child("event_windows");
    windows.present("section");
    b.eventWindowMinutes = new EnumMap<>(DayType.class);
    for (final Map.Entry<DayType, String> e : EVENT_NAMES.entrySet()) {
      final Obj w = windows.child(e.getValue());
      b.eventWindowMinutes.put(
          e.getKey(),
          new int[] {minuteOfDay(w.text("start_ny"), e.getValue()), minuteOfDay(w.text("end_ny"), e.getValue())});
      w.done();
    }
    windows.done();

    final Obj rfq = root.child("rfq");
    final List<Object> ladder = rfq.list("treasury_size_ladder_mm");
    b.ladderSizeMm = new double[ladder.size()];
    b.ladderWeight = new double[ladder.size()];
    for (int i = 0; i < ladder.size(); i++) {
      final double[] pair = numbers(ladder.get(i), 2, "treasury_size_ladder_mm");
      b.ladderSizeMm[i] = pair[0];
      b.ladderWeight[i] = pair[1];
    }
    b.tierInformedBase = numbers(rfq.take("tier_informed_probability"), TIERS, "tier_informed_probability");
    b.informedFractionBase = b.scalars.get(Scalar.INFORMED_FRACTION);

    final Obj clob = root.child("clob");
    final List<Object> depth = clob.list("depth_multiples");
    b.depthMultiple = numbers(depth, depth.size(), "depth_multiples");
    b.depthReferenceKey = clob.text("depth_reference_instrument");
    final List<Object> shares = clob.list("treasury_venue_shares");
    b.venueShare = numbers(shares, shares.size(), "treasury_venue_shares");

    final Map<String, Obj> groups =
        Map.of(
            "grid", root.child("grid"),
            "rfq", rfq,
            "competitors", root.child("competitors"),
            "clob", clob,
            "checks", root.child("checks"));
    for (final Scalar s : Scalar.values()) {
      if (s.unit == null && s.group != null) {
        put(b, s, groups.get(s.group).number(s.key));
      }
    }
    for (final Obj g : groups.values()) {
      g.present("section");
      g.done();
    }

    final Obj universe = root.child("instruments");
    universe.present("section", "note");
    b.instruments = new ArrayList<>();
    final Set<String> keys = new HashSet<>();
    for (final Object o : universe.list("list")) {
      final Obj i = new Obj(o, "instruments[" + b.instruments.size() + "]");
      final SimInstrument inst =
          new SimInstrument(
              b.instruments.size(),
              i.text("key"),
              (int) i.number("id"),
              SimInstrument.Kind.valueOf(i.text("kind").toUpperCase(java.util.Locale.ROOT)),
              i.number("node_years"),
              i.number("initial_mid"),
              i.number("price_change_per_bp"),
              i.number("tick"),
              i.number("unit_face_usd"),
              i.number("min_quantity"),
              i.number("annuity"));
      i.done();
      if (!keys.add(inst.key())) {
        throw new IllegalArgumentException("instruments: duplicate key " + inst.key());
      }
      b.instruments.add(inst);
    }
    universe.done();
    root.done();
  }

  // ------------------------------------------------------------------ identities

  private static void near(final double actual, final double expected, final double tolerance, final String what) {
    if (!(Math.abs(actual - expected) <= tolerance)) {
      throw new IllegalArgumentException(what + ": " + actual + " is not " + expected + " within " + tolerance);
    }
  }

  private static double sum(final double[] a) {
    double s = 0.0;
    for (final double v : a) {
      s += v;
    }
    return s;
  }

  private void validate() {
    final int nodes = tenorsYears.length;
    for (int n = 1; n < nodes; n++) {
      if (!(tenorsYears[n] > tenorsYears[n - 1])) {
        throw new IllegalArgumentException("tenors_years must increase");
      }
    }
    for (int j = 0; j < FACTORS; j++) {
      double norm = 0.0;
      for (int n = 0; n < nodes; n++) {
        norm += loadings[n][j] * loadings[n][j];
      }
      near(Math.sqrt(norm), 1.0, get(Scalar.LOADING_NORM_TOLERANCE), "length of loading column " + j);
    }
    near(sum(tenorMix), 1.0, 1e-3, "sum of rfq.tenor_mix");
    near(sum(timeOfDayShare), 1.0, 1e-3, "sum of rfq.time_of_day_share");
    near(sum(tierWeight), 1.0, 1e-9, "sum of the tier weights");
    near(sum(ladderWeight), 1.0, 1e-9, "sum of the Treasury size ladder weights");
    near(sum(venueShare), 1.0, 1e-9, "sum of the Treasury venue shares");
    near(
        timeOfDayShare.length * get(Scalar.RFQ_BIN_MINUTES),
        get(Scalar.SESSION_MINUTES),
        0.0,
        "arrival bins times bin width against the session length");
    double informed = 0.0;
    for (int t = 0; t < TIERS; t++) {
      informed += tierWeight[t] * tierInformedBase[t];
    }
    near(informed, informedFractionBase, 1e-9, "tier-weighted informed probability against informed_fraction");
    final double f = get(Scalar.INFORMED_FRACTION);
    if (!(f >= 0.0 && f <= 1.0)) {
      throw new IllegalArgumentException("informed_fraction outside [0, 1]: " + f);
    }
    if (f > 0.0) {
      near(
          get(Scalar.MOVE_PER_INFORMED_TRADE_BP) * f,
          get(Scalar.EXPECTED_ADVERSE_MOVE_BP),
          5e-4,
          "move_per_informed_trade times informed_fraction against expected_adverse_move");
    }
    near(get(Scalar.TIME_OF_DAY_MULTIPLIER), 1.0, 0.0, "time_of_day_multiplier (only the flat profile is specified)");
    near(get(Scalar.SIZE_AND_TENOR_SCALING), 0.0, 0.0, "size_and_tenor_scaling (only 0 is specified)");
    near(get(Scalar.STEP_SECONDS), 1.0, 0.0, "grid step (the recursion of section 3 is written for 1 s)");
    if (get(Scalar.DEALERS_MIN) < 2 || get(Scalar.DEALERS_MAX) < get(Scalar.DEALERS_MIN)) {
      throw new IllegalArgumentException("dealers asked must be at least 2 and min <= max");
    }
    for (final Scalar s : Scalar.values()) {
      final double v = get(s);
      if (Double.isNaN(v) || v < 0.0) {
        throw new IllegalArgumentException(s + " must be a non-negative number: " + v);
      }
    }
    boolean reference = false;
    for (final SimInstrument i : instruments) {
      reference |= i.key().equals(depthReferenceKey) && i.kind() == SimInstrument.Kind.TREASURY;
    }
    if (!reference) {
      throw new IllegalArgumentException("depth_reference_instrument is not a Treasury of the universe");
    }
  }

  // ------------------------------------------------------------------ access

  /** A scalar parameter. */
  public double get(final Scalar s) {
    return scalars.get(s);
  }

  /** Number of tenor nodes of the curve model. */
  public int nodes() {
    return tenorsYears.length;
  }

  /** Maturity of node {@code n}, years. */
  public double tenorYears(final int n) {
    return tenorsYears[n];
  }

  /** Loading of node {@code n} on factor {@code j} (0 level, 1 slope, 2 curvature), bp per factor unit. */
  public double loading(final int n, final int j) {
    return loadings[n][j];
  }

  /** Total sd of factor {@code j} outside event windows, bp per sqrt-minute. */
  public double factorSdPerSqrtMinute(final int j) {
    return factorSd[j];
  }

  /** Daily sd of factor {@code j}, bp per day; used for the impact scale only. */
  public double factorDailySdBp(final int j) {
    return factorDailySd[j];
  }

  /** Idiosyncratic sd of node {@code n}, bp per sqrt-minute. */
  public double idiosyncraticSdPerSqrtMinute(final int n) {
    return idioSd[n];
  }

  /** Probability that a swap RFQ has the tenor of node {@code n}. */
  public double tenorMix(final int n) {
    return tenorMix[n];
  }

  /** Number of arrival bins. */
  public int bins() {
    return timeOfDayShare.length;
  }

  /** Share of the day's RFQs in bin {@code b}. */
  public double timeOfDayShare(final int b) {
    return timeOfDayShare[b];
  }

  /** Mean of the log of the swap notional in USD millions at node {@code n}. */
  public double sizeLogMeanMm(final int n) {
    return sizeLogMean[n];
  }

  /** Sd of the log of the swap notional at node {@code n}. */
  public double sizeLogSd(final int n) {
    return sizeLogSd[n];
  }

  /** Largest swap notional at node {@code n}, USD millions. */
  public double sizeCapMm(final int n) {
    return sizeCapMm[n];
  }

  /** Weight of client tier {@code t} (0 is A). */
  public double tierWeight(final int t) {
    return tierWeight[t];
  }

  /**
   * Probability that an RFQ of tier {@code t} is informed. At the file's informed fraction these
   * are the file's values; at another fraction f the tiers keep their order and their weighted
   * mean is f: p = f + (p0 - f0) x min(f, 1 - f) / min(f0, 1 - f0).
   */
  public double tierInformedProbability(final int t) {
    final double f = get(Scalar.INFORMED_FRACTION);
    final double room = Math.min(informedFractionBase, 1.0 - informedFractionBase);
    final double scale = room > 0.0 ? Math.min(f, 1.0 - f) / room : 0.0;
    return f + (tierInformedBase[t] - informedFractionBase) * scale;
  }

  /** Steps of the Treasury RFQ size ladder. */
  public int ladderSteps() {
    return ladderSizeMm.length;
  }

  /** Size of ladder step {@code i}, USD millions. */
  public double ladderSizeMm(final int i) {
    return ladderSizeMm[i];
  }

  /** Weight of ladder step {@code i}. */
  public double ladderWeight(final int i) {
    return ladderWeight[i];
  }

  /** Provider levels per side of an order book. */
  public int bookLevels() {
    return depthMultiple.length;
  }

  /** Target size of level {@code l} (0 is the touch) as a multiple of the touch size. */
  public double depthMultiple(final int l) {
    return depthMultiple[l];
  }

  /** Number of Treasury order-book venues that share the model's book. */
  public int treasuryVenues() {
    return venueShare.length;
  }

  /** Share of the model's provider size and taker flow that Treasury venue {@code v} shows and receives. */
  public double treasuryVenueShare(final int v) {
    return venueShare[v];
  }

  /** Start of the day type's event window, minutes after midnight New York; -1 for no event. */
  public int eventStartMinuteNy(final DayType day) {
    return day == DayType.NONE ? -1 : eventWindowMinutes.get(day)[0];
  }

  /** End of the day type's event window, minutes after midnight New York; -1 for no event. */
  public int eventEndMinuteNy(final DayType day) {
    return day == DayType.NONE ? -1 : eventWindowMinutes.get(day)[1];
  }

  /** Extra variance per minute inside the day type's window, multiple of the normal variance. */
  public double eventExtraVariance(final DayType day) {
    return switch (day) {
      case NONE -> 0.0;
      case RELEASE -> get(Scalar.RELEASE_EXTRA_VARIANCE);
      case FOMC -> get(Scalar.FOMC_EXTRA_VARIANCE);
      case AUCTION -> get(Scalar.AUCTION_EXTRA_VARIANCE);
    };
  }

  /** The instrument universe, in index order. */
  public List<SimInstrument> instruments() {
    return instruments;
  }

  /** Key of the instrument whose touch depth is {@link Scalar#TOUCH_DEPTH_USD_MM}. */
  public String depthReferenceKey() {
    return depthReferenceKey;
  }

  // ------------------------------------------------------------------ sensitivities

  /** A copy with one scalar changed. Identities are checked again. */
  public SimParams with(final Scalar s, final double value) {
    final Builder b = copy();
    put(b, s, value);
    return new SimParams(b);
  }

  /**
   * A copy with another informed fraction at a fixed average adverse move: the move per informed
   * RFQ becomes expected_adverse_move / fraction (design section 12, sensitivities).
   */
  public SimParams withInformedFraction(final double fraction) {
    final Builder b = copy();
    put(b, Scalar.INFORMED_FRACTION, fraction);
    put(b, Scalar.MOVE_PER_INFORMED_TRADE_BP, fraction > 0.0 ? get(Scalar.EXPECTED_ADVERSE_MOVE_BP) / fraction : 0.0);
    return new SimParams(b);
  }

  /**
   * A copy with another competitor median half-spread or all-in cost and the adverse move solved
   * again for zero competitor profit: adverse = median x E[min] - cost (design section 7).
   */
  public SimParams withZeroProfit(final double medianHalfSpreadBp, final double allInCostBp) {
    final Builder b = copy();
    put(b, Scalar.MEDIAN_HALF_SPREAD_BP, medianHalfSpreadBp);
    put(b, Scalar.ALL_IN_COST_BP, allInCostBp);
    final double adverse = medianHalfSpreadBp * Competition.expectedBestPerMedian(this) - allInCostBp;
    if (adverse < 0.0) {
      throw new IllegalArgumentException("the winning half-spread does not cover the all-in cost");
    }
    final double f = get(Scalar.INFORMED_FRACTION);
    put(b, Scalar.EXPECTED_ADVERSE_MOVE_BP, adverse);
    put(b, Scalar.MOVE_PER_INFORMED_TRADE_BP, f > 0.0 ? adverse / f : 0.0);
    return new SimParams(b);
  }

  /** A copy with every curve factor's sd (per sqrt-minute and per day) scaled; idiosyncratic terms unchanged. */
  public SimParams withFactorSdScale(final double scale) {
    final Builder b = copy();
    for (int j = 0; j < FACTORS; j++) {
      b.factorSd[j] = factorSd[j] * scale;
      b.factorDailySd[j] = factorDailySd[j] * scale;
    }
    return new SimParams(b);
  }

  /** A copy with another instrument universe (acceptance test A6 doubles the ticks). */
  public SimParams withInstruments(final List<SimInstrument> universe) {
    final Builder b = copy();
    b.instruments = List.copyOf(universe);
    return new SimParams(b);
  }
}
