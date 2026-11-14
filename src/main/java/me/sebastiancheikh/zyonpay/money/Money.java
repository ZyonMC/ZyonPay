package me.sebastiancheikh.zyonpay.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Money as a {@code long} of cents (fixed 2 decimals). {@code double} is never used to do arithmetic. */
public final class Money {

    public static final int SCALE = 2;

    private static final BigDecimal CENTS_PER_UNIT = BigDecimal.valueOf(100);
    private static final Pattern AMOUNT = Pattern.compile("(\\d{1,18}(?:\\.\\d{1,18})?)([kmbt]?)", Pattern.CASE_INSENSITIVE);
    private static final Map<Character, BigDecimal> SUFFIXES = Map.of(
            'k', BigDecimal.valueOf(1_000L),
            'm', BigDecimal.valueOf(1_000_000L),
            'b', BigDecimal.valueOf(1_000_000_000L),
            't', BigDecimal.valueOf(1_000_000_000_000L));
    private static final Pattern PERCENT = Pattern.compile("(\\d{1,3}(?:\\.\\d{1,2})?)%");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** Default units: position {@code i} applies from 10^(3i). */
    public static final List<String> DEFAULT_UNITS = List.of("", "K", "M", "B", "T");

    private Money() {
    }

    /** Parses player input ({@code 100}, {@code 1.5k}, {@code 2M}). Positive values only. */
    public static OptionalLong parse(String input) {
        return parse(input, false);
    }

    public static OptionalLong parse(String input, boolean allowZero) {
        if (input == null || input.length() > 40) {
            return OptionalLong.empty();
        }
        Matcher matcher = AMOUNT.matcher(input);
        if (!matcher.matches()) {
            return OptionalLong.empty();
        }
        BigDecimal units = new BigDecimal(matcher.group(1));
        if (!matcher.group(2).isEmpty()) {
            units = units.multiply(SUFFIXES.get(Character.toLowerCase(matcher.group(2).charAt(0))));
        }
        return exactCents(units, allowZero);
    }

    /** Converts decimal units (config.yml) to cents. Empty if there are more than 2 decimals or it overflows. */
    public static OptionalLong fromUnits(BigDecimal units) {
        return exactCents(units, true);
    }

    /** Converts the {@code double} of the Vault API. Empty if NaN, infinite, negative or overflowing. */
    public static OptionalLong fromDouble(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value < 0) {
            return OptionalLong.empty();
        }
        try {
            BigDecimal rounded = BigDecimal.valueOf(value).setScale(SCALE, RoundingMode.HALF_UP);
            return OptionalLong.of(rounded.movePointRight(SCALE).longValueExact());
        } catch (ArithmeticException e) {
            return OptionalLong.empty();
        }
    }

    public static double toDouble(long cents) {
        return cents / 100.0;
    }

    /** {@code 123456 -> "1234.56"} */
    public static String plain(long cents) {
        return String.format(Locale.ROOT, "%d.%02d", cents / 100, cents % 100);
    }

    /** {@code 123456 -> "1,234.56"} */
    public static String grouped(long cents) {
        return String.format(Locale.US, "%,d.%02d", cents / 100, cents % 100);
    }

    /** {@code 1_500_000_00 -> "1.5M"} */
    public static String compact(long cents) {
        return compact(cents, DEFAULT_UNITS);
    }

    /** Abbreviates with the given units: {@code units[i]} applies from 10^(3i). */
    public static String compact(long cents, List<String> units) {
        BigDecimal value = BigDecimal.valueOf(cents, SCALE);
        for (int i = units.size() - 1; i >= 1; i--) {
            BigDecimal divisor = BigDecimal.TEN.pow(3 * i);
            if (value.compareTo(divisor) >= 0) {
                return trim(value.divide(divisor, 2, RoundingMode.DOWN)) + units.get(i);
            }
        }
        return trim(value) + units.get(0);
    }

    /**
     * A percentage of the balance ({@code 10%}, {@code 12.5%}), rounded down to the cent. Empty if the text is not
     * a percentage between 0 (exclusive) and 100, or the result is 0.
     */
    public static OptionalLong percentOf(String input, long balance) {
        if (input == null) {
            return OptionalLong.empty();
        }
        Matcher matcher = PERCENT.matcher(input);
        if (!matcher.matches()) {
            return OptionalLong.empty();
        }
        BigDecimal percent = new BigDecimal(matcher.group(1));
        if (percent.signum() <= 0 || percent.compareTo(HUNDRED) > 0) {
            return OptionalLong.empty();
        }
        long cents = BigDecimal.valueOf(balance).multiply(percent).divide(HUNDRED, 0, RoundingMode.DOWN).longValue();
        return cents > 0 ? OptionalLong.of(cents) : OptionalLong.empty();
    }

    /** Tax on {@code amount} at the given rate (0.05 = 5%), rounded to the nearest cent. */
    public static long tax(long amount, BigDecimal rate) {
        if (rate.signum() <= 0) {
            return 0;
        }
        return BigDecimal.valueOf(amount).multiply(rate).setScale(0, RoundingMode.HALF_UP).longValue();
    }

    private static OptionalLong exactCents(BigDecimal units, boolean allowZero) {
        try {
            long cents = units.multiply(CENTS_PER_UNIT).longValueExact();
            return cents > 0 || (allowZero && cents == 0) ? OptionalLong.of(cents) : OptionalLong.empty();
        } catch (ArithmeticException e) {
            return OptionalLong.empty();
        }
    }

    private static String trim(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
