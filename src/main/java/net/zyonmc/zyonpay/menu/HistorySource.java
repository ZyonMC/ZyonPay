package net.zyonmc.zyonpay.menu;

import net.zyonmc.zyonpay.config.Messages;
import net.zyonmc.zyonpay.config.Settings.HistorySettings;
import net.zyonmc.zyonpay.economy.EconomyService;
import net.zyonmc.zyonpay.model.AccountRow;
import net.zyonmc.zyonpay.model.HistoryRow;
import net.zyonmc.zyonpay.money.CurrencyRegistry;
import net.zyonmc.zyonpay.storage.StorageException;
import net.zyonmc.zyonpay.storage.TransactionRepository;
import org.bukkit.entity.Player;

import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** A player's transaction history, queried from MariaDB on the storage thread. All currencies unless the menu sets one. */
final class HistorySource implements MenuDataSource {

    private final EconomyService economy;
    private final TransactionRepository transactions;
    private final CurrencyRegistry currencies;
    private final Messages messages;
    private final HistorySettings settings;
    private final Executor storage;

    HistorySource(EconomyService economy, TransactionRepository transactions, CurrencyRegistry currencies,
                  Messages messages, HistorySettings settings, Executor storage) {
        this.economy = economy;
        this.transactions = transactions;
        this.currencies = currencies;
        this.messages = messages;
        this.settings = settings;
        this.storage = storage;
    }

    @Override
    public String permission() {
        return "zyonpay.history";
    }

    @Override
    public String argumentPermission() {
        return "zyonpay.history.others";
    }

    @Override
    public CompletableFuture<List<Map<String, String>>> load(Player viewer, String argument, String menuCurrency) {
        return CompletableFuture.supplyAsync(() -> {
            UUID target = viewer.getUniqueId();
            if (argument != null && !argument.equalsIgnoreCase(viewer.getName())) {
                target = economy.findByName(argument)
                        .map(AccountRow::uuid)
                        .orElseThrow(() -> new MenuLoadException("menu.player-not-found"));
            }
            try {
                UUID owner = target;
                return transactions.recent(owner, settings.pageLimit()).stream()
                        .filter(row -> menuCurrency == null || menuCurrency.equals(row.currency()))
                        .map(row -> row(owner, row)).toList();
            } catch (StorageException e) {
                throw new MenuLoadException("error");
            }
        }, storage);
    }

    private Map<String, String> row(UUID owner, HistoryRow row) {
        boolean incoming = row.isIncoming(owner);
        return Map.of(
                "type", messages.raw("history.types." + row.type().name().toLowerCase(Locale.ROOT)),
                "other", row.otherName() != null ? row.otherName() : messages.raw("history.system"),
                "amount", currencies.format(row.currency(), row.amount()),
                "id", String.valueOf(row.id()),
                "currency", row.currency(),
                "reason", row.reason() == null ? "" : row.reason(),
                "sign", incoming ? "+" : "-",
                "color", incoming ? "green" : "red",
                "server", row.server(),
                "date", settings.dateFormat().format(row.at().atZone(ZoneId.systemDefault())));
    }
}
