package io.ratesalgo.analytics.math;

/**
 * Dense LU decomposition with partial pivoting into preallocated storage, for the small square
 * systems of the bootstrap (one row per curve instrument). {@link #factor} and the two solves do
 * not allocate.
 */
public final class LuSolver {

  private final int capacity;
  private final double[] lu;
  private final int[] pivot;
  private int n;

  /** Allocates storage for systems up to {@code capacity} by {@code capacity}. */
  public LuSolver(final int capacity) {
    this.capacity = capacity;
    this.lu = new double[capacity * capacity];
    this.pivot = new int[capacity];
  }

  /**
   * Factors the n by n row-major matrix {@code a}; {@code a} is not modified.
   *
   * @throws IllegalStateException if the matrix is singular to working precision (a pivot below
   *     1e-300 in absolute value)
   */
  public void factor(final double[] a, final int size) {
    if (size > capacity) {
      throw new IllegalArgumentException("size " + size + " exceeds capacity " + capacity);
    }
    this.n = size;
    System.arraycopy(a, 0, lu, 0, size * size);
    for (int k = 0; k < size; k++) {
      int p = k;
      double max = Math.abs(lu[k * size + k]);
      for (int i = k + 1; i < size; i++) {
        final double v = Math.abs(lu[i * size + k]);
        if (v > max) {
          max = v;
          p = i;
        }
      }
      if (max < 1e-300) {
        throw new IllegalStateException("singular matrix at column " + k);
      }
      pivot[k] = p;
      if (p != k) {
        for (int j = 0; j < size; j++) {
          final double t = lu[k * size + j];
          lu[k * size + j] = lu[p * size + j];
          lu[p * size + j] = t;
        }
      }
      final double inv = 1.0 / lu[k * size + k];
      for (int i = k + 1; i < size; i++) {
        final double l = lu[i * size + k] * inv;
        lu[i * size + k] = l;
        if (l != 0.0) {
          for (int j = k + 1; j < size; j++) {
            lu[i * size + j] -= l * lu[k * size + j];
          }
        }
      }
    }
  }

  /** Solves A x = b in place in {@code b} for the factored A. */
  public void solve(final double[] b) {
    for (int k = 0; k < n; k++) {
      final int p = pivot[k];
      if (p != k) {
        final double t = b[k];
        b[k] = b[p];
        b[p] = t;
      }
    }
    for (int i = 1; i < n; i++) {
      double s = b[i];
      for (int j = 0; j < i; j++) {
        s -= lu[i * n + j] * b[j];
      }
      b[i] = s;
    }
    for (int i = n - 1; i >= 0; i--) {
      double s = b[i];
      for (int j = i + 1; j < n; j++) {
        s -= lu[i * n + j] * b[j];
      }
      b[i] = s / lu[i * n + i];
    }
  }

  /** Solves A^T x = b in place in {@code b} for the factored A. */
  public void solveTransposed(final double[] b) {
    // A = P^T L U, so A^T = U^T L^T P; solve U^T w = b, L^T v = w, x = P^T v
    for (int i = 0; i < n; i++) {
      double s = b[i];
      for (int j = 0; j < i; j++) {
        s -= lu[j * n + i] * b[j];
      }
      b[i] = s / lu[i * n + i];
    }
    for (int i = n - 1; i >= 0; i--) {
      double s = b[i];
      for (int j = i + 1; j < n; j++) {
        s -= lu[j * n + i] * b[j];
      }
      b[i] = s;
    }
    for (int k = n - 1; k >= 0; k--) {
      final int p = pivot[k];
      if (p != k) {
        final double t = b[k];
        b[k] = b[p];
        b[p] = t;
      }
    }
  }
}
