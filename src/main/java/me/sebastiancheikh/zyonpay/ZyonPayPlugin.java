package me.sebastiancheikh.zyonpay;

import me.clip.placeholderapi.PlaceholderAPI;
import me.sebastiancheikh.zyonpay.command.BalanceCommand;
import me.sebastiancheikh.zyonpay.command.CurrencyPerms;
import me.sebastiancheikh.zyonpay.command.EcoCommand;
import me.sebastiancheikh.zyonpay.command.PayCommand;
import me.sebastiancheikh.zyonpay.command.PayNotifier;
import me.sebastiancheikh.zyonpay.command.PayToggleCommand;
import me.sebastiancheikh.zyonpay.command.Tabs;
import me.sebastiancheikh.zyonpay.command.ZyonPayCommand;
import me.sebastiancheikh.zyonpay.config.Messages;
import me.sebastiancheikh.zyonpay.config.Settings;
import me.sebastiancheikh.zyonpay.economy.AccountCache;
import me.sebastiancheikh.zyonpay.economy.EconomyService;
import me.sebastiancheikh.zyonpay.economy.Flusher;
import me.sebastiancheikh.zyonpay.listener.PlayerConnectionListener;
import me.sebastiancheikh.zyonpay.menu.MenuListener;
import me.sebastiancheikh.zyonpay.menu.MenuManager;
import me.sebastiancheikh.zyonpay.money.Currency;
import me.sebastiancheikh.zyonpay.money.CurrencyRegistry;
import me.sebastiancheikh.zyonpay.placeholder.ZyonPayExpansion;
import me.sebastiancheikh.zyonpay.storage.AccountRepository;
import me.sebastiancheikh.zyonpay.storage.Backup;
import me.sebastiancheikh.zyonpay.storage.Database;
import me.sebastiancheikh.zyonpay.storage.MaintenanceRepository;
import me.sebastiancheikh.zyonpay.storage.StorageException;
import me.sebastiancheikh.zyonpay.storage.TransactionRepository;
import me.sebastiancheikh.zyonpay.sync.RedisBus;
import me.sebastiancheikh.zyonpay.top.TopService;
import me.sebastiancheikh.zyonpay.vault.VaultEconomy;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

public final class ZyonPayPlugin extends JavaPlugin {

    private static final long SWEEP_INTERVAL_TICKS = 600L;
    private static final long SHUTDOWN_WAIT_SECONDS = 10L;
    private static final int HISTORY_PURGE_MINUTES = 60;

    private final Map<String, VaultEconomy> vaults = new ConcurrentHashMap<>();
    private Settings settings;
    private Database database;
    private RedisBus bus;
    private ScheduledExecutorService storage;
    private CurrencyRegistry currencies;
    private EconomyService economy;
    private TransactionRepository transactions;
    private MenuManager menus;
    private Messages messages;
    private PayNotifier payNotifier;

    @Override
    public void onEnable() {
        Logger log = getSLF4JLogger();
        saveDefaultConfig();

        try {
            settings = Settings.load(getConfig());
        } catch (IllegalArgumentException e) {
            log.error("Invalid configuration: {}", e.getMessage());
            disable();
            return;
        }
        currencies = new CurrencyRegistry(settings.currencies(), settings.placeholders().shortUnits());

        try {
            database = new Database(settings.database());
            database.migrate(currencies.defaultCurrency().id());
        } catch (RuntimeException e) {
            log.error("Could not connect to MariaDB ({}:{}): {}", settings.database().host(),
                    settings.database().port(), e.getMessage());
            disable();
            return;
        }

        messages = new Messages(this);
        payNotifier = new PayNotifier(messages, settings.pay());
        Executor sync = task -> getServer().getScheduler().runTask(this, task);
        storage = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "zyonpay-storage");
            thread.setDaemon(true);
            return thread;
        });

        AccountRepository accounts = new AccountRepository(database);
        transactions = new TransactionRepository(database);
        Flusher flusher = new Flusher(accounts, transactions, currencies, log);
        bus = new RedisBus(settings.redis(), settings.serverId(), settings.debug(), log);
        economy = new EconomyService(settings, currencies, new AccountCache(), accounts, transactions, flusher, bus,
                storage, log);
        int topSize = Math.max(settings.baltop().size(), settings.placeholders().topAccounts());
        TopService top = new TopService(topSize, settings.baltop().refreshSeconds(), settings.serverId(), accounts,
                bus, currencies, log);

        boolean papi = getServer().getPluginManager().isPluginEnabled("PlaceholderAPI");
        BiFunction<Player, String, String> external = papi ? PlaceholderAPI::setPlaceholders : (player, text) -> text;
        menus = MenuManager.create(this, messages, sync, external, top, currencies, economy, transactions,
                settings.history(), settings.baltop().size(), storage);

        Tabs.configure(settings.tab());
        CurrencyPerms.register(getServer().getPluginManager(), currencies);
        loadOnlinePlayers(log);
        startBus(sync);
        schedule(top);

        registerVault();

        getServer().getPluginManager().registerEvents(new PlayerConnectionListener(economy, messages, log), this);
        getServer().getPluginManager().registerEvents(new MenuListener(menus), this);
        registerCommands(sync);

        if (papi) {
            new ZyonPayExpansion(this, economy, top, currencies, messages, settings.placeholders()).register();
            log.info("PlaceholderAPI expansion registered (%zyonpay_...%)");
        }
        log.info("ZyonPay {} ready (server '{}', Redis {}, currencies: {})", getPluginMeta().getVersion(),
                settings.serverId(), settings.redis().enabled() ? "enabled" : "disabled", String.join(", ", currencies.ids()));
    }

    @Override
    public void onDisable() {
        vaults.values().forEach(vault -> getServer().getServicesManager().unregister(Economy.class, vault));
        if (menus != null) {
            menus.shutdown();
        }
        if (storage != null) {
            storage.shutdown();
            try {
                storage.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // The storage thread is done, so this final flush competes with nobody.
            if (economy != null) {
                try {
                    economy.flushAll();
                } catch (RuntimeException e) {
                    getSLF4JLogger().error("Could not flush balances on shutdown", e);
                }
            }
        }
        if (bus != null) {
            bus.close();
        }
        if (database != null) {
            database.close();
        }
    }

    /** The Vault {@link Economy} of a currency, for plugins that want a non-default one. Null if it does not exist. */
    public Economy vault(String currencyId) {
        return vaults.get(currencyId.toLowerCase(java.util.Locale.ROOT));
    }

    /** Only the default currency is registered as the Vault service; the others are reachable through {@link #vault}. */
    private void registerVault() {
        for (Currency currency : currencies.all()) {
            vaults.computeIfAbsent(currency.id(), id -> new VaultEconomy(economy, currencies, id, settings));
        }
        getServer().getServicesManager().register(Economy.class, vaults.get(currencies.defaultCurrency().id()), this,
                ServicePriority.Highest);
    }

    /** {@code /zyonpay reload}: messages, menus and currencies. Everything else in config.yml needs a restart. */
    private void reloadAll() {
        reloadConfig();
        // Parse first so an invalid file changes nothing.
        Settings.CurrenciesSettings parsed = Settings.loadCurrencies(getConfig());
        List<String> units = Settings.shortUnits(getConfig());
        currencies.reload(parsed, units);
        CurrencyPerms.register(getServer().getPluginManager(), currencies);
        for (Currency currency : currencies.all()) {
            vaults.computeIfAbsent(currency.id(), id -> new VaultEconomy(economy, currencies, id, settings));
        }
        economy.reloadAll(); // gives cached accounts a wallet for any new currency
        messages.reload();
        menus.reload();
    }

    /** Loads the accounts of players already online (plugin hot reload). */
    private void loadOnlinePlayers(Logger log) {
        for (Player player : getServer().getOnlinePlayers()) {
            try {
                economy.load(player.getUniqueId(), player.getName());
            } catch (StorageException e) {
                log.error("Could not load the account of {}", player.getName(), e);
            }
        }
    }

    private void startBus(Executor sync) {
        bus.start(new RedisBus.Listener() {
            @Override
            public void onRefresh(UUID uuid, String currency) {
                economy.onRemoteRefresh(uuid, currency);
            }

            @Override
            public void onPaid(UUID to, String fromName, long cents, String currency) {
                sync.execute(() -> {
                    Player recipient = getServer().getPlayer(to);
                    Optional<Currency> paid = currencies.find(currency);
                    if (recipient != null && paid.isPresent()) {
                        payNotifier.received(recipient, fromName, paid.get(), cents);
                    }
                });
            }

            @Override
            public void onResync() {
                economy.reloadAll();
            }
        });
    }

    private void schedule(TopService top) {
        long flushMs = settings.economy().flushIntervalMs();
        Logger log = getSLF4JLogger();
        storage.scheduleWithFixedDelay(guarded("flush", economy::flushAll, log), flushMs, flushMs, TimeUnit.MILLISECONDS);
        storage.scheduleWithFixedDelay(guarded("baltop", top::refresh, log), 0,
                settings.baltop().refreshSeconds(), TimeUnit.SECONDS);
        storage.scheduleWithFixedDelay(guarded("history-purge", this::purgeHistory, log), 1, HISTORY_PURGE_MINUTES,
                TimeUnit.MINUTES);
        if (bus.enabled()) {
            storage.scheduleWithFixedDelay(guarded("network-players", this::syncNetworkPlayers, log),
                    0, settings.tab().networkSyncSeconds(), TimeUnit.SECONDS);
        }
        getServer().getScheduler().runTaskTimer(this,
                () -> economy.sweepOrphans(uuid -> getServer().getPlayer(uuid) != null),
                SWEEP_INTERVAL_TICKS, SWEEP_INTERVAL_TICKS);
    }

    /** Deletes history older than each currency's {@code history-ttl-days}. */
    private void purgeHistory() {
        for (Currency currency : currencies.all()) {
            int days = currency.settings().historyTtlDays();
            if (days > 0) {
                long deleted = transactions.purgeOlderThan(currency.id(), Instant.now().minus(days, ChronoUnit.DAYS));
                if (deleted > 0) {
                    getSLF4JLogger().info("Purged {} {} transactions older than {} days", deleted, currency.id(), days);
                }
            }
        }
    }

    /** Publishes this server's players (key with TTL) and reads the whole network's for tab completion. */
    private void syncNetworkPlayers() {
        String[] local = getServer().getOnlinePlayers().stream().map(Player::getName).toArray(String[]::new);
        String mine = bus.key("online:" + settings.serverId());
        Set<String> names = bus.execute(redis -> {
            redis.del(mine);
            if (local.length > 0) {
                redis.sadd(mine, local);
                redis.expire(mine, settings.tab().networkSyncSeconds() * 4L);
            }
            List<String> keys = redis.keys(bus.key("online:*"));
            return keys.isEmpty() ? Set.<String>of() : redis.sunion(keys.toArray(String[]::new));
        });
        if (settings.debug() && !new TreeSet<>(names).equals(new TreeSet<>(Tabs.network()))) {
            getSLF4JLogger().info("[debug] players on the network: {}", new TreeSet<>(names));
        }
        Tabs.setNetwork(names);
    }

    /** An exception in a periodic task would cancel all its future runs, so catch and log it. */
    private static Runnable guarded(String name, Runnable task, Logger log) {
        return () -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                log.error("Periodic task '{}' failed", name, e);
            }
        };
    }

    private void registerCommands(Executor sync) {
        register("balance", new BalanceCommand(economy, currencies, messages, storage, sync));
        register("pay", new PayCommand(economy, currencies, messages, settings.pay(), payNotifier, menus, storage, sync));
        register("paytoggle", new PayToggleCommand(economy, messages, storage, sync));
        register("eco", new EcoCommand(economy, currencies, messages, settings, transactions, storage, sync));
        register("zyonpay", ZyonPayCommand.create(this, messages, currencies, this::reloadAll, settings, economy,
                transactions, new Backup(database), new MaintenanceRepository(database), bus, storage, sync));
    }

    private void register(String name, CommandExecutor executor) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            throw new IllegalStateException("Command not declared in plugin.yml: " + name);
        }
        command.setExecutor(executor);
        if (executor instanceof TabCompleter completer) {
            command.setTabCompleter(completer);
        }
    }

    private void disable() {
        if (database != null) {
            database.close();
            database = null;
        }
        getServer().getPluginManager().disablePlugin(this);
    }
}
