package io.ratesalgo.analytics.golden;

import io.ratesalgo.analytics.curve.CurveBuilder;
import io.ratesalgo.analytics.curve.CurveInstrument;
import io.ratesalgo.analytics.curve.DiscountCurve;
import io.ratesalgo.analytics.curve.Interpolation;
import io.ratesalgo.analytics.curve.OisFutureInstrument;
import io.ratesalgo.analytics.curve.OisSwapInstrument;
import io.ratesalgo.analytics.curve.PeriodRateInstrument;
import io.ratesalgo.analytics.curve.TermSwapInstrument;
import io.ratesalgo.analytics.date.Calendars;
import io.ratesalgo.analytics.date.Dates;
import io.ratesalgo.analytics.date.DayCount;
import io.ratesalgo.analytics.swap.FixingHistory;
import io.ratesalgo.analytics.swap.SwapConvention;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Loads data/golden/analytics_golden.json and builds Java objects from its records. */
public final class Golden {

  private static Map<String, Object> cached;

  private Golden() {}

  /** Repository root, from the Surefire system property or the working directory's parent. */
  public static Path repoRoot() {
    final String prop = System.getProperty("rates.repo.root");
    if (prop != null && Files.isDirectory(Path.of(prop, "data"))) {
      return Path.of(prop);
    }
    Path p = Path.of("").toAbsolutePath();
    while (p != null && !Files.isDirectory(p.resolve("data").resolve("golden"))) {
      p = p.getParent();
    }
    if (p == null) {
      throw new IllegalStateException("cannot find the repository root with data/golden");
    }
    return p;
  }

  /** The parsed golden file. */
  @SuppressWarnings("unchecked")
  public static synchronized Map<String, Object> load() {
    if (cached == null) {
      try {
        final String text =
            Files.readString(
                repoRoot().resolve("data/golden/analytics_golden.json"), StandardCharsets.UTF_8);
        cached = (Map<String, Object>) Json.parse(text);
      } catch (final IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return cached;
  }

  @SuppressWarnings("unchecked")
  public static List<Map<String, Object>> list(final Map<String, Object> m, final String key) {
    return (List<Map<String, Object>>) m.get(key);
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> map(final Map<String, Object> m, final String key) {
    return (Map<String, Object>) m.get(key);
  }

  @SuppressWarnings("unchecked")
  public static List<Object> raw(final Map<String, Object> m, final String key) {
    return (List<Object>) m.get(key);
  }

  public static double num(final Map<String, Object> m, final String key) {
    final Object v = m.get(key);
    if (!(v instanceof Double d)) {
      throw new IllegalArgumentException("missing number " + key + " in " + m);
    }
    return d;
  }

  public static int integer(final Map<String, Object> m, final String key) {
    return (int) Math.round(num(m, key));
  }

  public static String str(final Map<String, Object> m, final String key) {
    return (String) m.get(key);
  }

  public static int date(final Map<String, Object> m, final String key) {
    return Dates.parse(str(m, key));
  }

  public static int today() {
    return Dates.parse(str(load(), "valuation_date"));
  }

  /** Published fixings for a currency, as recorded in the golden file. */
  public static FixingHistory fixings(final String ccy) {
    final Map<String, Object> f = map(map(load(), "fixings"), ccy);
    final List<Object> dates = raw(f, "dates");
    final List<Object> rates = raw(f, "rates");
    final int[] d = new int[dates.size()];
    final double[] r = new double[rates.size()];
    for (int i = 0; i < d.length; i++) {
      d[i] = Dates.parse((String) dates.get(i));
      r[i] = (Double) rates.get(i);
    }
    return new FixingHistory(d, r);
  }

  /** The golden curve record with the given id. */
  public static Map<String, Object> curveRecord(final String id) {
    for (final Map<String, Object> c : list(load(), "curves")) {
      if (id.equals(c.get("id"))) {
        return c;
      }
    }
    throw new IllegalArgumentException("no curve " + id);
  }

  /** QuantLib's curve as a Java curve: same pillars, same discount factors, log-linear. */
  public static DiscountCurve quantLibCurve(final String id) {
    final Map<String, Object> c = curveRecord(id);
    final List<Object> dates = raw(c, "pillar_dates");
    final List<Object> dfs = raw(c, "discount_factors");
    final int[] d = new int[dates.size()];
    final double[] v = new double[dfs.size()];
    for (int i = 0; i < d.length; i++) {
      d[i] = Dates.parse((String) dates.get(i));
      v[i] = (Double) dfs.get(i);
    }
    return DiscountCurve.fromDiscountFactors(today(), d, v, Interpolation.LINEAR_LOG_DF, 0.0);
  }

  /** Java instruments for a golden curve record. */
  public static List<CurveInstrument> instruments(final String id) {
    final Map<String, Object> c = curveRecord(id);
    final String ccy = str(c, "currency");
    final int today = today();
    final List<CurveInstrument> out = new ArrayList<>();
    for (final Map<String, Object> r : list(c, "instruments")) {
      final String kind = str(r, "kind");
      final double q = num(r, "quote");
      final String tenor = str(r, "tenor");
      switch (kind) {
        case "fixing", "meeting" ->
            out.add(
                new PeriodRateInstrument(
                    str(r, "label"), tenor, date(r, "start"), date(r, "end"),
                    ccy.equals("GBP") ? DayCount.ACT_365F : DayCount.ACT_360, q));
        case "future3m" ->
            out.add(
                OisFutureInstrument.threeMonth(
                    str(r, "label"), integer(r, "year"), integer(r, "month"), Calendars.US_SOFR,
                    DayCount.ACT_360, num(r, "convexity"), q));
        case "ois" ->
            out.add(
                new OisSwapInstrument(
                    SwapConvention.forCurrency(ccy), today, integer(r, "tenor_months"), tenor, q));
        case "term_swap" ->
            out.add(
                new TermSwapInstrument(
                    str(r, "label"), tenor, today, 2, integer(r, "tenor_months"),
                    Calendars.TARGET, 12, DayCount.THIRTY_E_360, 6, q));
        default -> throw new IllegalArgumentException("unknown instrument kind " + kind);
      }
    }
    return out;
  }

  private static final Map<String, CurveBuilder> BUILDERS = new HashMap<>();

  /** A Java bootstrap of a golden curve (linear log DF, sequential), cached. */
  public static synchronized CurveBuilder javaBuilder(final String id) {
    final CurveBuilder existing = BUILDERS.get(id);
    if (existing != null) {
      return existing;
    }
    final Map<String, Object> c = curveRecord(id);
    final Object disc = c.get("discount");
    final DiscountCurve discount = disc == null ? null : javaBuilder((String) disc).curve();
    final CurveBuilder b =
        new CurveBuilder(
            today(), instruments(id), Interpolation.LINEAR_LOG_DF, 0.0, discount,
            fixings(str(c, "currency")));
    b.build(CurveBuilder.Method.AUTO);
    BUILDERS.put(id, b);
    return b;
  }
}
