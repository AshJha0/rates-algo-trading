package io.ratesalgo.posttrade.trade;

/**
 * Where a trade is booked and which algorithm produced it.
 *
 * @param book trading book id
 * @param account account id within the book (the legal-entity or clearing account)
 * @param algorithmId the algorithm inventory id (docs/compliance/ALGORITHM_INVENTORY_TEMPLATE.md),
 *     written to the order record's execution-within-firm field
 */
public record BookAssignment(int book, int account, String algorithmId) {

  public BookAssignment {
    if (algorithmId == null || algorithmId.isEmpty()) {
      throw new IllegalArgumentException("algorithm id required for book " + book);
    }
  }
}
