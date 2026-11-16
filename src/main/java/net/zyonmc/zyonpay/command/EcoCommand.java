package net.zyonmc.zyonpay.command;

import net.zyonmc.zyonpay.config.Messages;
import net.zyonmc.zyonpay.config.Settings;
import net.zyonmc.zyonpay.economy.EconomyService;
import net.zyonmc.zyonpay.economy.Result;
import net.zyonmc.zyonpay.model.AccountRow;
import net.zyonmc.zyonpay.model.HistoryRow;
import net.zyonmc.zyonpay.model.TxType;
import net.zyonmc.zyonpay.model.WalletRow;
import net.zyonmc.zyonpay.money.Currency;
import net.zyonmc.zyonpay.money.CurrencyRegistry;
import net.zyonmc.zyonpay.money.Money;
import net.zyonmc.zyonpay.storage.StorageException;
import net.zyonmc.zyonpay.storage.TransactionRepository;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * {@code /eco give|take|set <player> <amount> [currency] [reason]}, {@code /eco reset <player> [currency] [reason]},
 * {@code /eco setmax <player> <amount|reset> [currency]} and {@code /eco tx <player>}.
 */
public final class EcoCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("give", "take", "set", "reset", "setmax", "tx");

    private final EconomyService economy;
    private final CurrencyRegistry currencies;
    private final Messages messages;
    private final TransactionRepository transactions;
    private final TxLines txLines;
    private final int chatLines;
    private final Executor storage;
    private final Executor sync;

    public EcoCommand(EconomyService economy, CurrencyRegistry currencies, Messages messages, Settings settings,
                      TransactionRepository transactions, Executor storage, Executor sync) {
        this.economy = economy;
        this.currencies = currencies;
        this.messages = messages;
        this.transactions = transactions;
        this.txLines = new TxLines(messages, currencies, settings.history());
        this.chatLines = settings.history().chatLines();
        this.storage = storage;
        this.sync = sync;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
                             @NotNull String[] args) {
        if (args.length < 2) {
            messages.send(sender, "eco.usage");
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "give", "take", "set" -> {
                if (args.length < 3) {
                    messages.send(sender, "eco.usage");
                    return true;
                }
                OptionalLong amount = Money.parse(args[2], sub.equals("set"));
                if (amount.isEmpty()) {
                    messages.send(sender, "invalid-amount");
                    return true;
                }
                modify(sender, sub, args[1], amount.getAsLong(), args, 3);
            }
            case "reset" -> modify(sender, sub, args[1], -1, args, 2);
            case "setmax" -> setMax(sender, args);
            case "tx" -> {
                if (!sender.hasPermission("zyonpay.tx")) {
                    messages.send(sender, "no-permission");
                    return true;
                }
                showTransactions(sender, args[1]);
            }
            default -> messages.send(sender, "eco.usage");
        }
        return true;
    }

    /** Optional trailing {@code [currency] [reason...]}; a first word that is a currency id selects it. */
    private record Tail(Currency currency, String reason) {
    }

    private Tail tail(String[] args, int from) {
        Currency currency = currencies.defaultCurrency();
        int reasonFrom = from;
        if (args.length > from) {
            Optional<Currency> found = currencies.find(args[from]);
            if (found.isPresent()) {
                currency = found.get();
                reasonFrom++;
            }
        }
        String reason = args.length > reasonFrom ? String.join(" ", Arrays.copyOfRange(args, reasonFrom, args.length)) : null;
        return new Tail(currency, reason);
    }

    private void modify(CommandSender sender, String sub, String targetName, long amount, String[] args, int tailFrom) {
        Tail tail = tail(args, tailFrom);
        if (!CurrencyPerms.allowed(sender, currencies, tail.currency(), "zyonpay.eco")) {
            messages.send(sender, "no-permission");
            return;
        }
        long value = sub.equals("reset") ? tail.currency().startingBalance() : amount;
        CompletableFuture
                .supplyAsync(() -> run(sub, targetName, tail.currency(), value, tail.reason()), storage)
                .thenAcceptAsync(outcome -> report(sender, sub, targetName, tail.currency(), value, outcome), sync);
    }

    /** Runs on the storage thread. */
    private Outcome run(String sub, String targetName, Currency currency, long amount, String reason) {
        Optional<AccountRow> target = economy.findByName(targetName);
        if (target.isEmpty()) {
            return new Outcome(Result.NOT_FOUND, null, 0);
        }
        UUID uuid = target.get().uuid();
        Result result = switch (sub) {
            case "give" -> economy.deposit(currency, uuid, amount, TxType.ADMIN, null, reason);
            case "take" -> economy.withdraw(currency, uuid, amount, TxType.ADMIN, null, reason);
            default -> economy.setBalance(currency, uuid, amount, TxType.ADMIN, reason);
        };
        return new Outcome(result, target.get(), economy.balance(uuid, currency).orElse(0));
    }

    private void report(CommandSender sender, String sub, String requested, Currency currency, long amount,
                        Outcome outcome) {
        String name = outcome.target() != null ? outcome.target().name() : requested;
        TagResolver player = Messages.tag("player", name);
        TagResolver balance = Messages.tag("balance", currency.format(outcome.balance()));
        switch (outcome.result()) {
            case SUCCESS -> messages.send(sender, "eco." + sub, player, balance,
                    Messages.tag("amount", currency.format(amount)));
            case INSUFFICIENT_FUNDS -> messages.send(sender, "eco.insufficient-funds", player, balance);
            case MAX_BALANCE -> messages.send(sender, "eco.max-balance");
            case NOT_FOUND -> messages.send(sender, "player-not-found", player);
            default -> messages.send(sender, "error");
        }
    }

    // ---------------------------------------------------------------- setmax

    private void setMax(CommandSender sender, String[] args) {
        if (args.length < 3 || args.length > 4) {
            messages.send(sender, "eco.usage");
            return;
        }
        boolean clear = args[2].equalsIgnoreCase("reset");
        OptionalLong max = clear ? OptionalLong.of(WalletRow.NO_MAX) : Money.parse(args[2]);
        if (max.isEmpty()) {
            messages.send(sender, "invalid-amount");
            return;
        }
        Tail tail = tail(args, 3);
        Currency currency = tail.currency();
        if (!CurrencyPerms.allowed(sender, currencies, currency, "zyonpay.eco")) {
            messages.send(sender, "no-permission");
            return;
        }
        CompletableFuture.supplyAsync(() -> {
            Optional<AccountRow> target = economy.findByName(args[1]);
            return target.isEmpty() ? new Outcome(Result.NOT_FOUND, null, 0)
                    : new Outcome(economy.setMaxBalance(currency, target.get().uuid(), max.getAsLong()), target.get(), 0);
        }, storage).thenAcceptAsync(outcome -> {
            TagResolver player = Messages.tag("player", outcome.target() != null ? outcome.target().name() : args[1]);
            switch (outcome.result()) {
                case SUCCESS -> messages.send(sender, clear ? "eco.setmax-reset" : "eco.setmax", player,
                        Messages.tag("max", currency.format(clear ? currency.maxBalance() : max.getAsLong())));
                case NOT_FOUND -> messages.send(sender, "player-not-found", player);
                default -> messages.send(sender, "error");
            }
        }, sync);
    }

    // ---------------------------------------------------------------- tx

    private void showTransactions(CommandSender sender, String targetName) {
        CompletableFuture.supplyAsync(() -> {
            Optional<AccountRow> target = economy.findByName(targetName);
            if (target.isEmpty()) {
                return new TxReport(null, List.of());
            }
            try {
                return new TxReport(target.get(), transactions.recent(target.get().uuid(), chatLines));
            } catch (StorageException e) {
                return null;
            }
        }, storage).thenAcceptAsync(report -> printTransactions(sender, targetName, report), sync);
    }

    private void printTransactions(CommandSender sender, String requested, TxReport report) {
        if (report == null) {
            messages.send(sender, "error");
            return;
        }
        if (report.target() == null) {
            messages.send(sender, "player-not-found", Messages.tag("player", requested));
            return;
        }
        TagResolver player = Messages.tag("player", report.target().name());
        if (report.rows().isEmpty()) {
            messages.send(sender, "tx.empty", player);
            return;
        }
        messages.send(sender, "tx.header", player);
        txLines.print(sender, report.target().uuid(), report.rows());
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias,
                                      @NotNull String[] args) {
        if (args.length == 1) {
            return Tabs.filter(SUBCOMMANDS, args[0]);
        }
        if (args.length == 2) {
            return Tabs.players(args[1]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        boolean currencySlot = (args.length == 4 && List.of("give", "take", "set", "setmax").contains(sub))
                || (args.length == 3 && sub.equals("reset"));
        return currencySlot ? Tabs.filter(currencies.ids(), args[args.length - 1]) : List.of();
    }

    private record Outcome(Result result, AccountRow target, long balance) {
    }

    private record TxReport(AccountRow target, List<HistoryRow> rows) {
    }
}
