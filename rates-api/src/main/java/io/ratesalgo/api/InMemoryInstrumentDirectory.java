package io.ratesalgo.api;

import java.util.ArrayList;
import java.util.List;
import org.agrona.collections.Int2IntHashMap;

/** Stub securities master: instruments added at start-up, indexed in insertion order. */
public final class InMemoryInstrumentDirectory implements InstrumentDirectory {

  private final List<Instrument> instruments = new ArrayList<>();
  private final Int2IntHashMap index = new Int2IntHashMap(64, 0.5f, -1);

  /** Adds an instrument and returns its dense index. Duplicate ids are rejected. */
  public int add(final Instrument instrument) {
    if (index.get(instrument.id()) != -1) {
      throw new IllegalArgumentException("duplicate instrument id " + instrument.id());
    }
    final int i = instruments.size();
    instruments.add(instrument);
    index.put(instrument.id(), i);
    return i;
  }

  @Override
  public Instrument byId(final int instrumentId) {
    final int i = index.get(instrumentId);
    return i < 0 ? null : instruments.get(i);
  }

  @Override
  public int indexOf(final int instrumentId) {
    return index.get(instrumentId);
  }

  @Override
  public Instrument byIndex(final int i) {
    return instruments.get(i);
  }

  @Override
  public int size() {
    return instruments.size();
  }
}
