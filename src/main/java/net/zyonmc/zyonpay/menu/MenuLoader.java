package net.zyonmc.zyonpay.menu;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Turns menus.yml into {@link MenuDefinition}s. An invalid menu is logged and skipped. */
final class MenuLoader {

    private static final Set<String> DATA_SOURCES = Set.of("baltop", "history");

    private final Logger log;

    MenuLoader(Logger log) {
        this.log = log;
    }

    Map<String, MenuDefinition> load(YamlConfiguration yaml) {
        Map<String, MenuDefinition> menus = new LinkedHashMap<>();
        ConfigurationSection root = yaml.getConfigurationSection("menus");
        if (root == null) {
            log.warn("menus.yml has no 'menus' section");
            return menus;
        }
        for (String name : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(name);
            if (section == null) {
                continue;
            }
            try {
                menus.put(name.toLowerCase(Locale.ROOT), parseMenu(name, section));
            } catch (IllegalArgumentException e) {
                log.error("menus.yml: menu '{}' skipped: {}", name, e.getMessage());
            }
        }
        return menus;
    }

    private MenuDefinition parseMenu(String name, ConfigurationSection s) {
        int size = s.getInt("size", 54);
        if (size < 9 || size > 54 || size % 9 != 0) {
            throw new IllegalArgumentException("size must be 9, 18, 27, 36, 45 or 54");
        }
        String dataSource = s.getString("data_source");
        if (dataSource != null && !DATA_SOURCES.contains(dataSource.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("unknown data_source: " + dataSource);
        }
        ConfigurationSection itemsSection = s.getConfigurationSection("items");
        if (itemsSection == null) {
            throw new IllegalArgumentException("missing 'items' section");
        }
        List<MenuItem> items = new ArrayList<>();
        int dynamicItems = 0;
        for (String key : itemsSection.getKeys(false)) {
            ConfigurationSection itemSection = itemsSection.getConfigurationSection(key);
            if (itemSection == null) {
                continue;
            }
            MenuItem item = parseItem(key, itemSection, size);
            if (item.dynamic()) {
                dynamicItems++;
            }
            items.add(item);
        }
        if (dynamicItems > 1) {
            throw new IllegalArgumentException("only one item with dynamic: true is allowed");
        }
        if (dynamicItems == 1 && dataSource == null) {
            throw new IllegalArgumentException("a dynamic item needs data_source");
        }
        return new MenuDefinition(
                name.toLowerCase(Locale.ROOT),
                s.getString("menu_title", name),
                size,
                openCommands(s),
                dataSource == null ? null : dataSource.toLowerCase(Locale.ROOT),
                s.getString("currency") == null ? null : s.getString("currency").toLowerCase(Locale.ROOT),
                List.copyOf(items));
    }

    private MenuItem parseItem(String key, ConfigurationSection s, int size) {
        try {
            Material material = Material.matchMaterial(s.getString("material", ""));
            if (material == null || !material.isItem()) {
                throw new IllegalArgumentException("invalid material: " + s.getString("material"));
            }
            Object rawSlots = s.contains("slots") ? s.get("slots") : s.get("slot");
            if (rawSlots == null) {
                throw new IllegalArgumentException("missing slot or slots");
            }
            int amount = Math.max(1, Math.min(64, s.getInt("amount", 1)));
            List<String> required = new ArrayList<>();
            List<String> denied = new ArrayList<>();
            readRequirements(s.getConfigurationSection("view_requirement.requirements"), required, denied);
            return new MenuItem(
                    key,
                    material,
                    SlotParser.parse(rawSlots, size),
                    s.getBoolean("dynamic", false),
                    s.getString("display_name"),
                    s.getStringList("lore"),
                    amount,
                    s.getBoolean("glow", false),
                    s.getStringList("left_click_commands"),
                    s.getStringList("right_click_commands"),
                    s.getStringList("click_commands"),
                    List.copyOf(required),
                    List.copyOf(denied));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("item '" + key + "': " + e.getMessage());
        }
    }

    private void readRequirements(ConfigurationSection requirements, List<String> required, List<String> denied) {
        if (requirements == null) {
            return;
        }
        for (String id : requirements.getKeys(false)) {
            ConfigurationSection r = requirements.getConfigurationSection(id);
            if (r == null) {
                continue;
            }
            String type = r.getString("type", "");
            String permission = r.getString("permission");
            if (permission == null) {
                throw new IllegalArgumentException("requirement '" + id + "' has no permission");
            }
            switch (type.toLowerCase(Locale.ROOT)) {
                case "has permission" -> required.add(permission);
                case "!has permission" -> denied.add(permission);
                default -> throw new IllegalArgumentException("requirement '" + id + "': unsupported type '" + type + "'");
            }
        }
    }

    private List<String> openCommands(ConfigurationSection s) {
        List<String> commands = s.isList("open_command") ? s.getStringList("open_command")
                : s.getString("open_command") == null ? List.of() : List.of(s.getString("open_command"));
        return commands.stream().map(c -> c.trim().toLowerCase(Locale.ROOT)).filter(c -> !c.isEmpty()).toList();
    }
}
