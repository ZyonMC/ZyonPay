package me.sebastiancheikh.zyonpay.economy;

import me.sebastiancheikh.zyonpay.model.LoadedAccount;
import me.sebastiancheikh.zyonpay.model.WalletRow;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** A player's account as cached by the server where they are online (their "owner"). */
public final class Account {

    private final UUID uuid;
    private final long loadedAt = System.currentTimeMillis();
    private final Map<String, Wallet> wallets = new ConcurrentHashMap<>();
    private final Set<UUID> blocked = ConcurrentHashMap.newKeySet();
    private volatile String name;
    private volatile boolean online = true;
    private volatile boolean acceptsPayments = true;

    public Account(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    public UUID uuid() {
        return uuid;
    }

    public String name() {
        return name;
    }

    public long loadedAt() {
        return loadedAt;
    }

    public boolean online() {
        return online;
    }

    public void markOffline() {
        this.online = false;
    }

    public void markOnline(String currentName) {
        this.online = true;
        this.name = currentName;
    }

    /** Null if this currency has not been loaded for the account yet. */
    public Wallet wallet(String currency) {
        return wallets.get(currency);
    }

    public Map<String, Wallet> wallets() {
        return wallets;
    }

    public boolean acceptsPayments() {
        return acceptsPayments;
    }

    public void setAcceptsPayments(boolean accepts) {
        this.acceptsPayments = accepts;
    }

    /** Flips the preference and returns the new value. */
    public synchronized boolean toggleAcceptsPayments() {
        acceptsPayments = !acceptsPayments;
        return acceptsPayments;
    }

    /** True if this player refuses payments from {@code payer}. */
    public boolean isBlocked(UUID payer) {
        return blocked.contains(payer);
    }

    public void setBlocked(UUID payer, boolean isBlocked) {
        if (isBlocked) {
            blocked.add(payer);
        } else {
            blocked.remove(payer);
        }
    }

    /** Applies freshly loaded database state, keeping any unflushed local changes. */
    public void apply(LoadedAccount data) {
        acceptsPayments = data.acceptsPayments();
        blocked.clear();
        blocked.addAll(data.blocked());
        data.wallets().forEach(this::reloadWallet);
    }

    /** Creates the wallet or refreshes it from the database, keeping unflushed changes. */
    public void reloadWallet(String currency, WalletRow row) {
        Wallet wallet = wallets.get(currency);
        if (wallet == null) {
            wallets.put(currency, new Wallet(row.balance(), row.maxBalance()));
        } else {
            wallet.reload(row.balance(), row.maxBalance());
        }
    }
}
