package io.ratesalgo.cookbook;

import io.ratesalgo.analytics.bond.BondConvention;
import io.ratesalgo.analytics.bond.FixedRateBond;
import io.ratesalgo.analytics.date.Dates;
import io.ratesalgo.analytics.futures.BasisAnalytics;
import io.ratesalgo.analytics.futures.ConversionFactors;
import io.ratesalgo.analytics.futures.TreasuryFutureContract;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** COOKBOOK.md recipe 8: conversion factors, net basis, implied repo and the CTD. */
public final class Recipe08Ctd {

  private Recipe08Ctd() {}

  public static void main(final String[] args) {
    final TreasuryFutureContract ty = TreasuryFutureContract.TY;
    final int settle = Dates.of(2025, 1, 16);
    final int delivery = Dates.of(2025, 3, 31); // last delivery day of March 2025
    final double futures = 108.359375; // 108-11+ in 32nds
    final double repo = 0.0430;
    // coupon, dated date, maturity, clean price
    final double[][] basket = {
        {0.01375, Dates.of(2021, 11, 15), Dates.of(2031, 11, 15), 82.1546},
        {0.04125, Dates.of(2024, 11, 30), Dates.of(2031, 11, 30), 97.5543},
        {0.02750, Dates.of(2022, 8, 15), Dates.of(2032, 8, 15), 88.8734},
        {0.03500, Dates.of(2023, 2, 15), Dates.of(2033, 2, 15), 93.0604},
    };
    final BasisAnalytics a = new BasisAnalytics();
    final List<BasisAnalytics.Result> results = new ArrayList<>();
    final int first = Dates.of(2025, 3, 1);
    for (final double[] row : basket) {
      final int dated = (int) row[1];
      final int mat = (int) row[2];
      final boolean ok = ty.isDeliverable(ConversionFactors.wholeMonths(first, mat),
          ConversionFactors.wholeMonths(dated, mat));
      final FixedRateBond b = new FixedRateBond(Dates.format(mat), BondConvention.UST, row[0],
          dated, mat);
      final double cf = ConversionFactors.cme(row[0], mat, 2025, 3, ty);
      final BasisAnalytics.Result r = a.analyse(b, row[3], futures, cf, settle, delivery, repo);
      results.add(r);
      System.out.println(String.format(Locale.ROOT,
          "%5.3f%% %s deliverable=%b CF=%.4f gross=%.4f carry=%.4f net=%.4f repo=%.4f%%",
          100 * row[0], r.bond(), ok, cf, r.grossBasis(), r.carry(), r.netBasis(),
          100 * r.impliedRepo()));
    }
    System.out.println("CTD " + results.get(BasisAnalytics.cheapestToDeliver(results)).bond());
  }
}
