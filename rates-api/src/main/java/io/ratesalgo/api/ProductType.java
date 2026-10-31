package io.ratesalgo.api;

/** Product families the risk engine aggregates separately for basis and spread exposures. */
public enum ProductType {
  /** Cash government bond, quoted in price per 100 of face. */
  BOND(0),
  /** Bond or rate future, quoted in price per 100 of face of the contract. */
  FUTURE(1),
  /** Interest rate swap, quoted as a par rate in percent; the instrument is the payer swap. */
  SWAP(2);

  /** Number of product types. */
  public static final int COUNT = 3;

  private final int index;

  ProductType(final int index) {
    this.index = index;
  }

  /** Dense index in {@code [0, COUNT)}. */
  public int index() {
    return index;
  }
}
