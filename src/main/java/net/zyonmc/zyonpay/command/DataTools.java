package net.zyonmc.zyonpay.command;

import net.zyonmc.zyonpay.config.Messages;
import net.zyonmc.zyonpay.config.Settings;
import net.zyonmc.zyonpay.economy.EconomyService;
import net.zyonmc.zyonpay.model.AccountRow;
import net.zyonmc.zyonpay.money.Currency;
import net.zyonmc.zyonpay.money.CurrencyRegistry;
import net.zyonmc.zyonpay.money.Money;
import net.zyonmc.zyonpay.storage.Backup;
import net.zyonmc.zyonpay.storage.MaintenanceRepository;
import net.zyonmc.zyonpay.sync.RedisBus;
import net.milkbowl.vault.economy.Economy;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * Destructive or bulk admin operations: backup, restore, purge, migrate and switch-currency.
 * Anything that deletes or overwrites data needs a trailing {@code confirm} argument.
 */
final class DataTools {

    private static final Pattern FILE_NAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final String EXTENSION = ".json.gz";

    private final JavaPlugin plugin;
    private final EconomyService economy;
    private final CurrencyRegistry currencies;
    private final Messages messages;
    private final Settings settings;
    private final Backup backup;
    private final MaintenanceRepository maintenance;
    private final RedisBus bus;
    private final Executor storage;
    private final Executor sync;

    DataTools(JavaPlugin plugin, EconomyService economy, CurrencyRegistry currencies, Messages messages,
              Settings settings, Backup backup, MaintenanceRepository maintenance, RedisBus bus, Executor storage,
              Executor sync) {
        this.plugin = plugin;
        this.economy = economy;
        this.currencies = currencies;
        this.messages = messages;
        this.settings = settings;
        this.backup = backup;
        this.maintenance = maintenance;
        this.bus = bus;
        this.storage = storage;
        this.sync = sync;
    }

    /** Backup file names for tab completion. */
    List<String> backupFiles() {
        try (Stream<Path> files = Files.list(dir())) {
            return files.map(path -> path.getFileName().toString()).filter(name -> name.endsWith(EXTENSION)).toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    // ---------------------------------------------------------------- backup / restore

    void backup(CommandSender sender, String[] args) {
        Path file = resolve(sender, args);
        if (file == null) {
            return;
        }
        run(sender, () -> {
            Files.createDirectories(dir());
            economy.flushAll();
            backup.write(file);
            return "data.backup-done";
        }, Messages.tag("file", file.getFileName().toString()));
    }

    void restore(CommandSender sender, String[] args) {
        Path file = resolve(sender, args);
        if (file == null) {
            return;
        }
        if (!Files.isRegularFile(file)) {
            messages.send(sender, "data.file-not-found", Messages.tag("file", file.getFileName().toString()));
            return;
        }
        if (!confirmed(sender, args, 3, "data.restore-confirm", Messages.tag("file", file.getFileName().toString()))) {
            return;
        }
        run(sender, () -> {
            economy.flushAll();
            backup.restore(file);
            economy.reloadAll();
            bus.publishResync();
            return "data.restore-done";
        }, Messages.tag("file", file.getFileName().toString()));
    }

    // ---------------------------------------------------------------- purge

    void purge(CommandSender sender, String[] args) {
        if (args.length < 2) {
            messages.send(sender, "data.purge-usage");
            return;
        }
        Pattern pattern;
        try {
            pattern = Pattern.compile(args[1]);
        } catch (PatternSyntaxException e) {
            messages.send(sender, "data.invalid-regex", Messages.tag("regex", args[1]));
            return;
        }
        boolean confirm = args.length == 3 && args[2].equalsIgnoreCase("confirm");
        CompletableFuture.supplyAsync(() -> {
            // Players with a cached account are online here; deleting them would desync the cache.
            List<UUID> matches = maintenance.accountsMatching(pattern).stream()
                    .map(AccountRow::uuid).filter(uuid -> !economy.isCached(uuid)).toList();
            return confirm ? maintenance.deleteAccounts(matches) : matches.size();
        }, storage).whenCompleteAsync((count, error) -> {
            if (error != null) {
                messages.send(sender, "error");
            } else if (confirm) {
                messages.send(sender, "data.purge-done", Messages.tag("count", String.valueOf(count)));
            } else {
                messages.send(sender, "data.purge-confirm", Messages.tag("count", String.valueOf(count)),
                        Messages.tag("regex", args[1]));
            }
        }, sync);
    }

    // ---------------------------------------------------------------- migrate

    /** Imports balances of players unknown to ZyonPay from another Vault economy into the default currency. */
    void migrate(CommandSender sender) {
        if (!settings.migrationEnabled()) {
            messages.send(sender, "data.migrate-disabled");
            return;
        }
        Optional<Economy> source = plugin.getServer().getServicesManager().getRegistrations(Economy.class).stream()
                .filter(registration -> registration.getPlugin() != plugin)
                .map(RegisteredServiceProvider::getProvider).findFirst();
        if (source.isEmpty()) {
            messages.send(sender, "data.migrate-none");
            return;
        }
        // Read on the main thread: Vault providers are not required to be thread-safe.
        record Entry(UUID uuid, String name, long cents) {
        }
        List<Entry> entries = new ArrayList<>();
        for (OfflinePlayer player : plugin.getServer().getOfflinePlayers()) {
            OptionalLong cents = Money.fromDouble(source.get().getBalance(player));
            if (player.getName() != null && cents.isPresent()) {
                entries.add(new Entry(player.getUniqueId(), player.getName(), cents.getAsLong()));
            }
        }
        Currency target = currencies.defaultCurrency();
        CompletableFuture.supplyAsync(() -> {
            int imported = 0;
            for (Entry entry : entries) {
                if (maintenance.importAccount(entry.uuid(), entry.name(), target, entry.cents())) {
                    imported++;
                }
            }
            return imported;
        }, storage).whenCompleteAsync((imported, error) -> {
            if (error != null) {
                messages.send(sender, "error");
            } else {
                messages.send(sender, "data.migrate-done", Messages.tag("imported", String.valueOf(imported)),
                        Messages.tag("skipped", String.valueOf(entries.size() - imported)));
            }
        }, sync);
    }

    // ---------------------------------------------------------------- switch-currency

    void switchCurrency(CommandSender sender, String[] args) {
        if (args.length < 3) {
            messages.send(sender, "data.switch-usage");
            return;
        }
        Optional<Currency> a = currencies.find(args[1]);
        Optional<Currency> b = currencies.find(args[2]);
        if (a.isEmpty() || b.isEmpty() || a.get().equals(b.get())) {
            messages.send(sender, "data.switch-invalid", Messages.tag("currencies", String.join(", ", currencies.ids())));
            return;
        }
        if (!confirmed(sender, args, 4, "data.switch-confirm", Messages.tag("a", a.get().id()),
                Messages.tag("b", b.get().id()))) {
            return;
        }
        run(sender, () -> {
            economy.flushAll();
            maintenance.swapCurrencies(a.get().id(), b.get().id());
            return "data.switch-done";
        });
    }

    // ---------------------------------------------------------------- helpers

    private Path dir() {
        return plugin.getDataFolder().toPath().resolve("backups");
    }

    /** Validates {@code args[1]} as a plain file name (no path separators) and resolves it inside backups/. */
    private Path resolve(CommandSender sender, String[] args) {
        if (args.length < 2) {
            messages.send(sender, "data.file-usage");
            return null;
        }
        String name = args[1].endsWith(EXTENSION) ? args[1] : args[1] + EXTENSION;
        if (!FILE_NAME.matcher(name).matches() || name.startsWith(".")) {
            messages.send(sender, "data.file-invalid");
            return null;
        }
        return dir().resolve(name);
    }

    /** True when the last argument (at index {@code confirmIndex - 1}) is {@code confirm}; otherwise asks for it. */
    private boolean confirmed(CommandSender sender, String[] args, int confirmIndex,
                              String promptKey, TagResolver... tags) {
        if (args.length >= confirmIndex && args[confirmIndex - 1].equalsIgnoreCase("confirm")) {
            return true;
        }
        messages.send(sender, promptKey, tags);
        return false;
    }

    private interface Job {
        String run() throws Exception;
    }

    /** Runs a job on the storage thread and reports the message key it returns, or an error. */
    private void run(CommandSender sender, Job job, TagResolver... tags) {
        CompletableFuture.supplyAsync(() -> {
            try {
                return job.run();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, storage).whenCompleteAsync((key, error) -> {
            if (error != null) {
                plugin.getSLF4JLogger().error("Admin operation failed", error);
                messages.send(sender, "error");
            } else {
                messages.send(sender, key, tags);
            }
        }, sync);
    }
}
