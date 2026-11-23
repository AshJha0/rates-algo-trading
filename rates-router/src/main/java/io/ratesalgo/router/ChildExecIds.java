package io.ratesalgo.router;

import java.util.Arrays;

/**
 * The execution ids booked to the children of one {@link ChildOrderBook}, each held for as long as
 * its child holds a slot in the book.
 *
 * <p>That is the whole window dedupe needs: a report for a child that has left the book is unknown
 * and books nothing, whatever its execution id, so an id is only worth keeping while its child can
 * still be found. Ids are pooled entries, chained per child slot and indexed by an open-addressing
 * table with linear probing; freeing a slot unlinks its chain and removes each id by backward shift,
 * so the table never fills with deleted markers. Everything is sized at construction and nothing
 * allocates afterwards. The layout is a pure function of the calls made, in order (a fixed
 * multiplicative hash, a last-in-first-out free list), so a book rebuilt by replaying its journal
 * makes the same decisions the live book made. Single-threaded.
 */
final class ChildExecIds {

  private static final int EMPTY = -1;
  private static final long GOLDEN = 0x9E3779B97F4A7C15L;

  private final long[] key;
  // per entry: the next entry of the same child, or the next free entry
  private final int[] next;
  private final int[] head;
  private final int[] table;
  private final int mask;
  private final int shift;
  private final int entries;
  private int free;
  private int size;

  /**
   * @param slots child slots in the book
   * @param entries most execution ids held at once, across all children
   */
  ChildExecIds(final int slots, final int entries) {
    if (slots < 1 || entries < 1 || entries > 1 << 28) {
      throw new IllegalArgumentException("slots and entries out of range: " + slots + ", " + entries);
    }
    this.entries = entries;
    key = new long[entries];
    next = new int[entries];
    for (int e = 0; e < entries; e++) {
      next[e] = e + 1 < entries ? e + 1 : EMPTY;
    }
    free = 0;
    head = new int[slots];
    Arrays.fill(head, EMPTY);
    // at most half full, so a probe run stays short
    final int length = Integer.highestOneBit(entries) << 2;
    table = new int[length];
    Arrays.fill(table, EMPTY);
    mask = length - 1;
    shift = 64 - Integer.numberOfTrailingZeros(length);
  }

  /** True if the id is held for any child. */
  boolean contains(final long k) {
    int i = home(k);
    while (true) {
      final int e = table[i];
      if (e == EMPTY) {
        return false;
      }
      if (key[e] == k) {
        return true;
      }
      i = (i + 1) & mask;
    }
  }

  /**
   * Holds an id for a child. The caller has checked that it is not held already.
   *
   * @return false, holding nothing, if every entry is in use
   */
  boolean add(final int slot, final long k) {
    final int e = free;
    if (e == EMPTY) {
      return false;
    }
    free = next[e];
    key[e] = k;
    next[e] = head[slot];
    head[slot] = e;
    int i = home(k);
    while (table[i] != EMPTY) {
      i = (i + 1) & mask;
    }
    table[i] = e;
    size++;
    return true;
  }

  /** Forgets every id held for a child, returning its entries to the pool. */
  void clear(final int slot) {
    int e = head[slot];
    while (e != EMPTY) {
      final int n = next[e];
      remove(e);
      next[e] = free;
      free = e;
      size--;
      e = n;
    }
    head[slot] = EMPTY;
  }

  /** Ids held now. */
  int size() {
    return size;
  }

  /** Most ids held at once. */
  int entries() {
    return entries;
  }

  private void remove(final int e) {
    int i = home(key[e]);
    while (table[i] != e) {
      i = (i + 1) & mask;
    }
    // backward shift: pull later members of the run into the hole when the hole lies between
    // their home and where they sit, so every remaining id stays reachable from its home
    int j = i;
    while (true) {
      j = (j + 1) & mask;
      final int f = table[j];
      if (f == EMPTY) {
        break;
      }
      if (((j - home(key[f])) & mask) >= ((j - i) & mask)) {
        table[i] = f;
        i = j;
      }
    }
    table[i] = EMPTY;
  }

  private int home(final long k) {
    return (int) ((k * GOLDEN) >>> shift);
  }
}
