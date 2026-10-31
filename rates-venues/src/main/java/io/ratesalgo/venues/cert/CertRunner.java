package io.ratesalgo.venues.cert;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.fix.codec.FixParser;
import io.ratesalgo.fix.codec.FixWriter;
import io.ratesalgo.fix.codec.MsgType;
import io.ratesalgo.fix.session.FixSession;
import io.ratesalgo.venues.Venues;
import io.ratesalgo.venues.clob.ClobSimulator;
import io.ratesalgo.venues.d2c.ClientDecision;
import io.ratesalgo.venues.d2c.D2cRfqSimulator;
import io.ratesalgo.venues.d2c.RfqSpec;
import io.ratesalgo.venues.feed.InProcessFeedChannel;
import io.ratesalgo.venues.sef.SefRfqSpec;
import io.ratesalgo.venues.sef.SefSimulator;
import io.ratesalgo.venues.sim.EngineSession;
import io.ratesalgo.venues.sim.Fix;
import io.ratesalgo.venues.sim.Json;
import io.ratesalgo.venues.sim.Px;
import io.ratesalgo.venues.sim.VenueHarness;
import io.ratesalgo.venues.sim.VenueSimulator;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs a certification script. The script lists steps; the runner starts the named simulator with
 * a fresh seeded state, connects the engine-side session (our codec) as {@code RALGO}, and for
 * each step sends a message, acts on the venue, moves time, or checks the next inbound message
 * against the expected tags. After every action it waits until both sides have processed all
 * messages, so each check sees exactly the messages the step caused.
 *
 * <p>Step kinds (API_SPEC.md Section 10.6): {@code send} (tag to value, first key 35), {@code
 * expect} (tag to value; {@code "*"} means present, null means absent), {@code expectNone},
 * {@code venue} (action and arguments), {@code advance} (milliseconds), {@code do} ({@code
 * disconnect}, {@code reconnect}). Values starting with {@code $} take that tag from the newest
 * received message carrying it; {@code @now+N} is a timestamp N milliseconds ahead.
 */
public final class CertRunner {

  private final Map<String, Object> script;
  private final List<Map<Integer, String>> inbox = new ArrayList<>();
  private final List<String> raw = new ArrayList<>();
  private final List<String> failures = new ArrayList<>();
  private int consumed;
  private int expectations;
  private long lastRfq;

  private final io.ratesalgo.fix.session.FixSessionHandler handler =
      new io.ratesalgo.fix.session.FixSessionHandler() {
        @Override
        public void onApplicationMessage(final FixSession s, final FixParser m, final boolean possDup) {
          record(m);
        }

        @Override
        public void onSessionReject(final FixSession s, final FixParser m) {
          record(m);
        }
      };

  private CertRunner(final Map<String, Object> script) {
    this.script = script;
  }

  /** Loads a script shipped on the class path under {@code /io/ratesalgo/venues/cert/}. */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> load(final String name) {
    try (InputStream in = CertRunner.class.getResourceAsStream("/io/ratesalgo/venues/cert/" + name)) {
      if (in == null) {
        throw new IllegalArgumentException("no certification script " + name);
      }
      return (Map<String, Object>) Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Runs a script by name. */
  public static CertResult run(final String name) {
    return new CertRunner(load(name)).execute(name);
  }

  /** Runs a parsed script. */
  public static CertResult run(final String name, final Map<String, Object> script) {
    return new CertRunner(script).execute(name);
  }

  private CertResult execute(final String name) {
    final String kind = Json.str(script, "venue");
    final ManualClock clock = new ManualClock(Venues.T0);
    final long seed = script.containsKey("seed") ? Json.num(script, "seed").longValue() : 1;
    final VenueSimulator sim = Venues.create(kind, clock, seed, new InProcessFeedChannel());
    final List<Object> steps = Json.list(script, "steps");
    int executed = 0;
    long sequenceErrors;
    try (VenueHarness h = new VenueHarness(sim, clock)) {
      EngineSession engine = h.connect("RALGO", handler);
      long carried = 0;
      for (final Object step : steps) {
        executed++;
        @SuppressWarnings("unchecked")
        final Map<String, Object> s = (Map<String, Object>) step;
        try {
          engine = step(s, h, engine, sim, executed);
        } catch (RuntimeException e) {
          failures.add("step " + executed + " " + s + ": " + e);
          break;
        }
        carried = Math.max(carried, engine.session().stats().sequenceErrors());
      }
      sequenceErrors = Math.max(carried, engine.session().stats().sequenceErrors());
      engine.close();
    }
    return new CertResult(name, kind, Json.str(script, "flow"), executed, expectations, failures, sequenceErrors);
  }

  private void record(final FixParser m) {
    final Map<Integer, String> fields = new LinkedHashMap<>();
    for (int i = 0; i < m.fieldCount(); i++) {
      fields.putIfAbsent(m.tagAt(i), m.getStringAt(i));
    }
    inbox.add(fields);
    raw.add(m.toString());
  }

  private String resolve(final String value, final ManualClock clock) {
    if ("$rfq".equals(value)) {
      return Long.toString(lastRfq);
    }
    if (value.startsWith("$")) {
      final int tag = Integer.parseInt(value.substring(1));
      for (int i = inbox.size() - 1; i >= 0; i--) {
        final String v = inbox.get(i).get(tag);
        if (v != null) {
          return v;
        }
      }
      throw new IllegalStateException("no received message carries tag " + tag);
    }
    if (value.startsWith("@now+")) {
      final quickfix.Message tmp = new quickfix.Message();
      Fix.time(tmp, 60, clock.epochNanos() + Long.parseLong(value.substring(5)) * 1_000_000L);
      return Fix.get(tmp, 60);
    }
    return value;
  }

  @SuppressWarnings("unchecked")
  private EngineSession step(final Map<String, Object> s, final VenueHarness h, final EngineSession engine,
      final VenueSimulator sim, final int index) {
    if (s.containsKey("send")) {
      final Map<String, Object> fields = (Map<String, Object>) s.get("send");
      final FixWriter w = engine.session().begin(MsgType.fromText(fields.get("35").toString()));
      for (final Map.Entry<String, Object> f : fields.entrySet()) {
        if ("35".equals(f.getKey())) {
          continue;
        }
        final int tag = Integer.parseInt(f.getKey());
        if (f.getValue() instanceof List<?> values) {
          for (final Object v : values) {
            w.putText(tag, resolve(String.valueOf(v), h.clock()));
          }
        } else {
          w.putText(tag, resolve(String.valueOf(f.getValue()), h.clock()));
        }
      }
      engine.session().send();
      engine.quiesce();
    } else if (s.containsKey("expect")) {
      expectations++;
      engine.quiesce();
      if (consumed >= inbox.size()) {
        throw new IllegalStateException("expected a message, none arrived");
      }
      final Map<Integer, String> got = inbox.get(consumed);
      final String text = raw.get(consumed);
      consumed++;
      for (final Map.Entry<String, Object> e : ((Map<String, Object>) s.get("expect")).entrySet()) {
        final String actual = got.get(Integer.parseInt(e.getKey()));
        final Object want = e.getValue();
        final boolean ok =
            want == null
                ? actual == null
                : "*".equals(want) ? actual != null : resolveExpected(want.toString(), h).equals(actual);
        if (!ok) {
          failures.add("step " + index + ": tag " + e.getKey() + " expected " + want + " got " + actual + " in " + text);
        }
      }
    } else if (s.containsKey("expectNone")) {
      engine.quiesce();
      if (consumed < inbox.size()) {
        failures.add("step " + index + ": expected no message, got " + raw.get(consumed));
        consumed = inbox.size();
      }
    } else if (s.containsKey("advance")) {
      h.advance(Json.num(s, "advance").longValue() * 1_000_000L);
      engine.quiesce();
    } else if (s.containsKey("venue")) {
      venueAction((Map<String, Object>) s.get("venue"), sim, h);
      engine.quiesce();
    } else if (s.containsKey("do")) {
      final String what = Json.str(s, "do");
      if ("disconnect".equals(what)) {
        engine.disconnect();
        return engine;
      }
      if ("reconnect".equals(what)) {
        final EngineSession fresh = h.connect("RALGO", handler);
        fresh.quiesce();
        return fresh;
      }
      throw new IllegalArgumentException("unknown do: " + what);
    } else {
      throw new IllegalArgumentException("unknown step kind " + s.keySet());
    }
    return engine;
  }

  private String resolveExpected(final String want, final VenueHarness h) {
    if ("$rfq".equals(want)) {
      return Long.toString(lastRfq);
    }
    return want.startsWith("$") || want.startsWith("@") ? resolve(want, h.clock()) : want;
  }

  private static List<Long> prices(final Map<String, Object> a, final String key) {
    final List<Long> out = new ArrayList<>();
    if (a.containsKey(key)) {
      for (final Object o : Json.list(a, key)) {
        out.add(Px.price(o.toString()));
      }
    }
    return out;
  }

  private void venueAction(final Map<String, Object> a, final VenueSimulator sim, final VenueHarness h) {
    final String action = Json.str(a, "action");
    switch (action) {
      case "rfq" -> lastRfq =
          ((D2cRfqSimulator) sim)
              .injectRfq(
                  new RfqSpec(
                      Json.str(a, "instrument"),
                      "1".equals(Json.str(a, "side")),
                      Px.qty(Json.str(a, "qty")),
                      Json.num(a, "dealers").intValue(),
                      prices(a, "competitors"),
                      a.containsKey("client") ? Json.num(a, "client").intValue() : 2003,
                      ClientDecision.valueOf(a.containsKey("decision") ? Json.str(a, "decision") : "BEST"),
                      Boolean.TRUE.equals(a.get("informed"))));
      case "decide" -> {
        if (sim instanceof D2cRfqSimulator d2c) {
          d2c.decide(lastRfq);
        } else {
          ((SefSimulator) sim).decide(lastRfq);
        }
      }
      case "sefRfq" -> lastRfq =
          ((SefSimulator) sim)
              .injectRfq(
                  new SefRfqSpec(
                      Json.str(a, "instrument"),
                      "1".equals(Json.str(a, "side")),
                      Px.qty(Json.str(a, "qty")),
                      Json.num(a, "dealers").intValue(),
                      a.containsKey("client") ? Json.num(a, "client").intValue() : 2001,
                      prices(a, "competitors"),
                      !Boolean.FALSE.equals(a.get("accept"))));
      case "internal" -> {
        final ClobSimulator clob = (ClobSimulator) sim;
        final int instrumentId =
            Venues.treasuries().stream()
                .filter(i -> i.securityId().equals(Json.str(a, "instrument")))
                .findFirst()
                .map(i -> i.instrumentId())
                .orElseGet(() -> Venues.swaps().stream()
                    .filter(i -> i.securityId().equals(Json.str(a, "instrument")))
                    .findFirst().orElseThrow().instrumentId());
        final long id =
            clob.submitInternal(
                Json.str(a, "owner"),
                instrumentId,
                "1".equals(Json.str(a, "side")),
                Px.price(Json.str(a, "price")),
                Px.qty(Json.str(a, "qty")),
                a.containsKey("display") ? Px.qty(Json.str(a, "display")) : 0);
        if (id < 0) {
          throw new IllegalStateException("internal order refused");
        }
      }
      case "bust" -> {
        if (!((ClobSimulator) sim).bust(Long.parseLong(resolve(Json.str(a, "execId"), h.clock())))) {
          throw new IllegalStateException("bust of unknown execution");
        }
      }
      case "duplicateNextFill" -> ((ClobSimulator) sim).duplicateNextFill();
      case "reverseNextFills" -> ((ClobSimulator) sim).reverseNextFills();
      case "lateFillOnNextCancel" -> ((ClobSimulator) sim).lateFillOnNextCancel();
      default -> throw new IllegalArgumentException("unknown venue action " + action);
    }
  }
}
