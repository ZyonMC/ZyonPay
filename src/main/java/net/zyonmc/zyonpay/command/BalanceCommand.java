package net.zyonmc.zyonpay.command;

import net.zyonmc.zyonpay.config.Messages;
import net.zyonmc.zyonpay.economy.EconomyService;
import net.zyonmc.zyonpay.model.AccountRow;
import net.zyonmc.zyonpay.money.Currency;
import net.zyonmc.zyonpay.money.CurrencyRegistry;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** {@code /balance [player] [currency]}; a single argument that names a currency shows your own balance in it. */
public final class BalanceCommand implements CommandExecutor, TabCompleter {

    private final EconomyService economy;
    private final CurrencyRegistry currencies;
    private final Messages messages;
    private final Executor storage;
    private final Executor sync;

    public BalanceCommand(EconomyService economy, CurrencyRegistry currencies, Messages messages, Executor storage,
                          Executor sync) {
        this.economy = economy;
        this.currencies = currencies;
        this.messages = messages;
        this.storage = storage;
        this.sync = sync;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
                             @NotNull String[] args) {
        if (args.length > 2) {
            messages.send(sender, "balance.usage");
            return true;
        }
        String playerArg = args.length >= 1 ? args[0] : null;
        String currencyArg = args.length == 2 ? args[1] : null;
        if (args.length == 1 && currencies.find(args[0]).isPresent()) {
            playerArg = null;
            currencyArg = args[0];
        }
        Currency currency = currencies.defaultCurrency();
        if (currencyArg != null) {
            Optional<Currency> found = currencies.find(currencyArg);
            if (found.isEmpty()) {
                messages.send(sender, "currency-not-found", Messages.tag("currency", currencyArg),
                        Messages.tag("currencies", String.join(", ", currencies.ids())));
                return true;
            }
            currency = found.get();
        }
        if (!CurrencyPerms.allowed(sender, currencies, currency, "zyonpay.balance")) {
            messages.send(sender, "no-permission");
            return true;
        }
        if (playerArg == null) {
            showOwn(sender, currency);
        } else if (sender.hasPermission("zyonpay.balance.others")) {
            showOther(sender, playerArg, currency);
        } else {
            messages.send(sender, "no-permission");
        }
        return true;
    }

    private void showOwn(CommandSender sender, Currency currency) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "player-only");
            return;
        }
        economy.cachedBalance(player.getUniqueId(), currency).ifPresentOrElse(
                balance -> messages.send(player, "balance.self", Messages.tag("balance", currency.format(balance))),
                () -> messages.send(player, "error"));
    }

    private void showOther(CommandSender sender, String name, Currency currency) {
        CompletableFuture.supplyAsync(() -> economy.findByName(name).map(row -> new Found(row, economy.balance(row.uuid(), currency).orElse(0))), storage)
                .thenAcceptAsync(found -> report(sender, name, currency, found), sync);
    }

    private void report(CommandSender sender, String requested, Currency currency, Optional<Found> found) {
        if (found.isEmpty()) {
            messages.send(sender, "player-not-found", Messages.tag("player", requested));
            return;
        }
        messages.send(sender, "balance.other",
                Messages.tag("player", found.get().row().name()),
                Messages.tag("balance", currency.format(found.get().balance())));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias,
                                      @NotNull String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>(currencies.ids());
            if (sender.hasPermission("zyonpay.balance.others")) {
                options.addAll(Tabs.players(args[0]));
            }
            return Tabs.filter(options, args[0]);
        }
        return args.length == 2 ? Tabs.filter(currencies.ids(), args[1]) : List.of();
    }

    private record Found(AccountRow row, long balance) {
    }
}
