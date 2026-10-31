package io.ratesalgo.refdata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Parties by id and by code. */
public final class PartyMaster {

  private final Map<Integer, Party> byId = new HashMap<>();
  private final Map<String, Party> byCode = new HashMap<>();

  PartyMaster(final List<Party> parties) {
    for (final Party p : parties) {
      if (byId.put(p.partyId(), p) != null || byCode.put(p.code(), p) != null) {
        throw new RefDataException("duplicate party id or code: " + p);
      }
    }
  }

  /** Party by id. */
  public Optional<Party> byId(final int partyId) {
    return Optional.ofNullable(byId.get(partyId));
  }

  /** Party by code. */
  public Optional<Party> byCode(final String code) {
    return Optional.ofNullable(byCode.get(code));
  }

  /** All parties of a type, ordered by id. */
  public List<Party> ofType(final PartyType type) {
    final List<Party> out = new ArrayList<>();
    for (final Party p : byId.values()) {
      if (p.type() == type) {
        out.add(p);
      }
    }
    out.sort(Comparator.comparingInt(Party::partyId));
    return Collections.unmodifiableList(out);
  }
}
