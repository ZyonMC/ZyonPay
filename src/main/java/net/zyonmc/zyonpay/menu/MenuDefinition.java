package net.zyonmc.zyonpay.menu;

import java.util.List;

/**
 * A menu read from menus.yml.
 *
 * @param dataSource name of the row source ({@code baltop}, {@code history}) or null
 * @param currency   currency id the menu shows, or null for the default currency
 */
public record MenuDefinition(
        String name,
        String title,
        int size,
        List<String> openCommands,
        String dataSource,
        String currency,
        List<MenuItem> items) {

    /** Slots per page: those of the dynamic item, or 1 if the menu has no rows. */
    public int pageSize() {
        return items.stream().filter(MenuItem::dynamic).mapToInt(item -> item.slots().length).findFirst().orElse(1);
    }
}
