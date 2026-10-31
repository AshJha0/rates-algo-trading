package io.ratesalgo.refdata;

/**
 * One party.
 *
 * @param partyId id used on the wire ({@code PartyId} in the SBE schema)
 * @param code short code used as FIX PartyID or CompID
 * @param name display name
 * @param type party type
 * @param tier client tier, 1 best; 0 for non-clients
 */
public record Party(int partyId, String code, String name, PartyType type, int tier) {}
