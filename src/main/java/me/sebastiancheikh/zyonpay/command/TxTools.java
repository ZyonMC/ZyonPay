package me.sebastiancheikh.zyonpay.command;

import me.sebastiancheikh.zyonpay.config.Messages;
import me.sebastiancheikh.zyonpay.config.Settings;
import me.sebastiancheikh.zyonpay.economy.EconomyService;
import me.sebastiancheikh.zyonpay.economy.Result;
import me.sebastiancheikh.zyonpay.model.AccountRow;
import me.sebastiancheikh.zyonpay.model.HistoryRow;
import me.sebastiancheikh.zyonpay.model.TxDetail;
import me.sebastiancheikh.zyonpay.money.CurrencyRegistry;
import me.sebastiancheikh.zyonpay.storage.StorageException;
import me.sebastiancheikh.zyonpay.storage.TransactionRepository;
import org.bukkit.command.CommandSender;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** {@code /zyonpay tx <player> <id> [revert]} and {@code /zyonpay history <player> [before] [after]}. */
final class TxTools {

    static final String DATE_PATTERN = "yyyy-MM-dd_HH.mm.ss";
    private static final DateTimeFormatter ARG_FORMAT = DateTimeFormatter.ofPattern(DATE_PATTERN);

    private final EconomyService economy;
    private final CurrencyRegistry currencies;
    private final Messages messages;
    private final Settings settings;
    private final TransactionRepository transactions;
    private final TxLines txLines;
    private final Executor storage;
    private final Executor sync;

    TxTools(EconomyService economy, CurrencyRegistry currencies, Messages messages, Settings settings,
            TransactionRepository transactions, Executor storage, Executor sync) {
        this.economy = economy;
        this.currencies = currencies;
        this.messages = messages;
        this.settings = settings;
        this.transactions = transactions;
        this.txLines = new TxLines(messages, currencies, settings.history());
        this.storage = storage;
        this.sync = sync;
    }

    // ---------------------------------------------------------------- tx <player> <id> [revert]

    void tx(CommandSender sender, String[] args) {
        if (!sender.hasPermission("zyonpay.tx")) {
            messages.send(sender, "no-permission");
            return;
        }
        boolean revert = args.length == 4 && args[3].equalsIgnoreCase("revert");
        if (args.length < 3 || args.length > 4 || (args.length == 4 && !revert)) {
            messages.send(sender, "tx.usage");
            return;
        }
        if (revert && !sender.hasPermission("zyonpay.tx.revert")) {
            messages.send(sender, "no-permission");
            return;
        }
        long id;
        try {
            id = Long.parseLong(args[2]);
        } catch (NumberFormatException e) {
            messages.send(sender, "tx.usage");
            return;
        }
        CompletableFuture.supplyAsync(() -> lookup(args[1], id, revert), storage)
                .whenCompleteAsync((outcome, error) -> {
                    if (error != null) {
                        messages.send(sender, "error");
                    } else {
                        outcome.send(sender);
                    }
                }, sync);
    }

    /** Runs on the storage thread. */
    private Reply lookup(String playerName, long id, boolean revert) {
        Optional<AccountRow> player = economy.findByName(playerName);
        if (player.isEmpty()) {
            return (sender) -> messages.send(sender, "player-not-found", Messages.tag("player", playerName));
        }
        Optional<TxDetail> found = transactions.find(id);
        boolean involved = found.isPresent() && (player.get().uuid().equals(found.get().from())
                || player.get().uuid().equals(found.get().to()));
        if (!involved) {
            return sender -> messages.send(sender, "tx.not-found", Messages.tag("id", String.valueOf(id)));
        }
        TxDetail tx = found.get();
        if (!revert) {
            return sender -> detail(sender, tx);
        }
        Result result = economy.revert(tx);
        return sender -> messages.send(sender, switch (result) {
            case SUCCESS -> "tx.reverted";
            case ALREADY_REVERTED -> "tx.already-reverted";
            case INSUFFICIENT_FUNDS -> "tx.revert-insufficient";
            case MAX_BALANCE -> "eco.max-balance";
            default -> "error";
        }, Messages.tag("id", String.valueOf(id)));
    }

    private void detail(CommandSender sender, TxDetail tx) {
        String system = messages.raw("history.system");
        messages.send(sender, "tx.detail",
                Messages.tag("id", String.valueOf(tx.id())),
                Messages.tag("from", tx.fromName() != null ? tx.fromName() : system),
                Messages.tag("to", tx.toName() != null ? tx.toName() : system),
                Messages.tag("amount", currencies.format(tx.currency(), tx.amount())),
                Messages.tag("type", messages.raw("history.types." + tx.type().name().toLowerCase(Locale.ROOT))),
                Messages.tag("server", tx.server()),
                Messages.tag("date", settings.history().dateFormat().format(tx.at().atZone(ZoneId.systemDefault()))),
                Messages.tag("reason", tx.reason() == null ? "-" : tx.reason()),
                Messages.tag("reverted", tx.reverted() ? messages.raw("history.reverted") : ""));
    }

    // ---------------------------------------------------------------- history <player> [before] [after]

    void history(CommandSender sender, String[] args) {
        if (!sender.hasPermission("zyonpay.history.others")) {
            messages.send(sender, "no-permission");
            return;
        }
        if (args.length < 2 || args.length > 4) {
            messages.send(sender, "history-cmd.usage", Messages.tag("format", DATE_PATTERN));
            return;
        }
        Instant before = args.length >= 3 ? parseDate(args[2]) : null;
        Instant after = args.length == 4 ? parseDate(args[3]) : null;
        if ((args.length >= 3 && before == null) || (args.length == 4 && after == null)) {
            messages.send(sender, "history-cmd.bad-date", Messages.tag("format", DATE_PATTERN));
            return;
        }
        CompletableFuture.supplyAsync(() -> {
            Optional<AccountRow> player = economy.findByName(args[1]);
            if (player.isEmpty()) {
                return new Page(null, List.of());
            }
            try {
                return new Page(player.get(), transactions.between(player.get().uuid(), before, after, settings.history().chatLines()));
            } catch (StorageException e) {
                return null;
            }
        }, storage).thenAcceptAsync(page -> printHistory(sender, args[1], page), sync);
    }

    private void printHistory(CommandSender sender, String requested, Page page) {
        if (page == null) {
            messages.send(sender, "error");
        } else if (page.player() == null) {
            messages.send(sender, "player-not-found", Messages.tag("player", requested));
        } else if (page.rows().isEmpty()) {
            messages.send(sender, "tx.empty", Messages.tag("player", page.player().name()));
        } else {
            messages.send(sender, "tx.header", Messages.tag("player", page.player().name()));
            txLines.print(sender, page.player().uuid(), page.rows());
            // Hint the "before" value that continues from the oldest row shown.
            HistoryRow oldest = page.rows().get(page.rows().size() - 1);
            messages.send(sender, "history-cmd.next", Messages.tag("player", page.player().name()),
                    Messages.tag("before", ARG_FORMAT.format(oldest.at().atZone(ZoneId.systemDefault()))));
        }
    }

    private static Instant parseDate(String text) {
        try {
            return LocalDateTime.parse(text, ARG_FORMAT).atZone(ZoneId.systemDefault()).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private interface Reply {
        void send(CommandSender sender);
    }

    private record Page(AccountRow player, List<HistoryRow> rows) {
    }
}
