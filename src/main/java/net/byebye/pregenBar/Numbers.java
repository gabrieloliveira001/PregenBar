package net.byebye.pregenBar;

import java.util.Locale;

/** Parses {@code 500}, {@code 2.5k}, {@code 1m}, {@code 1.2b}. */
public final class Numbers {

    private Numbers() {}

    /** Positive amount, or -1 when invalid. */
    public static long parse(String input) {
        long v = parseSigned(input);
        return v == Long.MIN_VALUE || v < 0 ? -1 : v;
    }

    /** Signed amount, or {@link Long#MIN_VALUE} when invalid. */
    public static long parseSigned(String input) {
        if (input == null) return Long.MIN_VALUE;
        String s = input.trim().toLowerCase(Locale.ROOT).replace(",", "").replace("_", "");
        if (s.isEmpty()) return Long.MIN_VALUE;
        double mult = 1;
        char last = s.charAt(s.length() - 1);
        if (last == 'k') mult = 1e3;
        else if (last == 'm') mult = 1e6;
        else if (last == 'b') mult = 1e9;
        if (mult != 1) s = s.substring(0, s.length() - 1);
        try {
            double d = Double.parseDouble(s) * mult;
            if (Double.isNaN(d) || Double.isInfinite(d) || Math.abs(d) > 9e15) return Long.MIN_VALUE;
            return Math.round(d);
        } catch (NumberFormatException e) {
            return Long.MIN_VALUE;
        }
    }
}
