package me.sebastiancheikh.zyonpay.money;

import me.sebastiancheikh.zyonpay.config.Settings.CurrenciesSettings;
import me.sebastiancheikh.zyonpay.config.Settings.CurrencySettings;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Active currencies. They can be reloaded live: callers must resolve them here each time instead of keeping a
 * {@link Currency} reference (a reload creates new instances).
 */
public final class CurrencyRegistry {

    private record State(String defaultId, Map<String, Currency> byId) {
    }

    private volatile State state;

    public CurrencyRegistry(CurrenciesSettings settings, List<String> shortUnits) {
        this.state = build(settings, shortUnits);
    }

    /**
     * Replaces the currencies with the new ones.
     *
     * @throws IllegalArgumentException if the default currency changes (needs a restart)
     */
    public void reload(CurrenciesSettings settings, List<String> shortUnits) {
        if (!settings.defaultId().equals(state.defaultId())) {
            throw new IllegalArgumentException("default-currency cannot change without restarting the server");
        }
        this.state = build(settings, shortUnits);
    }

    private static State build(CurrenciesSettings settings, List<String> shortUnits) {
        Map<String, Currency> byId = new LinkedHashMap<>();
        for (CurrencySettings currency : settings.list()) {
            byId.put(currency.id(), new Currency(currency, shortUnits));
        }
        return new State(settings.defaultId(), byId);
    }

    public Currency defaultCurrency() {
        State current = state;
        return current.byId().get(current.defaultId());
    }

    /** Looks up by id, ignoring case. */
    public Optional<Currency> find(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(state.byId().get(id.toLowerCase(Locale.ROOT)));
    }

    public boolean isDefault(Currency currency) {
        return currency.id().equals(state.defaultId());
    }

    /** Formats an amount of a currency by id; falls back to a plain number if the currency was removed. */
    public String format(String id, long cents) {
        return find(id).map(currency -> currency.format(cents)).orElseGet(() -> Money.plain(cents) + " " + id);
    }

    public Collection<Currency> all() {
        return state.byId().values();
    }

    public List<String> ids() {
        return List.copyOf(state.byId().keySet());
    }
}
