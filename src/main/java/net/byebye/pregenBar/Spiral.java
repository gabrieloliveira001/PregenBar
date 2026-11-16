package net.byebye.pregenBar;

/**
 * Closed-form square spiral: index 0 is the centre, ring k (k >= 1) holds the 8k cells at
 * Chebyshev distance k, indices (2k-1)^2 .. (2k+1)^2 - 1. Because the position is a pure function
 * of the index, progress is saved as a single number.
 */
public final class Spiral {

    private Spiral() {}

    /** Cells in a square spiral of the given ring radius. */
    public static long total(int radius) {
        long side = 2L * radius + 1;
        return side * side;
    }

    /** Offset {dx, dz} of the cell at {@code index}. */
    public static int[] at(long index) {
        if (index <= 0) return new int[] {0, 0};
        long k = ring(index);
        long m = index - (2 * k - 1) * (2 * k - 1);
        long side = m / (2 * k);
        long off = m % (2 * k);
        long x;
        long z;
        if (side == 0) {
            x = k;
            z = -k + 1 + off;
        } else if (side == 1) {
            x = k - 1 - off;
            z = k;
        } else if (side == 2) {
            x = -k;
            z = k - 1 - off;
        } else {
            x = -k + 1 + off;
            z = -k;
        }
        return new int[] {(int) x, (int) z};
    }

    /** Smallest k with (2k+1)^2 > index. */
    private static long ring(long index) {
        long k = (long) Math.ceil((Math.sqrt((double) index + 1) - 1) / 2);
        // Correct floating error either way.
        while (k > 1 && (2 * k - 1) * (2 * k - 1) > index) k--;
        while ((2 * k + 1) * (2 * k + 1) <= index) k++;
        return Math.max(1, k);
    }
}
