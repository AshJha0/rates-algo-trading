package io.ratesalgo.analytics.bond;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.analytics.date.Dates;
import io.ratesalgo.analytics.golden.Golden;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Treasury-published worked examples: the 2024 auction results (TreasuryDirect, data/fixtures/
 * ust_auctions_2024.csv). For each note and bond the Treasury publishes the high yield (three
 * decimals, percent) and the price per 100 it implies under 31 CFR 356 Appendix B (six decimals);
 * for each bill the high discount rate and the price. The checks: price from the published yield
 * rounds to the published price, and the yield from the published price rounds to the published
 * yield (the last published digit both ways).
 */
class PublishedExamplesTest {

  static List<String[]> rows() throws IOException {
    final List<String> lines =
        Files.readAllLines(
            Golden.repoRoot().resolve(Path.of("data", "fixtures", "ust_auctions_2024.csv")),
            StandardCharsets.UTF_8);
    return lines.subList(1, lines.size()).stream().map(l -> l.split(",", -1)).toList();
  }

  /** Original term in years from the term label: "10-Year", "9-Year 10-Month" (a reopening). */
  static int originalYears(final String term) {
    final int years = Integer.parseInt(term.substring(0, term.indexOf('-')));
    return term.contains("Month") ? years + 1 : years;
  }

  @Test
  void notesAndBondsReproducePublishedPriceAndYield() throws IOException {
    final BondPricer p = new BondPricer();
    int checked = 0;
    int priceMatches = 0;
    int yieldMatches = 0;
    double maxPriceDiff = 0.0;
    for (final String[] r : rows()) {
      if (!(r[1].equals("Note") || r[1].equals("Bond")) || r[6].isEmpty() || r[7].isEmpty()
          || r[8].isEmpty()) {
        continue;
      }
      final int issue = Dates.parse(r[4]);
      final int maturity = Dates.parse(r[5]);
      final int dated = Dates.addYears(maturity, -originalYears(r[2]));
      final double coupon = Double.parseDouble(r[6]) / 100.0;
      final double yield = Double.parseDouble(r[7]) / 100.0;
      final double published = Double.parseDouble(r[8]);
      final FixedRateBond b = new FixedRateBond(r[0], BondConvention.UST, coupon, dated,
          maturity);
      final double price = p.treasuryAuctionPrice(b, yield, issue);
      maxPriceDiff = Math.max(maxPriceDiff, Math.abs(price - published));
      // the Treasury rounds the dirty price and the accrued interest to six decimals each
      final long rounded = Math.round(p.treasuryAuctionDirtyPrice(b, yield, issue) * 1e6)
          - Math.round(p.accrued(b, issue) * 1e6);
      if (rounded == Math.round(published * 1e6)) {
        priceMatches++;
      }
      final double y = p.treasuryAuctionYield(b, published, issue);
      if (Math.round(y * 1e5) == Math.round(yield * 1e5)) {
        yieldMatches++;
      }
      checked++;
    }
    System.out.println("TREASURY notes and bonds checked " + checked + ", price to 6 dp "
        + priceMatches + ", yield to 3 dp " + yieldMatches + ", max |price diff| "
        + maxPriceDiff);
    assertTrue(checked >= 60, "too few auction rows: " + checked);
    assertEquals(checked, yieldMatches, "yield from the published price, three decimals");
    assertEquals(checked, priceMatches, "price from the published yield, six decimals");
  }

  @Test
  void billsReproducePublishedPriceFromDiscountRate() throws IOException {
    int checked = 0;
    for (final String[] r : rows()) {
      if (!r[1].equals("Bill") || r[8].isEmpty() || r[9].isEmpty()) {
        continue;
      }
      final int issue = Dates.parse(r[4]);
      final int maturity = Dates.parse(r[5]);
      final double rate = Double.parseDouble(r[9]) / 100.0;
      final double published = Double.parseDouble(r[8]);
      final double price = TreasuryBill.price(rate, issue, maturity);
      assertEquals(Math.round(published * 1e6), Math.round(price * 1e6),
          r[0] + " " + r[2] + " " + price + " vs " + published);
      assertEquals(rate, TreasuryBill.discountRate(price, issue, maturity), 1e-14);
      checked++;
    }
    System.out.println("TREASURY bills checked " + checked);
    assertTrue(checked >= 300);
  }

  @Test
  void billInvestmentRateFollowsAppendixB() {
    // 13-week: (100 - P)/P * y/r; the year basis is 366 when 29 Feb 2028 lies ahead
    final int s = Dates.of(2024, 1, 4);
    final int m = Dates.of(2024, 4, 4);
    final double p = TreasuryBill.price(0.05245, s, m);
    assertEquals((100 - p) / p * 366.0 / 91.0, TreasuryBill.investmentRate(p, s, m), 1e-15);
    assertEquals(365, TreasuryBill.yearBasis(Dates.of(2024, 3, 1)));
    // 52-week: the quadratic root reprices the bill on the semi-annual coupon-equivalent basis
    final int s2 = Dates.of(2024, 3, 7);
    final int m2 = Dates.of(2025, 3, 6);
    final double p2 = TreasuryBill.price(0.0475, s2, m2);
    final double i = TreasuryBill.investmentRate(p2, s2, m2);
    final double r = m2 - s2;
    final double y = TreasuryBill.yearBasis(s2);
    final double back = 100.0 / ((1 + (r - y / 2) / y * i) * (1 + i / 2));
    assertEquals(p2, back, 1e-9);
  }
}
