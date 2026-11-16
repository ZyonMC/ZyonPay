package net.zyonmc.zyonpay.menu;

import net.zyonmc.zyonpay.command.CurrencyPerms;
import net.zyonmc.zyonpay.money.Currency;
import net.zyonmc.zyonpay.money.CurrencyRegistry;
import net.zyonmc.zyonpay.money.Money;
import net.zyonmc.zyonpay.top.TopEntry;
import net.zyonmc.zyonpay.top.TopService;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Ranking rows read from the in-memory snapshot (no I/O). {@code /baltop <currency>} picks another currency. */
final class BaltopSource implements MenuDataSource {

    private final TopService top;
    private final CurrencyRegistry currencies;
    private final int size;

    BaltopSource(TopService top, CurrencyRegistry currencies, int size) {
        this.top = top;
        this.currencies = currencies;
        this.size = size;
    }

    @Override
    public String permission() {
        return "zyonpay.baltop";
    }

    @Override
    public String argumentPermission() {
        return permission();
    }

    @Override
    public CompletableFuture<List<Map<String, String>>> load(Player viewer, String argument, String menuCurrency) {
        Currency currency = currencies.find(argument)
                .or(() -> currencies.find(menuCurrency))
                .orElseGet(currencies::defaultCurrency);
        if (!CurrencyPerms.allowed(viewer, currencies, currency, "zyonpay.baltop")) {
            return CompletableFuture.failedFuture(new MenuLoadException("no-permission"));
        }
        List<Map<String, String>> rows = top.snapshot(currency.id()).stream().limit(size)
                .map(entry -> row(currency, entry)).toList();
        return CompletableFuture.completedFuture(rows);
    }

    private Map<String, String> row(Currency currency, TopEntry entry) {
        return Map.of(
                "rank", String.valueOf(entry.rank()),
                "name", entry.name(),
                "uuid", entry.uuid().toString(),
                "balance", currency.format(entry.balance()),
                "balance_short", currency.formatShort(entry.balance()),
                "balance_raw", Money.plain(entry.balance()),
                "currency", currency.id());
    }
}
