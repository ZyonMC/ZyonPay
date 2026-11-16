package net.zyonmc.zyonpay.command;

import net.zyonmc.zyonpay.config.Messages;
import net.zyonmc.zyonpay.config.Settings.PaySettings;
import net.zyonmc.zyonpay.economy.EconomyService;
import net.zyonmc.zyonpay.economy.Result;
import net.zyonmc.zyonpay.menu.MenuManager;
import net.zyonmc.zyonpay.model.AccountRow;
import net.zyonmc.zyonpay.money.Currency;
import net.zyonmc.zyonpay.money.CurrencyRegistry;
import net.zyonmc.zyonpay.money.Money;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * {@code /pay <player> <amount|percent> [currency]}, {@code /pay * <amount> [currency]} (everyone online on this
 * server) and {@code /pay history [player]}.
 */
public final class PayCommand implements CommandExecutor, TabCompleter {

    private final EconomyService economy;
    private final CurrencyRegistry currencies;
    private final Messages messages;
    private final PaySettings settings;
    private final PayNotifier notifier;
    private final MenuManager menus;
    private final Executor storage;
    private final Executor sync;
    // ponytail: entries are never evicted; one long per player who ever paid since startup.
    private final Map<UUID, Long> lastPay = new ConcurrentHashMap<>();

    public PayCommand(EconomyService economy, CurrencyRegistry currencies, Messages messages, PaySettings settings,
                      PayNotifier notifier, MenuManager menus, Executor storage, Executor sync) {
        this.economy = economy;
        this.currencies = currencies;
        this.messages = messages;
        this.settings = settings;
        this.notifier = notifier;
        this.menus = menus;
        this.storage = storage;
        this.sync = sync;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
                             @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "player-only");
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("history") && args.length <= 2) {
            menus.open(player, "history", args.length == 2 ? args[1] : null);
            return true;
        }
        if (args.length < 2 || args.length > 3) {
            messages.send(player, "pay.usage");
            return true;
        }
        Currency currency = currencies.defaultCurrency();
        if (args.length == 3) {
            Optional<Currency> found = currencies.find(args[2]);
            if (found.isEmpty()) {
                messages.send(player, "currency-not-found", Messages.tag("currency", args[2]),
                        Messages.tag("currencies", String.join(", ", currencies.ids())));
                return true;
            }
            currency = found.get();
        }
        if (!CurrencyPerms.allowed(player, currencies, currency, "zyonpay.pay")) {
            messages.send(player, "no-permission");
            return true;
        }
        if (!currency.allowPay()) {
            messages.send(player, "pay.disabled-currency", Messages.tag("currency", currency.id()));
            return true;
        }
        boolean everyone = args[0].equals("*");
        if (everyone && !player.hasPermission("zyonpay.pay.all")) {
            messages.send(player, "no-permission");
            return true;
        }
        OptionalLong amount = parseAmount(player, args[1], currency, everyone);
        if (amount.isEmpty()) {
            messages.send(player, "invalid-amount");
            return true;
        }
        long min = currency.minPay(settings.minAmount());
        if (amount.getAsLong() < min) {
            messages.send(player, "pay.min-amount", Messages.tag("min", currency.format(min)));
            return true;
        }
        if (currency.maxPay() > 0 && amount.getAsLong() > currency.maxPay()) {
            messages.send(player, "pay.max-amount", Messages.tag("max", currency.format(currency.maxPay())));
            return true;
        }
        if (!everyone && args[0].equalsIgnoreCase(player.getName())) {
            messages.send(player, "pay.self");
            return true;
        }
        if (onCooldown(player)) {
            return true;
        }
        if (everyone) {
            payEveryone(player, currency, amount.getAsLong());
        } else {
            pay(player, args[0], currency, amount.getAsLong());
        }
        return true;
    }

    /** Plain amount, or a percentage of the payer's balance when enabled (not allowed with {@code *}). */
    private OptionalLong parseAmount(Player player, String input, Currency currency, boolean everyone) {
        if (!input.endsWith("%")) {
            return Money.parse(input);
        }
        if (!settings.allowPercentage() || everyone) {
            return OptionalLong.empty();
        }
        OptionalLong balance = economy.cachedBalance(player.getUniqueId(), currency);
        return balance.isEmpty() ? OptionalLong.empty() : Money.percentOf(input, balance.getAsLong());
    }

    private boolean onCooldown(Player player) {
        long now = System.currentTimeMillis();
        Long last = lastPay.get(player.getUniqueId());
        if (last != null && now - last < settings.cooldownMs()) {
            long remaining = settings.cooldownMs() - (now - last);
            messages.send(player, "pay.cooldown", Messages.tag("seconds", String.format("%.1f", remaining / 1000.0)));
            return true;
        }
        lastPay.put(player.getUniqueId(), now);
        return false;
    }

    // ---------------------------------------------------------------- single payment

    private void pay(Player sender, String targetName, Currency currency, long amount) {
        UUID senderId = sender.getUniqueId();
        String senderName = sender.getName();
        CompletableFuture
                .supplyAsync(() -> execute(senderId, senderName, targetName, currency, amount), storage)
                .thenAcceptAsync(outcome -> report(sender, targetName, currency, amount, outcome), sync);
    }

    /** Runs on the storage thread: may query MariaDB. */
    private Outcome execute(UUID senderId, String senderName, String targetName, Currency currency, long amount) {
        Optional<AccountRow> target = economy.findByName(targetName);
        if (target.isEmpty()) {
            return new Outcome(Result.NOT_FOUND, null);
        }
        return new Outcome(economy.pay(currency, senderId, senderName, target.get().uuid(), amount), target.get());
    }

    private void report(Player sender, String requested, Currency currency, long amount, Outcome outcome) {
        String targetName = outcome.target() != null ? outcome.target().name() : requested;
        var player = Messages.tag("player", targetName);
        switch (outcome.result()) {
            case SUCCESS -> {
                long tax = currency.tax(amount);
                messages.send(sender, "pay.sent", player, Messages.tag("amount", currency.format(amount)));
                if (tax > 0) {
                    messages.send(sender, "pay.tax", player, Messages.tag("tax", currency.format(tax)),
                            Messages.tag("received", currency.format(amount - tax)));
                }
                notifier.sent(sender);
                notifyLocalRecipient(outcome.target().uuid(), sender.getName(), currency, amount - tax);
            }
            case INSUFFICIENT_FUNDS -> messages.send(sender, "pay.insufficient-funds");
            case PAYMENTS_DISABLED -> messages.send(sender, "pay.recipient-disabled", player);
            case BLOCKED -> messages.send(sender, "pay.recipient-blocked", player);
            case MAX_BALANCE -> messages.send(sender, "pay.recipient-max-balance", player);
            case TARGET_OFFLINE -> messages.send(sender, "pay.recipient-offline", player);
            case TOO_SMALL -> messages.send(sender, "pay.too-small");
            case CANCELLED -> messages.send(sender, "pay.cancelled");
            case NOT_FOUND -> messages.send(sender, "player-not-found", player);
            case SELF -> messages.send(sender, "pay.self");
            default -> messages.send(sender, "error");
        }
    }

    private void notifyLocalRecipient(UUID recipient, String fromName, Currency currency, long received) {
        Player online = Bukkit.getPlayer(recipient);
        if (online != null) {
            notifier.received(online, fromName, currency, received);
        }
    }

    // ---------------------------------------------------------------- /pay *

    private void payEveryone(Player sender, Currency currency, long amount) {
        List<Player> recipients = new ArrayList<>(Bukkit.getOnlinePlayers());
        recipients.removeIf(other -> other.getUniqueId().equals(sender.getUniqueId()));
        if (recipients.isEmpty()) {
            messages.send(sender, "pay.nobody");
            return;
        }
        UUID senderId = sender.getUniqueId();
        String senderName = sender.getName();
        List<UUID> ids = recipients.stream().map(Player::getUniqueId).toList();
        CompletableFuture
                .supplyAsync(() -> executeAll(senderId, senderName, ids, currency, amount), storage)
                .thenAcceptAsync(paid -> reportAll(sender, currency, amount, paid), sync);
    }

    /** Each recipient gets {@code amount}; recipients who refuse payments or block the sender are skipped. */
    private List<UUID> executeAll(UUID senderId, String senderName, List<UUID> recipients, Currency currency,
                                  long amount) {
        List<UUID> paid = new ArrayList<>();
        for (UUID recipient : recipients) {
            Result result = economy.pay(currency, senderId, senderName, recipient, amount);
            if (result == Result.SUCCESS) {
                paid.add(recipient);
            } else if (result == Result.INSUFFICIENT_FUNDS) {
                break;
            }
        }
        return paid;
    }

    private void reportAll(Player sender, Currency currency, long amount, List<UUID> paid) {
        if (paid.isEmpty()) {
            messages.send(sender, "pay.all-none");
            return;
        }
        notifier.sent(sender);
        long received = amount - currency.tax(amount);
        for (UUID id : paid) {
            notifyLocalRecipient(id, sender.getName(), currency, received);
        }
        messages.send(sender, "pay.sent-all", Messages.tag("count", String.valueOf(paid.size())),
                Messages.tag("amount", currency.format(amount)),
                Messages.tag("total", currency.format(amount * paid.size())));
    }

    // ---------------------------------------------------------------- tab completion

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias,
                                      @NotNull String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>(Tabs.players(args[0]));
            options.add("history");
            if (sender.hasPermission("zyonpay.pay.all")) {
                options.add("*");
            }
            return Tabs.filter(options, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("history") && sender.hasPermission("zyonpay.history.others")) {
            return Tabs.players(args[1]);
        }
        if (args.length == 3) {
            return Tabs.filter(currencies.ids(), args[2]);
        }
        return List.of();
    }

    private record Outcome(Result result, AccountRow target) {
    }
}
