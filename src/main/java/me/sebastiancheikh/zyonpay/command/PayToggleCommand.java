package me.sebastiancheikh.zyonpay.command;

import me.sebastiancheikh.zyonpay.config.Messages;
import me.sebastiancheikh.zyonpay.economy.EconomyService;
import me.sebastiancheikh.zyonpay.model.AccountRow;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * {@code /paytoggle} lists the players you block, {@code /paytoggle <player>} blocks or unblocks one, and
 * {@code /paytoggle *} switches payments from everyone on or off.
 */
public final class PayToggleCommand implements CommandExecutor, TabCompleter {

    private final EconomyService economy;
    private final Messages messages;
    private final Executor storage;
    private final Executor sync;

    public PayToggleCommand(EconomyService economy, Messages messages, Executor storage, Executor sync) {
        this.economy = economy;
        this.messages = messages;
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
        if (args.length > 1) {
            messages.send(player, "paytoggle.usage");
            return true;
        }
        if (!economy.isCached(player.getUniqueId())) {
            messages.send(player, "error");
            return true;
        }
        if (args.length == 0) {
            list(player);
        } else if (args[0].equals("*")) {
            boolean accepts = economy.toggleAcceptsPayments(player.getUniqueId());
            messages.send(player, accepts ? "paytoggle.enabled" : "paytoggle.disabled");
        } else if (args[0].equalsIgnoreCase(player.getName())) {
            messages.send(player, "paytoggle.self");
        } else {
            toggle(player, args[0]);
        }
        return true;
    }

    private void list(Player player) {
        UUID owner = player.getUniqueId();
        CompletableFuture.supplyAsync(() -> economy.blockedNames(owner), storage)
                .whenCompleteAsync((names, error) -> {
                    if (error != null) {
                        messages.send(player, "error");
                    } else if (names.isEmpty()) {
                        messages.send(player, "paytoggle.list-empty");
                    } else {
                        messages.send(player, "paytoggle.list", Messages.tag("players", String.join(", ", names)));
                    }
                }, sync);
    }

    private void toggle(Player player, String targetName) {
        UUID owner = player.getUniqueId();
        CompletableFuture.supplyAsync(() -> {
            Optional<AccountRow> target = economy.findByName(targetName);
            if (target.isEmpty()) {
                return null;
            }
            return new Toggled(target.get().name(), economy.toggleBlocked(owner, target.get().uuid()));
        }, storage).whenCompleteAsync((toggled, error) -> {
            if (error != null) {
                messages.send(player, "error");
            } else if (toggled == null) {
                messages.send(player, "player-not-found", Messages.tag("player", targetName));
            } else {
                messages.send(player, toggled.blocked() ? "paytoggle.blocked" : "paytoggle.unblocked",
                        Messages.tag("player", toggled.name()));
            }
        }, sync);
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias,
                                      @NotNull String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        List<String> options = new ArrayList<>(Tabs.players(args[0]));
        options.add("*");
        return Tabs.filter(options, args[0]);
    }

    private record Toggled(String name, boolean blocked) {
    }
}
