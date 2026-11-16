package net.zyonmc.zyonpay.command;

import net.zyonmc.zyonpay.config.Messages;
import net.zyonmc.zyonpay.money.CurrencyRegistry;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;

/** {@code /zyonpay}: info, reload and the admin subcommands (tx, history, backup, restore, purge, migrate...). */
public final class ZyonPayCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS =
            List.of("reload", "tx", "history", "backup", "restore", "purge", "migrate", "switch-currency");

    private final JavaPlugin plugin;
    private final Messages messages;
    private final CurrencyRegistry currencies;
    private final Runnable reloader;
    private final TxTools txTools;
    private final DataTools dataTools;

    /**
     * Builds the command with its tools; this is the only entry point from outside the package.
     */
    public static ZyonPayCommand create(JavaPlugin plugin, Messages messages, CurrencyRegistry currencies,
                                        Runnable reloader, net.zyonmc.zyonpay.config.Settings settings,
                                        net.zyonmc.zyonpay.economy.EconomyService economy,
                                        net.zyonmc.zyonpay.storage.TransactionRepository transactions,
                                        net.zyonmc.zyonpay.storage.Backup backup,
                                        net.zyonmc.zyonpay.storage.MaintenanceRepository maintenance,
                                        net.zyonmc.zyonpay.sync.RedisBus bus,
                                        java.util.concurrent.Executor storage, java.util.concurrent.Executor sync) {
        TxTools tx = new TxTools(economy, currencies, messages, settings, transactions, storage, sync);
        DataTools data = new DataTools(plugin, economy, currencies, messages, settings, backup, maintenance, bus,
                storage, sync);
        return new ZyonPayCommand(plugin, messages, currencies, reloader, tx, data);
    }

    /**
     * @param reloader reloads messages, menus and currencies; throws {@link IllegalArgumentException} with the
     *                 reason if config.yml is invalid
     */
    ZyonPayCommand(JavaPlugin plugin, Messages messages, CurrencyRegistry currencies, Runnable reloader,
                   TxTools txTools, DataTools dataTools) {
        this.plugin = plugin;
        this.messages = messages;
        this.currencies = currencies;
        this.reloader = reloader;
        this.txTools = txTools;
        this.dataTools = dataTools;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
                             @NotNull String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> reload(sender);
            case "tx" -> txTools.tx(sender, args);
            case "history" -> txTools.history(sender, args);
            case "backup", "restore", "purge", "migrate", "switch-currency" -> {
                if (!sender.hasPermission("zyonpay.data")) {
                    messages.send(sender, "no-permission");
                    return true;
                }
                switch (sub) {
                    case "backup" -> dataTools.backup(sender, args);
                    case "restore" -> dataTools.restore(sender, args);
                    case "purge" -> dataTools.purge(sender, args);
                    case "migrate" -> dataTools.migrate(sender);
                    default -> dataTools.switchCurrency(sender, args);
                }
            }
            default -> messages.send(sender, "plugin-info", Messages.tag("version", plugin.getPluginMeta().getVersion()));
        }
        return true;
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("zyonpay.reload")) {
            messages.send(sender, "no-permission");
            return;
        }
        try {
            reloader.run();
            messages.send(sender, "reloaded");
        } catch (IllegalArgumentException e) {
            messages.send(sender, "reload-failed", Messages.tag("reason", e.getMessage()));
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias,
                                      @NotNull String[] args) {
        if (args.length == 1) {
            return Tabs.filter(SUBCOMMANDS, args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            return switch (sub) {
                case "tx", "history" -> Tabs.players(args[1]);
                case "restore" -> Tabs.filter(dataTools.backupFiles(), args[1]);
                case "switch-currency" -> Tabs.filter(currencies.ids(), args[1]);
                default -> List.of();
            };
        }
        if (args.length == 3 && sub.equals("switch-currency")) {
            return Tabs.filter(currencies.ids(), args[2]);
        }
        if (args.length == 4 && sub.equals("tx")) {
            return Tabs.filter(List.of("revert"), args[3]);
        }
        return List.of();
    }
}
