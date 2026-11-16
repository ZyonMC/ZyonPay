package net.zyonmc.zyonpay.command;

import net.zyonmc.zyonpay.config.Messages;
import net.zyonmc.zyonpay.config.Settings.HistorySettings;
import net.zyonmc.zyonpay.model.HistoryRow;
import net.zyonmc.zyonpay.money.CurrencyRegistry;
import org.bukkit.command.CommandSender;

import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Prints transaction history lines in chat; shared by {@code /eco tx} and {@code /zyonpay history}. */
final class TxLines {

    private final Messages messages;
    private final CurrencyRegistry currencies;
    private final HistorySettings history;

    TxLines(Messages messages, CurrencyRegistry currencies, HistorySettings history) {
        this.messages = messages;
        this.currencies = currencies;
        this.history = history;
    }

    void print(CommandSender sender, UUID viewer, List<HistoryRow> rows) {
        for (HistoryRow row : rows) {
            boolean incoming = row.isIncoming(viewer);
            messages.send(sender, "tx.line",
                    Messages.tag("id", String.valueOf(row.id())),
                    Messages.tag("date", history.dateFormat().format(row.at().atZone(ZoneId.systemDefault()))),
                    Messages.tag("type", messages.raw("history.types." + row.type().name().toLowerCase(Locale.ROOT))),
                    Messages.parsedTag("sign_color", incoming ? "<green>" : "<red>"),
                    Messages.tag("sign", incoming ? "+" : "-"),
                    Messages.tag("amount", currencies.format(row.currency(), row.amount())),
                    Messages.tag("other", row.otherName() != null ? row.otherName() : messages.raw("history.system")),
                    Messages.tag("server", row.server()),
                    Messages.tag("reason", row.reason() == null ? "" : " - " + row.reason()),
                    Messages.tag("reverted", row.reverted() ? messages.raw("history.reverted") : ""));
        }
    }
}
