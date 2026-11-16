package net.zyonmc.zyonpay.economy;

import net.zyonmc.zyonpay.model.WalletRow;

/**
 * One player's balance in one currency, held in memory by the server the player is on.
 * Changes apply instantly and accumulate in {@code pending} until the flusher writes them to MariaDB.
 * State is guarded by the monitor: one wallet, one player, so there is no real contention.
 */
public final class Wallet {

    private long balance;
    private long pending;
    private long maxOverride;

    public Wallet(long balance, long maxOverride) {
        this.balance = balance;
        this.maxOverride = maxOverride;
    }

    public synchronized long balance() {
        return balance;
    }

    /** The player's own cap if one is set, otherwise the currency's. */
    public synchronized long max(long currencyMax) {
        return maxOverride == WalletRow.NO_MAX ? currencyMax : maxOverride;
    }

    public synchronized void setMaxOverride(long maxOverride) {
        this.maxOverride = maxOverride;
    }

    /** @return false if {@code amount} is not positive or the balance would exceed {@code max} */
    public synchronized boolean tryDeposit(long amount, long max) {
        if (amount <= 0 || amount > max - balance) {
            return false;
        }
        balance += amount;
        pending += amount;
        return true;
    }

    /** @return false if {@code amount} is not positive or exceeds the balance */
    public synchronized boolean tryWithdraw(long amount) {
        if (amount <= 0 || amount > balance) {
            return false;
        }
        balance -= amount;
        pending -= amount;
        return true;
    }

    /** Returns the unflushed delta and resets it. */
    public synchronized long drainPending() {
        long drained = pending;
        pending = 0;
        return drained;
    }

    /** Puts back a delta that could not be flushed, to retry next cycle. */
    public synchronized void restorePending(long delta) {
        pending += delta;
    }

    /** Takes the database state but keeps local changes that were not flushed yet. */
    public synchronized void reload(long databaseBalance, long maxOverride) {
        this.balance = Math.max(0, databaseBalance + pending);
        this.maxOverride = maxOverride;
    }
}
