package me.sebastiancheikh.zyonpay.placeholder;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.sebastiancheikh.zyonpay.config.Messages;
import me.sebastiancheikh.zyonpay.config.Settings.PlaceholderSettings;
import me.sebastiancheikh.zyonpay.economy.EconomyService;
import me.sebastiancheikh.zyonpay.money.Currency;
import me.sebastiancheikh.zyonpay.money.CurrencyRegistry;
import me.sebastiancheikh.zyonpay.money.Money;
import me.sebastiancheikh.zyonpay.top.TopEntry;
import me.sebastiancheikh.zyonpay.top.TopService;
import net.milkbowl.vault.chat.Chat;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code %zyonpay_...%} placeholders. Balances and rankings come from the cache and the baltop snapshot, so they
 * never do I/O and are safe in scoreboards. Every placeholder takes an optional {@code _<currency>} suffix
 * (default currency otherwise), except {@code bal_decformat}, whose pattern would be ambiguous.
 */
public final class ZyonPayExpansion extends PlaceholderExpansion {

    private static final Pattern TOP = Pattern.compile(
            "top_(\\d{1,4})_(name|balance|balance_short|balance_raw|playerprefix|playersuffix)");
    // bal_decformat<pattern> or bal_<currency>_decformat<pattern>; the pattern keeps its original case.
    private static final Pattern DECFORMAT = Pattern.compile("(?i)bal(?:_([a-z0-9_]{1,8}))??_decformat_?(.+)");

    private record Cached(String value, long at) {
    }

    private final JavaPlugin plugin;
    private final EconomyService economy;
    private final TopService top;
    private final CurrencyRegistry currencies;
    private final Messages messages;
    private final PlaceholderSettings settings;
    private final Map<String, Cached> affixes = new ConcurrentHashMap<>();

    public ZyonPayExpansion(JavaPlugin plugin, EconomyService economy, TopService top, CurrencyRegistry currencies,
                            Messages messages, PlaceholderSettings settings) {
        this.plugin = plugin;
        this.economy = economy;
        this.top = top;
        this.currencies = currencies;
        this.messages = messages;
        this.settings = settings;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "zyonpay";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Sebastian Cheikh";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        Matcher decimal = DECFORMAT.matcher(params);
        if (decimal.matches()) {
            Currency currency = decimal.group(1) == null
                    ? currencies.defaultCurrency() : currencies.find(decimal.group(1)).orElse(null);
            return currency == null ? null : decimalFormat(player, currency, decimal.group(2));
        }
        String key = params.toLowerCase(Locale.ROOT);
        for (Currency currency : currencies.all()) {
            String suffix = "_" + currency.id();
            if (key.endsWith(suffix)) {
                String value = value(player, key.substring(0, key.length() - suffix.length()), currency);
                if (value != null) {
                    return value;
                }
            }
        }
        return value(player, key, currencies.defaultCurrency());
    }

    /** Null when the key is not a known placeholder (PlaceholderAPI then leaves the text untouched). */
    private String value(OfflinePlayer player, String key, Currency currency) {
        Matcher topMatch = TOP.matcher(key);
        if (topMatch.matches()) {
            return topValue(currency, Integer.parseInt(topMatch.group(1)), topMatch.group(2));
        }
        return switch (key) {
            case "currency_symbol" -> currency.symbol();
            case "currency_singular" -> currency.singular();
            case "currency_plural" -> currency.plural();
            case "balance", "bal" -> balance(player, currency, currency::format);
            case "balance_short", "bal_short" -> balance(player, currency, currency::formatShort);
            case "balance_raw", "bal_raw" -> balance(player, currency, Money::plain);
            case "rank", "top_position" -> rank(player, currency);
            case "maxbal" -> player == null ? unknown() : currency.format(economy.maxBalance(player.getUniqueId(), currency));
            case "totsupply" -> Money.plain(top.supply(currency.id()));
            case "totsupply_formatted" -> currency.format(top.supply(currency.id()));
            case "totsupply_short" -> currency.formatShort(top.supply(currency.id()));
            case "accepts_payments" -> player == null ? unknown()
                    : String.valueOf(economy.acceptsPaymentsCached(player.getUniqueId()));
            default -> null;
        };
    }

    private String balance(OfflinePlayer player, Currency currency, LongFunction<String> format) {
        if (player == null) {
            return unknown();
        }
        OptionalLong cents = economy.cachedBalance(player.getUniqueId(), currency);
        return cents.isPresent() ? format.apply(cents.getAsLong()) : unknown();
    }

    private String decimalFormat(OfflinePlayer player, Currency currency, String pattern) {
        if (player == null) {
            return unknown();
        }
        OptionalLong cents = economy.cachedBalance(player.getUniqueId(), currency);
        if (cents.isEmpty()) {
            return unknown();
        }
        try {
            return currency.formatWith(cents.getAsLong(), pattern);
        } catch (IllegalArgumentException e) {
            return unknown();
        }
    }

    private String rank(OfflinePlayer player, Currency currency) {
        if (player == null) {
            return unknown();
        }
        OptionalInt rank = top.rankOf(currency.id(), player.getUniqueId());
        return rank.isPresent() && rank.getAsInt() <= settings.topAccounts() ? String.valueOf(rank.getAsInt()) : unknown();
    }

    private String topValue(Currency currency, int rank, String field) {
        if (rank > settings.topAccounts()) {
            return unknown();
        }
        return top.at(currency.id(), rank).map(entry -> field(currency, entry, field)).orElseGet(this::unknown);
    }

    private String field(Currency currency, TopEntry entry, String field) {
        return switch (field) {
            case "name" -> entry.name();
            case "balance" -> currency.format(entry.balance());
            case "balance_short" -> currency.formatShort(entry.balance());
            case "playerprefix" -> affix(entry.uuid(), true);
            case "playersuffix" -> affix(entry.uuid(), false);
            default -> Money.plain(entry.balance());
        };
    }

    /** Vault chat prefix/suffix, cached for {@code placeholders.cache-interval-ms} because chat plugins may be slow. */
    private String affix(UUID uuid, boolean prefix) {
        String key = uuid + (prefix ? ":p" : ":s");
        long now = System.currentTimeMillis();
        Cached cached = affixes.get(key);
        if (cached != null && now - cached.at() < settings.cacheIntervalMs()) {
            return cached.value();
        }
        RegisteredServiceProvider<Chat> registration = Bukkit.getServicesManager().getRegistration(Chat.class);
        String value = "";
        if (registration != null) {
            OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
            String found = prefix ? registration.getProvider().getPlayerPrefix(null, player)
                    : registration.getProvider().getPlayerSuffix(null, player);
            value = found == null ? "" : found;
        }
        affixes.put(key, new Cached(value, now));
        return value;
    }

    private String unknown() {
        return messages.raw("placeholder-unknown");
    }
}
