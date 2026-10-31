package io.ratesalgo.api;

/**
 * Lookup of instrument static data by id, with a dense index per instrument so that per-instrument
 * state can live in arrays. Implemented by the coordinator over P1's securities master; {@link
 * InMemoryInstrumentDirectory} is the stub. Lookups never allocate.
 */
public interface InstrumentDirectory {

  /** The instrument with this id, or null. */
  Instrument byId(int instrumentId);

  /** Dense index in {@code [0, size())}, or -1 if unknown. */
  int indexOf(int instrumentId);

  /** The instrument at a dense index. */
  Instrument byIndex(int index);

  /** Number of instruments. */
  int size();
}
