package io.ratesalgo.venues.sim;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Loads named presets from {@code presets.json} on the class path (API_SPEC.md Section 11.3). */
public final class Presets {

  private static final Object ROOT = load();

  private Presets() {}

  private static Object load() {
    try (InputStream in = Presets.class.getResourceAsStream("/io/ratesalgo/venues/presets.json")) {
      if (in == null) {
        throw new IllegalStateException("presets.json missing from the class path");
      }
      return Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The preset object for a venue kind ({@code d2c}, {@code clob}, {@code sef}) and name. */
  public static Map<String, Object> get(final String venue, final String name) {
    return Json.obj(Json.obj(ROOT, venue), name);
  }
}
