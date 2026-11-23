package io.ratesalgo.app.loop;

import io.ratesalgo.app.pricing.PricingBindings;
import io.ratesalgo.app.pricing.UsdUniverse;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Which version of each component one desk runs: the market it trades on, the pricing binding,
 * the strategy (through {@link DeskSettings}) and the router's execution model. {@link
 * #defaults} is the recorded configuration, the one every smoke figure, recipe and ledger
 * reproduction was made with; {@link #v2} is the four Round 3 to 6 components together. Each
 * component can also be switched alone, for ablation and for bringing them in one at a time.
 *
 * <p>The live loop and its replay must be built from equal profiles, or the replay hash differs.
 *
 * @param settings the desk's tunable parameters, with the strategy preset
 * @param pricing the pricing binding
 * @param router the router's execution model
 * @param market the venues and the universe
 * @param markoutLoad file the client markout store is read from when the desk is built, or null.
 *     It must not change while the session's journals may still be replayed: the desk a replay
 *     builds reads it again and has to find the same store
 * @param markoutSave file the store is written to by {@link DeskGroup#endSession}, or null. A
 *     different file from {@code markoutLoad}; the next session's start promotes it
 */
public record DeskProfile(
    DeskSettings settings,
    PricingBindings.Version pricing,
    RouterModel router,
    Market market,
    Path markoutLoad,
    Path markoutSave) {

  /** The router's execution model. */
  public enum RouterModel {
    /** The touch model with the global preset: what every recorded run routed with. */
    TOUCH,
    /** The ladder model: {@code RouterPresets.ladder()} with {@code ExecutionModel.defaults()}. */
    LADDER
  }

  /** The venues a desk trades on, and with them its universe. */
  public enum Market {
    /** The version 1 simulators: one D2C venue and one Treasury CLOB; four swap templates. */
    STANDARD,
    /**
     * Simulator v2: one D2C venue, two Treasury CLOBs showing shares of one book, a futures order
     * book; a swap at every risk tenor and the Treasury future.
     */
    SIM_V2
  }

  /** Validates the profile. */
  public DeskProfile {
    Objects.requireNonNull(settings, "settings");
    Objects.requireNonNull(pricing, "pricing");
    Objects.requireNonNull(router, "router");
    Objects.requireNonNull(market, "market");
    if (markoutLoad != null && markoutLoad.equals(markoutSave)) {
      throw new IllegalArgumentException("the markout store is saved to another file than it is loaded from: " + markoutLoad);
    }
  }

  /** The recorded configuration with these settings: version 1 of everything. */
  public static DeskProfile defaults(final DeskSettings settings) {
    return new DeskProfile(settings, PricingBindings.Version.V1, RouterModel.TOUCH, Market.STANDARD, null, null);
  }

  /** The recorded configuration with the default settings. */
  public static DeskProfile defaults() {
    return defaults(DeskSettings.defaults());
  }

  /**
   * The v2 desk: simulator v2's venues, pricing v2, strategy v2 exactly as {@link
   * DeskSettings#strategyV2()} declares it, and the ladder router. No recorded figure was made
   * with it.
   */
  public static DeskProfile v2() {
    return new DeskProfile(
        DeskSettings.strategyV2(), PricingBindings.Version.V2, RouterModel.LADDER, Market.SIM_V2, null, null);
  }

  /** The same profile with other settings (the strategy preset is part of them). */
  public DeskProfile withSettings(final DeskSettings other) {
    return new DeskProfile(other, pricing, router, market, markoutLoad, markoutSave);
  }

  /** The same profile with another pricing binding. */
  public DeskProfile withPricing(final PricingBindings.Version other) {
    return new DeskProfile(settings, other, router, market, markoutLoad, markoutSave);
  }

  /** The same profile with another router model. */
  public DeskProfile withRouter(final RouterModel other) {
    return new DeskProfile(settings, pricing, other, market, markoutLoad, markoutSave);
  }

  /** The same profile on another market. */
  public DeskProfile withMarket(final Market other) {
    return new DeskProfile(settings, pricing, router, other, markoutLoad, markoutSave);
  }

  /** The same profile with these markout files; either may be null. */
  public DeskProfile withMarkouts(final Path load, final Path save) {
    return new DeskProfile(settings, pricing, router, market, load, save);
  }

  /** The universe of this profile's market, built from the reference data. */
  public UsdUniverse newUniverse() {
    return market == Market.SIM_V2 ? UsdUniverse.simV2() : UsdUniverse.standard();
  }

  /** True when this is the recorded configuration apart from its settings. */
  public boolean isRecorded() {
    return pricing == PricingBindings.Version.V1 && router == RouterModel.TOUCH && market == Market.STANDARD;
  }
}
