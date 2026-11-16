package net.zyonmc.zyonpay.menu;

import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Supplies the rows of a menu. Each row is a map of placeholders ({@code {name}} -> value). */
interface MenuDataSource {

    /** Permission needed to open the menu. */
    String permission();

    /** Extra permission needed when the player passes an argument (for example to view someone else's history). */
    String argumentPermission();

    /**
     * @param argument     extra command text or null
     * @param menuCurrency currency id set in menus.yml, or null for the default
     * @return the rows; may fail with {@link MenuLoadException}
     */
    CompletableFuture<List<Map<String, String>>> load(Player viewer, String argument, String menuCurrency);
}
