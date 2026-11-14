package me.sebastiancheikh.zyonpay.money;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoneyTest {

    @Test
    void parsesPlainAndSuffixedAmounts() {
        assertEquals(OptionalLong.of(10_000), Money.parse("100"));
        assertEquals(OptionalLong.of(150), Money.parse("1.5"));
        assertEquals(OptionalLong.of(150_000), Money.parse("1.5k"));
        assertEquals(OptionalLong.of(200_000_000), Money.parse("2M"));
        assertEquals(OptionalLong.of(300_000_000_000L), Money.parse("3b"));
        assertEquals(OptionalLong.of(4_000_000_000_000_00L), Money.parse("4T"));
    }

    @Test
    void rejectsInvalidInput() {
        for (String bad : new String[]{"", "abc", "-5", "0", "0.00", "1,5", "1e3", "NaN", "Infinity", "1.001",
                "0.001k0", "1kk", " 5", "5 ", null, "99999999999999999999"}) {
            assertTrue(Money.parse(bad).isEmpty(), "deberia rechazar: " + bad);
        }
    }

    @Test
    void zeroOnlyWhenAllowed() {
        assertEquals(OptionalLong.of(0), Money.parse("0", true));
        assertTrue(Money.parse("0").isEmpty());
    }

    @Test
    void acceptsFractionalCentsOnlyWhenSuffixMakesThemWhole() {
        assertEquals(OptionalLong.of(123_400), Money.parse("1.234k"));
        assertEquals(OptionalLong.of(123_450), Money.parse("1.2345k"));
        assertTrue(Money.parse("1.234567k").isEmpty());
    }

    @Test
    void vaultDoublesAreRoundedAndValidated() {
        assertEquals(OptionalLong.of(1999), Money.fromDouble(19.99));
        assertEquals(OptionalLong.of(1), Money.fromDouble(0.005));
        assertTrue(Money.fromDouble(-1).isEmpty());
        assertTrue(Money.fromDouble(Double.NaN).isEmpty());
        assertTrue(Money.fromDouble(Double.POSITIVE_INFINITY).isEmpty());
        assertTrue(Money.fromDouble(1e30).isEmpty());
    }

    @Test
    void configUnitsMustHaveAtMostTwoDecimals() {
        assertEquals(OptionalLong.of(100_000), Money.fromUnits(new BigDecimal("1000.00")));
        assertEquals(OptionalLong.of(0), Money.fromUnits(BigDecimal.ZERO));
        assertTrue(Money.fromUnits(new BigDecimal("1.234")).isEmpty());
        assertTrue(Money.fromUnits(new BigDecimal("-1")).isEmpty());
    }

    @Test
    void formatting() {
        assertEquals("1234.56", Money.plain(123_456));
        assertEquals("0.05", Money.plain(5));
        assertEquals("1,234,567.89", Money.grouped(123_456_789));
        assertEquals("0", Money.compact(0));
        assertEquals("12.5", Money.compact(1_250));
        assertEquals("999.99", Money.compact(99_999));
        assertEquals("1K", Money.compact(100_000));
        assertEquals("1.5M", Money.compact(150_000_000));
        assertEquals("2.34B", Money.compact(234_999_999_999L));
        assertEquals("1T", Money.compact(100_000_000_000_000L));
    }

    @Test
    void compactUsesConfigurableUnits() {
        List<String> units = List.of("", "k", "M", "B", "T");
        assertEquals("1.5k", Money.compact(150_000, units));
        assertEquals("2M", Money.compact(200_000_000, units));
        assertEquals("999.99", Money.compact(99_999, units));
        assertEquals("5 gold", Money.compact(500, List.of(" gold", "x")));
    }

    @Test
    void percentageOfBalanceRoundsDown() {
        assertEquals(OptionalLong.of(1_000), Money.percentOf("10%", 10_000));
        assertEquals(OptionalLong.of(1_250), Money.percentOf("12.5%", 10_000));
        assertEquals(OptionalLong.of(333), Money.percentOf("33.33%", 1_000));
        assertEquals(OptionalLong.of(10_000), Money.percentOf("100%", 10_000));
        for (String bad : new String[]{"0%", "101%", "-5%", "10", "%", "1.234%", "abc%", null}) {
            assertTrue(Money.percentOf(bad, 10_000).isEmpty(), "should reject: " + bad);
        }
        assertTrue(Money.percentOf("1%", 50).isEmpty(), "rounds down to zero");
    }

    @Test
    void taxRoundsToNearestCent() {
        assertEquals(500, Money.tax(10_000, new BigDecimal("0.05")));
        assertEquals(1, Money.tax(10, new BigDecimal("0.05"))); // 0.5 rounds half up
        assertEquals(0, Money.tax(10_000, BigDecimal.ZERO));
    }
}
