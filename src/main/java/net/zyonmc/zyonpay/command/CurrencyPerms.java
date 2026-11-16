package net.zyonmc.zyonpay.command;

import net.zyonmc.zyonpay.money.Currency;
import net.zyonmc.zyonpay.money.CurrencyRegistry;
import org.bukkit.command.CommandSender;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;

/**
 * Per-currency permissions ({@code zyonpay.<command>.<currency>}). The default currency is covered by the plain
 * command permission, so only the other currencies need the extra node.
 */
public final class CurrencyPerms {

    private CurrencyPerms() {
    }

    public static boolean allowed(CommandSender sender, CurrencyRegistry currencies, Currency currency, String base) {
        return currencies.isDefault(currency) || sender.hasPermission(base + "." + currency.id());
    }

    /** Declares the nodes so permission plugins list them. Safe to call again after a currency reload. */
    public static void register(PluginManager manager, CurrencyRegistry currencies) {
        for (Currency currency : currencies.all()) {
            declare(manager, "zyonpay.balance." + currency.id(), PermissionDefault.TRUE);
            declare(manager, "zyonpay.pay." + currency.id(), PermissionDefault.TRUE);
            declare(manager, "zyonpay.baltop." + currency.id(), PermissionDefault.TRUE);
            declare(manager, "zyonpay.eco." + currency.id(), PermissionDefault.OP);
        }
    }

    private static void declare(PluginManager manager, String node, PermissionDefault defaultValue) {
        if (manager.getPermission(node) == null) {
            manager.addPermission(new Permission(node, "Use this command with the " + node.substring(node.lastIndexOf('.') + 1)
                    + " currency.", defaultValue));
        }
    }
}
