package me.sebastiancheikh.zyonpay.menu;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

/** State of an open menu: definition, rows and current page. */
final class MenuHolder implements InventoryHolder {

    private final MenuDefinition definition;
    private final List<Map<String, String>> rows;
    private final Inventory inventory;
    private int page;

    MenuHolder(MenuDefinition definition, List<Map<String, String>> rows, Component title) {
        this.definition = definition;
        this.rows = rows;
        this.inventory = Bukkit.createInventory(this, definition.size(), title);
    }

    MenuDefinition definition() {
        return definition;
    }

    List<Map<String, String>> rows() {
        return rows;
    }

    int page() {
        return page;
    }

    int pages() {
        return pagesFor(definition, rows.size());
    }

    static int pagesFor(MenuDefinition definition, int rowCount) {
        return Math.max(1, (rowCount + definition.pageSize() - 1) / definition.pageSize());
    }

    /** Turns the page if it exists. Returns true if it changed. */
    boolean turn(int delta) {
        int target = page + delta;
        if (target < 0 || target >= pages()) {
            return false;
        }
        page = target;
        return true;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
