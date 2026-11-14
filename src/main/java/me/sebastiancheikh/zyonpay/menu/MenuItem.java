package me.sebastiancheikh.zyonpay.menu;

import org.bukkit.Material;

import java.util.List;

/**
 * A menu item.
 *
 * @param dynamic    true: repeats once per row of the {@code data_source}
 * @param permission permissions the player must have / must not have to see the item
 */
public record MenuItem(
        String key,
        Material material,
        int[] slots,
        boolean dynamic,
        String displayName,
        List<String> lore,
        int amount,
        boolean glow,
        List<String> leftCommands,
        List<String> rightCommands,
        List<String> anyCommands,
        List<String> requiredPermissions,
        List<String> deniedPermissions) {
}
