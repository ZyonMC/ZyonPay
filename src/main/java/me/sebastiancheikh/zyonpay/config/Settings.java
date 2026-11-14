package me.sebastiancheikh.zyonpay.config;

import me.sebastiancheikh.zyonpay.money.Money;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Typed, immutable and validated config.yml. Loaded once at startup. */
public record Settings(
        String serverId,
        boolean debug,
        int registerCallsVerbosity,
        List<Pattern> callBlacklist,
        boolean migrationEnabled,
        DatabaseSettings database,
        RedisSettings redis,
        CurrenciesSettings currencies,
        EconomySettings economy,
        PaySettings pay,
        TabSettings tab,
        PlaceholderSettings placeholders,
        BaltopSettings baltop,
        HistorySettings history) {

    /** Ids that clash with fixed permission nodes ({@code zyonpay.balance.others}, {@code zyonpay.pay.all}...). */
    private static final Set<String> RESERVED_IDS = Set.of("others", "all", "admin");
    private static final Pattern CURRENCY_ID = Pattern.compile("[a-z0-9_]{1,8}");

    public record DatabaseSettings(String host, int port, String name, String user, String password,
                                   int poolSize, int connectionTimeoutMs) {
    }

    public record RedisSettings(boolean enabled, String host, int port, String user, String password,
                                int database, String prefix, int timeoutMs, boolean ssl) {
    }

    /**
     * @param payTax /pay tax rate (0.05 = 5%)
     * @param historyTtlDays days history is kept; -1 = forever
     * @param allowPay       false makes the currency non-transferable with /pay
     * @param minPay         minimum /pay amount in cents, or -1 to use {@code pay.min-amount}
     * @param maxPay         maximum /pay amount in cents, or 0 for no limit
     */
    public record CurrencySettings(String id, String symbol, String singular, String plural, String decimalFormat,
                                   String languageTag, long startingBalance, long maxBalance, BigDecimal payTax,
                                   boolean taxOnlyPay, boolean saveTransactions, int historyTtlDays,
                                   boolean allowPay, long minPay, long maxPay) {
    }

    public record CurrenciesSettings(String defaultId, List<CurrencySettings> list) {
    }

    public record EconomySettings(long flushIntervalMs, boolean logVaultTransactions, boolean logPayTransactions,
                                  boolean logAdminTransactions) {
    }

    /** A sound to play: {@code name} is a namespaced key such as {@code entity.experience_orb.pickup}. */
    public record SoundSetting(String name, float volume, float pitch) {
    }

    /** Sounds are null when disabled (empty in config.yml). */
    public record PaySettings(long minAmount, boolean allowOffline, boolean allowPercentage, long cooldownMs,
                              boolean notifyRecipient, SoundSetting sentSound, SoundSetting receivedSound) {
    }

    public record TabSettings(int completeChars, boolean onlineOnly, int networkSyncSeconds) {
    }

    public record PlaceholderSettings(long cacheIntervalMs, int topAccounts, List<String> shortUnits) {
    }

    public record BaltopSettings(int size, int refreshSeconds) {
    }

    public record HistorySettings(int pageLimit, DateTimeFormatter dateFormat, int chatLines) {
    }

    /** @throws IllegalArgumentException naming the path of the invalid value */
    public static Settings load(FileConfiguration c) {
        String serverId = text(c, "server-id");
        if (serverId.length() > 32) {
            throw invalid("server-id", "at most 32 characters");
        }

        DatabaseSettings database = new DatabaseSettings(
                text(c, "database.host"),
                integer(c, "database.port", 1, 65535),
                text(c, "database.name"),
                text(c, "database.user"),
                c.getString("database.password", ""),
                integer(c, "database.pool-size", 1, 100),
                integer(c, "database.connection-timeout-ms", 250, 120_000));

        RedisSettings redis = new RedisSettings(
                c.getBoolean("redis.enabled", true),
                text(c, "redis.host"),
                integer(c, "redis.port", 1, 65535),
                c.getString("redis.user", ""),
                c.getString("redis.password", ""),
                integer(c, "redis.database", 0, 255),
                text(c, "redis.prefix"),
                integer(c, "redis.timeout-ms", 250, 60_000),
                c.getBoolean("redis.ssl", false));

        EconomySettings economy = new EconomySettings(
                integer(c, "economy.flush-interval-ticks", 1, 6000) * 50L,
                c.getBoolean("economy.log-vault-transactions", true),
                c.getBoolean("economy.log-pay-transactions", true),
                c.getBoolean("economy.log-admin-transactions", true));

        long minAmount = cents(c, "pay.min-amount");
        if (minAmount <= 0) {
            throw invalid("pay.min-amount", "must be greater than 0");
        }
        PaySettings pay = new PaySettings(minAmount, c.getBoolean("pay.allow-offline", true),
                c.getBoolean("pay.allow-percentage", true), integer(c, "pay.cooldown-ms", 0, 3_600_000),
                c.getBoolean("pay.notify-recipient", true), sound(c, "pay.sounds.sent"), sound(c, "pay.sounds.received"));

        TabSettings tab = new TabSettings(integer(c, "tab.complete-chars", 0, 16), c.getBoolean("tab.online-only", false),
                integer(c, "tab.network-sync-seconds", 1, 60));

        PlaceholderSettings placeholders = new PlaceholderSettings(
                integer(c, "placeholders.cache-interval-ms", 0, 3_600_000),
                integer(c, "placeholders.top-accounts", 1, 1000),
                shortUnits(c));

        BaltopSettings baltop = new BaltopSettings(
                integer(c, "baltop.size", 1, 1000),
                integer(c, "baltop.refresh-seconds", 5, 3600));

        DateTimeFormatter dateFormat;
        try {
            dateFormat = DateTimeFormatter.ofPattern(text(c, "history.date-format"));
        } catch (IllegalArgumentException e) {
            throw invalid("history.date-format", "invalid date pattern");
        }
        HistorySettings history = new HistorySettings(integer(c, "history.page-limit", 1, 1000), dateFormat,
                integer(c, "history.chat-lines", 1, 50));

        int verbosity = integer(c, "register-calls-verbosity", 0, 3);
        List<Pattern> blacklist = new ArrayList<>();
        for (String regex : c.getStringList("call-blacklist-regex")) {
            try {
                blacklist.add(Pattern.compile(regex));
            } catch (PatternSyntaxException e) {
                throw invalid("call-blacklist-regex", "invalid regex: " + regex);
            }
        }

        return new Settings(serverId, c.getBoolean("debug", false), verbosity, List.copyOf(blacklist),
                c.getBoolean("migration.enabled", false), database, redis, loadCurrencies(c), economy, pay, tab,
                placeholders, baltop, history);
    }

    /** Reads {@code default-currency} and {@code currencies}. Also used by the live currency reload. */
    public static CurrenciesSettings loadCurrencies(FileConfiguration c) {
        ConfigurationSection section = c.getConfigurationSection("currencies");
        if (section == null || section.getKeys(false).isEmpty()) {
            throw invalid("currencies", "define at least one currency");
        }
        List<CurrencySettings> list = new ArrayList<>();
        for (String key : section.getKeys(false)) {
            list.add(currency(c, key));
        }
        String defaultId = text(c, "default-currency").toLowerCase(Locale.ROOT);
        if (list.stream().noneMatch(currency -> currency.id().equals(defaultId))) {
            throw invalid("default-currency", "does not match any currency in 'currencies'");
        }
        return new CurrenciesSettings(defaultId, List.copyOf(list));
    }

    /** Reads {@code placeholders.short-units}. */
    public static List<String> shortUnits(FileConfiguration c) {
        List<String> units = c.getStringList("placeholders.short-units");
        if (units.size() < 2) {
            throw invalid("placeholders.short-units", "at least 2 units (the first is for values < 1000)");
        }
        return List.copyOf(units);
    }

    private static CurrencySettings currency(FileConfiguration c, String key) {
        String path = "currencies." + key;
        String id = key.toLowerCase(Locale.ROOT);
        if (!CURRENCY_ID.matcher(id).matches() || RESERVED_IDS.contains(id)) {
            throw invalid(path, "invalid id (1-8 characters a-z, 0-9 or _; cannot be " + RESERVED_IDS + ")");
        }
        long maxBalance = cents(c, path + ".max-balance");
        long startingBalance = cents(c, path + ".starting-balance");
        if (maxBalance <= 0 || startingBalance > maxBalance) {
            throw invalid(path + ".max-balance", "must be positive and >= starting-balance");
        }
        String decimalFormat = text(c, path + ".decimal-format");
        try {
            new DecimalFormat(decimalFormat);
        } catch (IllegalArgumentException e) {
            throw invalid(path + ".decimal-format", "invalid pattern");
        }
        String languageTag = text(c, path + ".language-tag");
        if (Locale.forLanguageTag(languageTag).getLanguage().isEmpty()) {
            throw invalid(path + ".language-tag", "invalid language tag (e.g. en-US, es-ES)");
        }
        BigDecimal payTax;
        try {
            payTax = new BigDecimal(String.valueOf(c.get(path + ".pay-tax", 0)));
        } catch (NumberFormatException e) {
            throw invalid(path + ".pay-tax", "not a number");
        }
        if (payTax.signum() < 0 || payTax.compareTo(BigDecimal.ONE) >= 0) {
            throw invalid(path + ".pay-tax", "must be between 0 and 1 (0.05 = 5%)");
        }
        int ttl = c.getInt(path + ".history-ttl-days", -1);
        if (ttl == 0 || ttl < -1) {
            throw invalid(path + ".history-ttl-days", "-1 (never) or a positive number of days");
        }
        long minPay = c.contains(path + ".min-pay") ? cents(c, path + ".min-pay") : -1;
        long maxPay = c.contains(path + ".max-pay") ? cents(c, path + ".max-pay") : 0;
        if (minPay == 0 || (maxPay > 0 && minPay > maxPay)) {
            throw invalid(path + ".min-pay", "must be greater than 0 and not above max-pay");
        }
        return new CurrencySettings(id, c.getString(path + ".symbol", ""), text(c, path + ".singular"),
                text(c, path + ".plural"), decimalFormat, languageTag, startingBalance, maxBalance, payTax,
                c.getBoolean(path + ".tax-only-pay", true), c.getBoolean(path + ".save-transactions", true), ttl,
                c.getBoolean(path + ".allow-pay", true), minPay, maxPay);
    }

    /** Parses {@code "sound.key;volume;pitch"} (volume and pitch optional). Blank means no sound. */
    private static SoundSetting sound(FileConfiguration c, String path) {
        String raw = c.getString(path, "");
        if (raw.isBlank()) {
            return null;
        }
        String[] parts = raw.split(";");
        try {
            String name = parts[0].trim();
            if (!name.matches("[a-z0-9_.:/-]+")) {
                throw new NumberFormatException();
            }
            return new SoundSetting(name, parts.length > 1 ? Float.parseFloat(parts[1].trim()) : 1f,
                    parts.length > 2 ? Float.parseFloat(parts[2].trim()) : 1f);
        } catch (NumberFormatException e) {
            throw invalid(path, "expected sound.key;volume;pitch, for example entity.experience_orb.pickup;1.0;1.2");
        }
    }

    private static String text(FileConfiguration c, String path) {
        String value = c.getString(path);
        if (value == null || value.isBlank()) {
            throw invalid(path, "required");
        }
        return value.trim();
    }

    private static int integer(FileConfiguration c, String path, int min, int max) {
        if (!c.isInt(path)) {
            throw invalid(path, "must be a whole number");
        }
        int value = c.getInt(path);
        if (value < min || value > max) {
            throw invalid(path, "must be between " + min + " and " + max);
        }
        return value;
    }

    private static long cents(FileConfiguration c, String path) {
        Object raw = c.get(path);
        if (raw == null) {
            throw invalid(path, "required");
        }
        try {
            OptionalLong cents = Money.fromUnits(new BigDecimal(raw.toString()));
            return cents.orElseThrow(() -> invalid(path, "at most 2 decimals and not negative"));
        } catch (NumberFormatException e) {
            throw invalid(path, "not a number");
        }
    }

    private static IllegalArgumentException invalid(String path, String reason) {
        return new IllegalArgumentException("config.yml -> " + path + ": " + reason);
    }
}
