package io.ratesalgo.posttrade;

import io.ratesalgo.posttrade.chaos.SyntheticDay;
import io.ratesalgo.posttrade.position.InstrumentCatalogue;
import io.ratesalgo.posttrade.report.EodReportMain;
import io.ratesalgo.posttrade.trade.FixedBookMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** Shared fixture data for the post-trade tests (src/test/resources/fixture). */
public final class Fixtures {

  /** 2026-10-30 09:00 UTC. */
  public static final long NINE = SyntheticDay.MIDNIGHT + 9 * 3_600_000_000_000L;

  private Fixtures() {}

  public static Path path(final String name) {
    try {
      return Path.of(Fixtures.class.getResource("/fixture/" + name).toURI());
    } catch (final URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }

  public static Reader reader(final String name) {
    final InputStream in = Fixtures.class.getResourceAsStream("/fixture/" + name);
    if (in == null) {
      throw new IllegalStateException("missing fixture " + name);
    }
    return new InputStreamReader(in, StandardCharsets.UTF_8);
  }

  public static InstrumentCatalogue catalogue() {
    try {
      return EodReportMain.readInstruments(path("instruments.csv"));
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static FixedBookMapper books() {
    try {
      return EodReportMain.readBooks(path("books.csv"));
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** A Decimal9 mantissa from a decimal string. */
  public static long px(final String decimal) {
    return new java.math.BigDecimal(decimal).movePointRight(9).longValueExact();
  }
}
