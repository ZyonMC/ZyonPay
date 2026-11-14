package me.sebastiancheikh.zyonpay.economy;

import me.sebastiancheikh.zyonpay.config.Settings;
import me.sebastiancheikh.zyonpay.model.AccountRow;
import me.sebastiancheikh.zyonpay.model.LoadedAccount;
import me.sebastiancheikh.zyonpay.model.Transaction;
import me.sebastiancheikh.zyonpay.model.TxDetail;
import me.sebastiancheikh.zyonpay.model.TxType;
import me.sebastiancheikh.zyonpay.model.WalletRow;
import me.sebastiancheikh.zyonpay.money.Currency;
import me.sebastiancheikh.zyonpay.money.CurrencyRegistry;
import me.sebastiancheikh.zyonpay.storage.AccountRepository;
import me.sebastiancheikh.zyonpay.storage.StorageException;
import me.sebastiancheikh.zyonpay.storage.TransactionRepository;
import me.sebastiancheikh.zyonpay.sync.RedisBus;
import org.slf4j.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

/**
 * Money logic. Players connected here only touch the cache; accounts that are not cached (offline, or online on
 * another server) get an atomic UPDATE in MariaDB plus a Redis notice. Methods that hit the database block.
 * <p>{@code storage} is the only thread that writes to MariaDB from the cache (flush, refresh, load, unload).
 */
public final class EconomyService {

    private static final int LOAD_TIMEOUT_SECONDS = 10;
    private static final long ORPHAN_GRACE_MS = 30_000;

    private final Settings settings;
    private final CurrencyRegistry currencies;
    private final AccountCache cache;
    private final AccountRepository accounts;
    private final TransactionRepository transactions;
    private final Flusher flusher;
    private final RedisBus bus;
    private final ScheduledExecutorService storage;
    private final Logger log;

    public EconomyService(Settings settings, CurrencyRegistry currencies, AccountCache cache, AccountRepository accounts,
                          TransactionRepository transactions, Flusher flusher, RedisBus bus,
                          ScheduledExecutorService storage, Logger log) {
        this.settings = settings;
        this.currencies = currencies;
        this.cache = cache;
        this.accounts = accounts;
        this.transactions = transactions;
        this.flusher = flusher;
        this.bus = bus;
        this.storage = storage;
        this.log = log;
    }

    // ---------------------------------------------------------------- account lifecycle

    /** Loads (or creates) the account into the cache. Blocks; call off the main thread. */
    public void load(UUID uuid, String name) {
        try {
            storage.submit(() -> doLoad(uuid, name)).get(LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StorageException("Load interrupted for " + uuid, e);
        } catch (ExecutionException | TimeoutException e) {
            throw new StorageException("Could not load account " + uuid, e);
        }
    }

    private void doLoad(UUID uuid, String name) {
        Account existing = cache.get(uuid);
        if (existing != null) {
            existing.markOnline(name);
            sync(existing);
            return;
        }
        LoadedAccount data = accounts.loadOrCreate(uuid, name, currencies.all());
        Account account = new Account(uuid, name);
        account.apply(data);
        cache.put(account);
    }

    /** Flushes local changes, then re-reads the whole account from the database. */
    private void sync(Account account) {
        flusher.flush(List.of(account));
        account.apply(accounts.loadOrCreate(account.uuid(), account.name(), currencies.all()));
    }

    /** Flushes the account and drops it from the cache. Runs on the storage thread. */
    public void unload(UUID uuid) {
        Account account = cache.get(uuid);
        if (account == null) {
            return;
        }
        account.markOffline();
        storage.execute(() -> {
            try {
                flusher.flush(List.of(account));
                if (!account.online()) {
                    cache.remove(uuid);
                }
                bus.publishRefresh(uuid);
            } catch (RuntimeException e) {
                log.error("Failed to unload account {}", uuid, e);
            }
        });
    }

    /** Unloads accounts loaded at login whose player never joined. Call from the main thread. */
    public void sweepOrphans(Predicate<UUID> isConnected) {
        long now = System.currentTimeMillis();
        for (Account account : cache.all()) {
            if (now - account.loadedAt() > ORPHAN_GRACE_MS && !isConnected.test(account.uuid())) {
                unload(account.uuid());
            }
        }
    }

    /** Another server changed this balance: flush local changes and re-read. */
    public void onRemoteRefresh(UUID uuid, String currencyId) {
        Account account = cache.get(uuid);
        if (account == null) {
            return;
        }
        storage.execute(() -> {
            try {
                flusher.flush(List.of(account));
                accounts.findByUuid(uuid).ifPresent(row -> account.setAcceptsPayments(row.acceptsPayments()));
                for (Currency currency : currencies.all()) {
                    if (currencyId.equals(RedisBus.ALL) || currencyId.equals(currency.id())) {
                        accounts.wallet(uuid, currency).ifPresent(row -> account.reloadWallet(currency.id(), row));
                    }
                }
            } catch (RuntimeException e) {
                log.warn("Could not refresh account {}", uuid, e);
            }
        });
    }

    /** Re-reads every cached account (after a restore or when currencies were added). Storage thread. */
    public void reloadAll() {
        storage.execute(() -> cache.all().forEach(account -> {
            try {
                sync(account);
            } catch (RuntimeException e) {
                log.warn("Could not reload account {}", account.uuid(), e);
            }
        }));
    }

    public void flushAll() {
        flusher.flush(cache.all());
    }

    // ---------------------------------------------------------------- queries

    /** Balance from the cache only (no I/O). Empty if the player is not connected here. */
    public OptionalLong cachedBalance(UUID uuid, Currency currency) {
        Wallet wallet = cachedWallet(uuid, currency);
        return wallet == null ? OptionalLong.empty() : OptionalLong.of(wallet.balance());
    }

    /** The player's balance cap (own cap if set, else the currency's), from the cache when possible. */
    public long maxBalance(UUID uuid, Currency currency) {
        Wallet wallet = cachedWallet(uuid, currency);
        return wallet == null ? currency.maxBalance() : wallet.max(currency.maxBalance());
    }

    public boolean isCached(UUID uuid) {
        return cache.get(uuid) != null;
    }

    /** Payment preference from the cache; true (the default) if the player is not connected here. */
    public boolean acceptsPaymentsCached(UUID uuid) {
        Account account = cache.get(uuid);
        return account == null || account.acceptsPayments();
    }

    /** Balance from the cache or, if the player is not here, from MariaDB (blocks). */
    public OptionalLong balance(UUID uuid, Currency currency) {
        OptionalLong cached = cachedBalance(uuid, currency);
        if (cached.isPresent()) {
            return cached;
        }
        try {
            return accounts.wallet(uuid, currency).map(row -> OptionalLong.of(row.balance())).orElse(OptionalLong.empty());
        } catch (StorageException e) {
            log.warn("Could not read the {} balance of {}", currency.id(), uuid, e);
            return OptionalLong.empty();
        }
    }

    public boolean exists(UUID uuid) {
        if (isCached(uuid)) {
            return true;
        }
        try {
            return accounts.exists(uuid);
        } catch (StorageException e) {
            log.warn("Could not check account {}", uuid, e);
            return false;
        }
    }

    /** Looks up by name in the cache and then in MariaDB (blocks). */
    public Optional<AccountRow> findByName(String name) {
        Optional<Account> cached = cache.findByName(name);
        if (cached.isPresent()) {
            Account account = cached.get();
            return Optional.of(new AccountRow(account.uuid(), account.name(), account.acceptsPayments()));
        }
        try {
            return accounts.findByName(name);
        } catch (StorageException e) {
            log.warn("Could not look up account '{}'", name, e);
            return Optional.empty();
        }
    }

    /** Creates the account if missing, without caching it. */
    public boolean ensureAccount(UUID uuid, String name) {
        try {
            accounts.loadOrCreate(uuid, name, currencies.all());
            return true;
        } catch (StorageException e) {
            log.warn("Could not create account {}", uuid, e);
            return false;
        }
    }

    // ---------------------------------------------------------------- operations

    /**
     * Deposit. {@code counterpart} is who originates it (null = system or plugin) and only feeds the history.
     * Amount 0 is a successful no-op; rejecting negatives is the caller's job.
     */
    public Result deposit(Currency currency, UUID uuid, long amount, TxType type, UUID counterpart, String reason) {
        if (amount == 0) {
            return Result.SUCCESS;
        }
        Result result = applyDeposit(currency, uuid, amount);
        if (result == Result.SUCCESS) {
            record(currency, counterpart, uuid, amount, type, reason);
        }
        return result;
    }

    public Result withdraw(Currency currency, UUID uuid, long amount, TxType type, UUID counterpart, String reason) {
        if (amount == 0) {
            return Result.SUCCESS;
        }
        Result result = applyWithdraw(currency, uuid, amount);
        if (result == Result.SUCCESS) {
            record(currency, uuid, counterpart, amount, type, reason);
        }
        return result;
    }

    /** Sets the balance as the difference from the current one (recorded as ADMIN). */
    public Result setBalance(Currency currency, UUID uuid, long target, String reason) {
        OptionalLong current = balance(uuid, currency);
        if (current.isEmpty()) {
            return Result.NOT_FOUND;
        }
        long delta = target - current.getAsLong();
        return delta >= 0
                ? deposit(currency, uuid, delta, TxType.ADMIN, null, reason)
                : withdraw(currency, uuid, -delta, TxType.ADMIN, null, reason);
    }

    /**
     * Player-to-player payment. The payer must be connected here; the recipient can be anywhere and the call
     * blocks if they are not cached. The currency's pay tax is taken from what the recipient receives.
     */
    public Result pay(Currency currency, UUID from, String fromName, UUID to, long amount) {
        if (from.equals(to)) {
            return Result.SELF;
        }
        Account sourceAccount = cache.get(from);
        Wallet source = sourceAccount == null ? null : sourceAccount.wallet(currency.id());
        if (source == null) {
            return Result.ERROR;
        }
        Account target = cache.get(to);
        try {
            Result check = checkRecipient(target, from, to);
            if (check != Result.SUCCESS) {
                return check;
            }
        } catch (StorageException e) {
            log.warn("Could not check recipient {}", to, e);
            return Result.ERROR;
        }

        long tax = currency.tax(amount);
        long received = amount - tax;
        if (received <= 0) {
            return Result.TOO_SMALL;
        }
        if (!source.tryWithdraw(amount)) {
            return Result.INSUFFICIENT_FUNDS;
        }
        Result deposited = applyDeposit(currency, to, received);
        if (deposited != Result.SUCCESS) {
            source.tryDeposit(amount, Long.MAX_VALUE); // refund
            return deposited;
        }
        record(currency, from, to, received, TxType.PAY, null);
        if (tax > 0) {
            record(currency, from, null, tax, TxType.PAY, "tax");
        }
        if (target == null) {
            bus.publishPaid(to, fromName, received, currency.id());
        }
        return Result.SUCCESS;
    }

    private Result checkRecipient(Account target, UUID from, UUID to) {
        if (target != null) {
            if (!target.acceptsPayments()) {
                return Result.PAYMENTS_DISABLED;
            }
            return target.isBlocked(from) ? Result.BLOCKED : Result.SUCCESS;
        }
        if (!settings.pay().allowOffline()) {
            return Result.TARGET_OFFLINE;
        }
        Optional<AccountRow> row = accounts.findByUuid(to);
        if (row.isEmpty()) {
            return Result.NOT_FOUND;
        }
        if (!row.get().acceptsPayments()) {
            return Result.PAYMENTS_DISABLED;
        }
        return accounts.isBlocked(to, from) ? Result.BLOCKED : Result.SUCCESS;
    }

    /** Sets (or clears with {@link WalletRow#NO_MAX}) a player's own balance cap in a currency. */
    public Result setMaxBalance(Currency currency, UUID uuid, long max) {
        try {
            if (!accounts.exists(uuid)) {
                return Result.NOT_FOUND;
            }
            accounts.setMaxBalance(uuid, currency, max);
        } catch (StorageException e) {
            log.warn("Could not set the {} cap of {}", currency.id(), uuid, e);
            return Result.ERROR;
        }
        Wallet wallet = cachedWallet(uuid, currency);
        if (wallet != null) {
            wallet.setMaxOverride(max);
        }
        bus.publishRefresh(uuid, currency.id());
        return Result.SUCCESS;
    }

    /**
     * Reverses a transaction with a new one and marks the original, so it cannot be reverted twice.
     * The payee of the original gives the money back to the payer.
     */
    public Result revert(TxDetail tx) {
        Optional<Currency> found = currencies.find(tx.currency());
        if (found.isEmpty()) {
            return Result.ERROR;
        }
        Currency currency = found.get();
        try {
            if (!transactions.markReverted(tx.id())) {
                return Result.ALREADY_REVERTED;
            }
            Result result = tx.to() == null ? Result.SUCCESS : applyWithdraw(currency, tx.to(), tx.amount());
            if (result == Result.SUCCESS && tx.from() != null) {
                result = applyDeposit(currency, tx.from(), tx.amount());
                if (result != Result.SUCCESS && tx.to() != null) {
                    applyDeposit(currency, tx.to(), tx.amount()); // give the withdrawn money back
                }
            }
            if (result != Result.SUCCESS) {
                transactions.unmarkReverted(tx.id());
                return result;
            }
            record(currency, tx.to(), tx.from(), tx.amount(), TxType.ADMIN, "revert #" + tx.id());
            return Result.SUCCESS;
        } catch (StorageException e) {
            log.warn("Could not revert transaction {}", tx.id(), e);
            return Result.ERROR;
        }
    }

    /** Flips whether this player accepts payments at all. Returns the new value. */
    public boolean toggleAcceptsPayments(UUID uuid) {
        Account account = cache.get(uuid);
        if (account == null) {
            throw new IllegalStateException("Account not loaded: " + uuid);
        }
        boolean accepts = account.toggleAcceptsPayments();
        storage.execute(() -> {
            try {
                accounts.setAcceptsPayments(uuid, accepts);
            } catch (StorageException e) {
                log.warn("Could not save the payment preference of {}", uuid, e);
            }
        });
        return accepts;
    }

    /** Blocks or unblocks {@code target} from paying {@code owner}. Returns true if now blocked. Blocks on I/O. */
    public boolean toggleBlocked(UUID owner, UUID target) {
        boolean blocked = accounts.toggleBlocked(owner, target);
        Account account = cache.get(owner);
        if (account != null) {
            account.setBlocked(target, blocked);
        }
        return blocked;
    }

    public List<String> blockedNames(UUID owner) {
        return accounts.blockedNames(owner);
    }

    // ---------------------------------------------------------------- internals

    private Wallet cachedWallet(UUID uuid, Currency currency) {
        Account account = cache.get(uuid);
        return account == null ? null : account.wallet(currency.id());
    }

    private Result applyDeposit(Currency currency, UUID uuid, long amount) {
        Wallet wallet = cachedWallet(uuid, currency);
        if (wallet != null) {
            return wallet.tryDeposit(amount, wallet.max(currency.maxBalance())) ? Result.SUCCESS : Result.MAX_BALANCE;
        }
        return direct(currency, uuid, amount);
    }

    private Result applyWithdraw(Currency currency, UUID uuid, long amount) {
        Wallet wallet = cachedWallet(uuid, currency);
        if (wallet != null) {
            return wallet.tryWithdraw(amount) ? Result.SUCCESS : Result.INSUFFICIENT_FUNDS;
        }
        return direct(currency, uuid, -amount);
    }

    /** Path for uncached accounts: atomic UPDATE in MariaDB, then tell the owner server. */
    private Result direct(Currency currency, UUID uuid, long delta) {
        try {
            if (accounts.adjust(uuid, currency, delta)) {
                bus.publishRefresh(uuid, currency.id());
                return Result.SUCCESS;
            }
            if (!accounts.exists(uuid)) {
                return Result.NOT_FOUND;
            }
            return delta > 0 ? Result.MAX_BALANCE : Result.INSUFFICIENT_FUNDS;
        } catch (StorageException e) {
            log.warn("Direct operation failed for {}", uuid, e);
            return Result.ERROR;
        }
    }

    private boolean loggedType(TxType type) {
        return switch (type) {
            case VAULT -> settings.economy().logVaultTransactions();
            case PAY -> settings.economy().logPayTransactions();
            case ADMIN -> settings.economy().logAdminTransactions();
        };
    }

    private void record(Currency currency, UUID from, UUID to, long amount, TxType type, String reason) {
        if (!currency.saveTransactions() || !loggedType(type)) {
            return;
        }
        flusher.log(new Transaction(from, to, amount, type, settings.serverId(), Instant.now(), currency.id(), reason));
    }
}
