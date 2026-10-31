package io.ratesalgo.hedge;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;

/**
 * The projected-gradient solver against a brute-force reference: for every assignment of each
 * variable to its lower bound, its upper bound or free (3^n cases), solve the free variables'
 * linear system, keep feasible points, and take the smallest objective. For a convex problem that
 * is the global minimum. Tolerance: objective within 1e-7 relative to max(1, |f*|); solution
 * within 1e-4 per variable (the problems are strictly convex).
 */
class BoxQpSolverTest {

  @Property(tries = 400)
  void matchesBruteForce(@ForAll @IntRange(min = 1, max = 6) final int n, @ForAll @LongRange(min = 0, max = 1_000_000) final long seed) {
    final SplittableRandom rnd = new SplittableRandom(seed);
    final double[][] a = new double[n + 2][n];
    for (final double[] row : a) {
      for (int j = 0; j < n; j++) {
        row[j] = rnd.nextDouble(-3.0, 3.0);
      }
    }
    final double[][] q = new double[n][n];
    for (int i = 0; i < n; i++) {
      for (int j = 0; j < n; j++) {
        double s = 0.0;
        for (final double[] row : a) {
          s += row[i] * row[j];
        }
        q[i][j] = s + (i == j ? 0.05 : 0.0);
      }
    }
    final double[] c = new double[n];
    final double[] lo = new double[n];
    final double[] hi = new double[n];
    for (int i = 0; i < n; i++) {
      c[i] = rnd.nextDouble(-20.0, 20.0);
      lo[i] = rnd.nextBoolean() ? 0.0 : -rnd.nextDouble(0.1, 5.0);
      hi[i] = lo[i] + rnd.nextDouble(0.1, 5.0);
    }
    final double[] x = new double[n];
    final BoxQpSolver solver = new BoxQpSolver(n, 1e-10, 200_000);
    assertTrue(solver.solve(q, c, lo, hi, x), "converged, residual " + solver.residual());
    final double[] ref = bruteForce(q, c, lo, hi);
    final double fRef = BoxQpSolver.objective(q, c, ref);
    assertEquals(fRef, BoxQpSolver.objective(q, c, x), 1e-7 * Math.max(1.0, Math.abs(fRef)));
    assertArrayEquals(ref, x, 1e-4);
    for (int i = 0; i < n; i++) {
      assertTrue(x[i] >= lo[i] && x[i] <= hi[i]);
    }
  }

  @Test
  void linearObjectiveGoesToTheBounds() {
    final BoxQpSolver s = new BoxQpSolver(3, 1e-9, 10);
    final double[] x = {5.0, 5.0, 0.5};
    assertTrue(s.solve(new double[3][3], new double[] {1.0, -1.0, 0.0}, new double[] {0, 0, 0}, new double[] {1, 2, 3}, x));
    assertArrayEquals(new double[] {0.0, 2.0, 0.5}, x, 0.0);
    assertEquals(0, s.iterations());
    assertEquals(0.0, s.residual(), 0.0);
  }

  @Test
  void rejectsBadInputsAndReportsNonConvergence() {
    assertThrows(IllegalArgumentException.class, () -> new BoxQpSolver(-1, 1e-9, 10));
    assertTrue(new BoxQpSolver(0, 1e-9, 10).solve(new double[0][0], new double[0], new double[0], new double[0], new double[0]));
    assertThrows(IllegalArgumentException.class, () -> new BoxQpSolver(2, 0.0, 10));
    assertThrows(IllegalArgumentException.class, () -> new BoxQpSolver(2, 1e-9, 0));
    final BoxQpSolver s = new BoxQpSolver(1, 1e-9, 10);
    assertThrows(IllegalArgumentException.class, () -> s.solve(new double[][] {{1}}, new double[] {0}, new double[] {1}, new double[] {0}, new double[1]));
    final BoxQpSolver once = new BoxQpSolver(2, 1e-15, 1);
    final double[][] q = {{1000.0, 999.0}, {999.0, 1000.0}};
    assertTrue(!once.solve(q, new double[] {-1.0, 1.0}, new double[] {-1e3, -1e3}, new double[] {1e3, 1e3}, new double[2]));
    assertEquals(1, once.iterations());
  }

  static double[] bruteForce(final double[][] q, final double[] c, final double[] lo, final double[] hi) {
    final int n = c.length;
    double best = Double.MAX_VALUE;
    double[] bestX = null;
    int cases = 1;
    for (int i = 0; i < n; i++) {
      cases *= 3;
    }
    for (int code = 0; code < cases; code++) {
      final int[] state = new int[n];
      int v = code;
      for (int i = 0; i < n; i++) {
        state[i] = v % 3;
        v /= 3;
      }
      final double[] x = new double[n];
      int free = 0;
      for (int i = 0; i < n; i++) {
        if (state[i] == 0) {
          x[i] = lo[i];
        } else if (state[i] == 1) {
          x[i] = hi[i];
        } else {
          free++;
        }
      }
      if (free > 0) {
        final int[] idx = new int[free];
        int f = 0;
        for (int i = 0; i < n; i++) {
          if (state[i] == 2) {
            idx[f++] = i;
          }
        }
        final double[][] m = new double[free][free + 1];
        for (int r = 0; r < free; r++) {
          final int i = idx[r];
          double rhs = -c[i];
          for (int j = 0; j < n; j++) {
            if (state[j] != 2) {
              rhs -= q[i][j] * x[j];
            }
          }
          for (int s = 0; s < free; s++) {
            m[r][s] = q[i][idx[s]];
          }
          m[r][free] = rhs;
        }
        final double[] sol = gauss(m);
        if (sol == null) {
          continue;
        }
        boolean feasible = true;
        for (int r = 0; r < free; r++) {
          x[idx[r]] = sol[r];
          feasible &= sol[r] >= lo[idx[r]] - 1e-12 && sol[r] <= hi[idx[r]] + 1e-12;
        }
        if (!feasible) {
          continue;
        }
      }
      final double obj = BoxQpSolver.objective(q, c, x);
      if (obj < best) {
        best = obj;
        bestX = x;
      }
    }
    return bestX;
  }

  private static double[] gauss(final double[][] m) {
    final int n = m.length;
    for (int col = 0; col < n; col++) {
      int piv = col;
      for (int r = col + 1; r < n; r++) {
        if (Math.abs(m[r][col]) > Math.abs(m[piv][col])) {
          piv = r;
        }
      }
      if (Math.abs(m[piv][col]) < 1e-14) {
        return null;
      }
      final double[] t = m[col];
      m[col] = m[piv];
      m[piv] = t;
      for (int r = 0; r < n; r++) {
        if (r != col) {
          final double f = m[r][col] / m[col][col];
          for (int k = col; k <= n; k++) {
            m[r][k] -= f * m[col][k];
          }
        }
      }
    }
    final double[] x = new double[n];
    for (int i = 0; i < n; i++) {
      x[i] = m[i][n] / m[i][i];
    }
    return x;
  }
}
