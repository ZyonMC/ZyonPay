package net.zyonmc.zyonpay.money;

import net.zyonmc.zyonpay.config.Settings.CurrencySettings;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.List;
import java.util.Locale;

/** A configured currency: its settings and amount formatting. Immutable and thread-safe. */
public final class Currency {

    private final CurrencySettings settings;
    private final List<String> shortUnits;
    private final DecimalFormatSymbols symbols;
    // DecimalFormat is not thread-safe and is used from the main, storage and PlaceholderAPI threads.
    private final ThreadLocal<DecimalFormat> format;

    public Currency(CurrencySettings settings, List<String> shortUnits) {
        this.settings = settings;
        this.shortUnits = shortUnits;
        this.symbols = DecimalFormatSymbols.getInstance(Locale.forLanguageTag(settings.languageTag()));
        this.format = ThreadLocal.withInitial(() -> decimalFormat(settings.decimalFormat()));
    }

    public String id() {
        return settings.id();
    }

    public CurrencySettings settings() {
        return settings;
    }

    public String symbol() {
        return settings.symbol();
    }

    public String singular() {
        return settings.singular();
    }

    public String plural() {
        return settings.plural();
    }

    public long maxBalance() {
        return settings.maxBalance();
    }

    public long startingBalance() {
        return settings.startingBalance();
    }

    public boolean taxOnlyPay() {
        return settings.taxOnlyPay();
    }

    public boolean saveTransactions() {
        return settings.saveTransactions();
    }

    public boolean allowPay() {
        return settings.allowPay();
    }

    /** Minimum /pay amount; {@code fallback} is {@code pay.min-amount}, used when the currency sets none. */
    public long minPay(long fallback) {
        return settings.minPay() < 0 ? fallback : settings.minPay();
    }

    /** Maximum /pay amount, or 0 for no limit. */
    public long maxPay() {
        return settings.maxPay();
    }

    /** /pay tax on {@code amount} (0 if the currency has none). */
    public long tax(long amount) {
        return Money.tax(amount, settings.payTax());
    }

    /** {@code $1,234.56} according to the currency's {@code decimal-format} and {@code language-tag}. */
    public String format(long cents) {
        return settings.symbol() + format.get().format(BigDecimal.valueOf(cents, Money.SCALE));
    }

    /** {@code $1.5M} using {@code placeholders.short-units}. */
    public String formatShort(long cents) {
        return settings.symbol() + Money.compact(cents, shortUnits);
    }

    /**
     * Amount without symbol using a custom {@link DecimalFormat} pattern.
     *
     * @throws IllegalArgumentException if the pattern is invalid
     */
    public String formatWith(long cents, String pattern) {
        return decimalFormat(pattern).format(BigDecimal.valueOf(cents, Money.SCALE));
    }

    private DecimalFormat decimalFormat(String pattern) {
        DecimalFormat decimal = new DecimalFormat(pattern, symbols);
        decimal.setRoundingMode(RoundingMode.DOWN); // never display more money than there is
        return decimal;
    }
}
