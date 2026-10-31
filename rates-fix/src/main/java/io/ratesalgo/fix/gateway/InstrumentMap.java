package io.ratesalgo.fix.gateway;

import io.ratesalgo.fix.codec.FixParser;
import java.nio.charset.StandardCharsets;
import org.agrona.collections.Int2ObjectHashMap;
import org.agrona.collections.Long2LongHashMap;

/**
 * Maps FIX SecurityID text (ISIN, SecurityIDSource 4) to instrument ids and back. Lookups hash the
 * value in place with FNV-1a 64 and do not allocate; registration refuses a hash collision, so a
 * lookup hit is exact for every registered identifier.
 */
public final class InstrumentMap {

  private final Long2LongHashMap idByHash = new Long2LongHashMap(1024, 0.5f, 0L);
  private final Int2ObjectHashMap<byte[]> securityIdById = new Int2ObjectHashMap<>();

  /** Registers an instrument; ids must be positive and identifiers unique. */
  public InstrumentMap register(final int instrumentId, final String securityId) {
    if (instrumentId <= 0) {
      throw new IllegalArgumentException("instrument id must be positive: " + instrumentId);
    }
    final byte[] bytes = securityId.getBytes(StandardCharsets.US_ASCII);
    final long hash = FixParser.hash(bytes);
    if (idByHash.containsKey(hash) || securityIdById.containsKey(instrumentId)) {
      throw new IllegalArgumentException(
          "duplicate instrument " + instrumentId + " or security id " + securityId);
    }
    idByHash.put(hash, instrumentId);
    securityIdById.put(instrumentId, bytes);
    return this;
  }

  /** Instrument id for the SecurityID value at a parser position, or 0 if unknown. */
  public int idAt(final FixParser parser, final int index) {
    return (int) idByHash.get(parser.hashAt(index));
  }

  /** SecurityID bytes of an instrument, or null if unknown. The array must not be modified. */
  public byte[] securityId(final int instrumentId) {
    return securityIdById.get(instrumentId);
  }

  /** Number of registered instruments. */
  public int size() {
    return securityIdById.size();
  }
}
