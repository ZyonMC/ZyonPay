package me.sebastiancheikh.zyonpay.menu;

import me.sebastiancheikh.zyonpay.config.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;

/**
 * Menu engine for menus.yml (DeluxeMenus-style syntax). One engine renders every menu;
 * the rows come from {@link MenuDataSource}s.
 */
public final class MenuManager {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final JavaPlugin plugin;
    private final Messages messages;
    private final Executor sync;
    private final BiFunction<Player, String, String> externalPlaceholders;
    private final Map<String, MenuDataSource> sources;
    private final Logger log;
    private final List<String> registeredLabels = new ArrayList<>();
    private volatile Map<String, MenuDefinition> menus = Map.of();

    /**
     * @param externalPlaceholders resolves other plugins' placeholders (PlaceholderAPI); identity if absent
     */
    MenuManager(JavaPlugin plugin, Messages messages, Executor sync,
                BiFunction<Player, String, String> externalPlaceholders, Map<String, MenuDataSource> sources) {
        this.plugin = plugin;
        this.messages = messages;
        this.sync = sync;
        this.externalPlaceholders = externalPlaceholders;
        this.sources = sources;
        this.log = plugin.getSLF4JLogger();
    }

    /** Creates the manager with the standard data sources and loads menus.yml. */
    public static MenuManager create(JavaPlugin plugin, Messages messages, Executor sync,
                                     BiFunction<Player, String, String> externalPlaceholders,
                                     me.sebastiancheikh.zyonpay.top.TopService top,
                                     me.sebastiancheikh.zyonpay.money.CurrencyRegistry currencies,
                                     me.sebastiancheikh.zyonpay.economy.EconomyService economy,
                                     me.sebastiancheikh.zyonpay.storage.TransactionRepository transactions,
                                     me.sebastiancheikh.zyonpay.config.Settings.HistorySettings history,
                                     int baltopSize, Executor storage) {
        Map<String, MenuDataSource> sources = Map.of(
                "baltop", new BaltopSource(top, currencies, baltopSize),
                "history", new HistorySource(economy, transactions, currencies, messages, history, storage));
        MenuManager manager = new MenuManager(plugin, messages, sync, externalPlaceholders, sources);
        manager.reload();
        return manager;
    }

    // ---------------------------------------------------------------- loading

    public void reload() {
        File file = new File(plugin.getDataFolder(), "menus.yml");
        if (!file.exists()) {
            plugin.saveResource("menus.yml", false);
        }
        unregisterCommands();
        this.menus = new MenuLoader(log).load(YamlConfiguration.loadConfiguration(file));
        registerCommands();
        log.info("Menus loaded: {}", String.join(", ", menus.keySet()));
    }

    public void shutdown() {
        unregisterCommands();
        Bukkit.getOnlinePlayers().forEach(player -> {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof MenuHolder) {
                player.closeInventory();
            }
        });
    }

    private void registerCommands() {
        CommandMap map = Bukkit.getCommandMap();
        for (MenuDefinition menu : menus.values()) {
            for (String label : menu.openCommands()) {
                map.register(plugin.getName().toLowerCase(Locale.ROOT), new MenuCommand(label, menu.name()));
                registeredLabels.add(label);
            }
        }
    }

    private void unregisterCommands() {
        Map<String, Command> known = Bukkit.getCommandMap().getKnownCommands();
        String fallback = plugin.getName().toLowerCase(Locale.ROOT);
        for (String label : registeredLabels) {
            if (known.get(label) instanceof MenuCommand) {
                known.remove(label);
            }
            known.remove(fallback + ":" + label);
        }
        registeredLabels.clear();
    }

    // ---------------------------------------------------------------- opening

    /** Opens a menu. {@code argument} is the extra command text (for example a player) or null. */
    public void open(Player player, String menuName, String argument) {
        MenuDefinition definition = menus.get(menuName.toLowerCase(Locale.ROOT));
        if (definition == null) {
            messages.send(player, "menu.unavailable");
            return;
        }
        MenuDataSource source = definition.dataSource() == null ? null : sources.get(definition.dataSource());
        if (source != null && !authorized(player, source, argument)) {
            messages.send(player, "no-permission");
            return;
        }
        if (source == null) {
            show(player, definition, List.of());
            return;
        }
        source.load(player, argument, definition.currency()).whenComplete((rows, error) -> sync.execute(() -> {
            if (!player.isOnline()) {
                return;
            }
            if (error != null) {
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                if (cause instanceof MenuLoadException failure) {
                    messages.send(player, failure.messageKey());
                } else {
                    log.warn("Could not prepare menu '{}'", definition.name(), cause);
                    messages.send(player, "error");
                }
                return;
            }
            show(player, definition, rows);
        }));
    }

    private static boolean authorized(Player player, MenuDataSource source, String argument) {
        if (!player.hasPermission(source.permission())) {
            return false;
        }
        boolean hasArgument = argument != null && !argument.equalsIgnoreCase(player.getName());
        return !hasArgument || player.hasPermission(source.argumentPermission());
    }

    private void show(Player player, MenuDefinition definition, List<Map<String, String>> rows) {
        // The title is fixed on open (page 1); Bukkit cannot change it when turning pages.
        Component title = text(player, definition.title(),
                context(player, 0, MenuHolder.pagesFor(definition, rows.size()), rows.size()));
        MenuHolder holder = new MenuHolder(definition, rows, title);
        render(player, holder);
        player.openInventory(holder.getInventory());
    }

    // ---------------------------------------------------------------- render

    private void render(Player player, MenuHolder holder) {
        Inventory inventory = holder.getInventory();
        inventory.clear();
        Map<String, String> context = context(player, holder);
        for (MenuItem item : holder.definition().items()) {
            if (!visible(player, item)) {
                continue;
            }
            if (item.dynamic()) {
                int start = holder.page() * item.slots().length;
                for (int i = 0; i < item.slots().length && start + i < holder.rows().size(); i++) {
                    Map<String, String> row = holder.rows().get(start + i);
                    inventory.setItem(item.slots()[i], build(player, item, merge(context, row), row.get("uuid")));
                }
            } else {
                ItemStack stack = build(player, item, context, null);
                for (int slot : item.slots()) {
                    inventory.setItem(slot, stack.clone());
                }
            }
        }
    }

    private Map<String, String> context(Player player, MenuHolder holder) {
        return context(player, holder.page(), holder.pages(), holder.rows().size());
    }

    private static Map<String, String> context(Player player, int pageIndex, int pages, int total) {
        return Map.of(
                "viewer", player.getName(),
                "page", String.valueOf(pageIndex + 1),
                "pages", String.valueOf(pages),
                "total", String.valueOf(total));
    }

    private static Map<String, String> merge(Map<String, String> base, Map<String, String> extra) {
        Map<String, String> merged = new HashMap<>(base);
        merged.putAll(extra);
        return merged;
    }

    private static boolean visible(Player player, MenuItem item) {
        return item.requiredPermissions().stream().allMatch(player::hasPermission)
                && item.deniedPermissions().stream().noneMatch(player::hasPermission);
    }

    private ItemStack build(Player player, MenuItem item, Map<String, String> context, String rowUuid) {
        ItemStack stack = new ItemStack(item.material(), item.amount());
        ItemMeta meta = stack.getItemMeta();
        if (item.displayName() != null) {
            meta.displayName(text(player, item.displayName(), context));
        }
        if (!item.lore().isEmpty()) {
            meta.lore(item.lore().stream().map(line -> text(player, line, context)).toList());
        }
        if (item.glow()) {
            meta.setEnchantmentGlintOverride(true);
        }
        if (meta instanceof SkullMeta skull) {
            skull.setOwningPlayer(rowUuid != null ? offlinePlayer(rowUuid, player) : player);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    private static OfflinePlayer offlinePlayer(String uuid, Player fallback) {
        try {
            return Bukkit.getOfflinePlayer(UUID.fromString(uuid));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private Component text(Player player, String template, Map<String, String> context) {
        return MINI.deserialize(substitute(player, template, context)).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    private String substitute(Player player, String template, Map<String, String> context) {
        String result = template;
        for (Map.Entry<String, String> entry : context.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return externalPlaceholders.apply(player, result);
    }

    // ---------------------------------------------------------------- clicks

    void handleClick(Player player, MenuHolder holder, int slot, ClickType click) {
        Map<String, String> context = context(player, holder);
        MenuItem hit = null;
        Map<String, String> hitContext = context;
        for (MenuItem item : holder.definition().items()) {
            int index = indexOf(item.slots(), slot);
            if (index < 0 || !visible(player, item)) {
                continue;
            }
            if (item.dynamic()) {
                int row = holder.page() * item.slots().length + index;
                if (row >= holder.rows().size()) {
                    continue;
                }
                hit = item;
                hitContext = merge(context, holder.rows().get(row));
            } else {
                hit = item;
                hitContext = context;
            }
        }
        if (hit == null) {
            return;
        }
        List<String> commands = new ArrayList<>(hit.anyCommands());
        if (click.isRightClick()) {
            commands.addAll(hit.rightCommands());
        } else if (click.isLeftClick()) {
            commands.addAll(hit.leftCommands());
        }
        for (String command : commands) {
            runAction(player, holder, substitute(player, command, hitContext));
        }
    }

    private static int indexOf(int[] slots, int slot) {
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == slot) {
                return i;
            }
        }
        return -1;
    }

    private void runAction(Player player, MenuHolder holder, String action) {
        String trimmed = action.trim();
        int end = trimmed.indexOf(']');
        if (!trimmed.startsWith("[") || end < 0) {
            log.warn("Invalid menu action (must start with [type]): {}", action);
            return;
        }
        String type = trimmed.substring(1, end).toLowerCase(Locale.ROOT);
        String argument = trimmed.substring(end + 1).trim();
        switch (type) {
            case "close" -> player.closeInventory();
            case "player" -> player.performCommand(argument);
            case "console" -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), argument);
            case "message" -> player.sendMessage(MINI.deserialize(argument));
            case "openmenu" -> open(player, argument, null);
            case "nextpage" -> turnPage(player, holder, 1);
            case "prevpage" -> turnPage(player, holder, -1);
            default -> log.warn("Unknown menu action: [{}]", type);
        }
    }

    private void turnPage(Player player, MenuHolder holder, int delta) {
        if (holder.turn(delta)) {
            render(player, holder);
        }
    }

    // ---------------------------------------------------------------- command that opens a menu

    private final class MenuCommand extends Command {

        private final String menuName;

        MenuCommand(String label, String menuName) {
            super(label);
            this.menuName = menuName;
            setDescription("Opens the menu " + menuName);
        }

        @Override
        public boolean execute(@NotNull CommandSender sender, @NotNull String commandLabel, @NotNull String[] args) {
            if (!(sender instanceof Player player)) {
                messages.send(sender, "player-only");
                return true;
            }
            open(player, menuName, args.length > 0 ? args[0] : null);
            return true;
        }
    }
}
