package io.ratesalgo.backtest.model;

import java.util.Map;

/**
 * One entry of {@code data/presets.yaml}, following the schema in that file's header and
 * API_SPEC.md Section 16.2.
 *
 * @param name identifier used in code and reports
 * @param fittedFrom the manifest or label set the values came from, or "assumption"
 * @param method estimator and the script that ran it
 * @param sampleSize observations used
 * @param parameters fitted or declared parameters with standard errors
 * @param assumptions declared assumption bands (DR-008), keyed by parameter name
 */
public record Preset(
    String name,
    String fittedFrom,
    String method,
    long sampleSize,
    Map<String, Parameter> parameters,
    Map<String, Band> assumptions) {

  /** A parameter value and its standard error; NaN standard error means none was estimated. */
  public record Parameter(double value, double standardError) {}

  /** A declared assumption band: low, central and high values of one parameter. */
  public record Band(double low, double central, double high, String rationale) {

    public Band {
      if (!(low <= central && central <= high) && !(low >= central && central >= high)) {
        throw new IllegalArgumentException(
            "band is not ordered: low=" + low + " central=" + central + " high=" + high);
      }
    }

    public double at(final BandPoint point) {
      return switch (point) {
        case LOW -> low;
        case CENTRAL -> central;
        case HIGH -> high;
      };
    }
  }

  public Preset {
    parameters = Map.copyOf(parameters);
    assumptions = Map.copyOf(assumptions);
  }

  /** The value of a parameter; throws if the preset does not declare it. */
  public double value(final String parameter) {
    final Parameter p = parameters.get(parameter);
    if (p == null) {
      throw new IllegalArgumentException("preset " + name + " has no parameter " + parameter);
    }
    return p.value();
  }

  public double value(final String parameter, final double fallback) {
    final Parameter p = parameters.get(parameter);
    return p == null ? fallback : p.value();
  }

  public Band band(final String parameter) {
    final Band b = assumptions.get(parameter);
    if (b == null) {
      throw new IllegalArgumentException("preset " + name + " declares no band for " + parameter);
    }
    return b;
  }
}
