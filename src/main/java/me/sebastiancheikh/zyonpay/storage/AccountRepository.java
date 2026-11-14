package me.sebastiancheikh.zyonpay.storage;

import me.sebastiancheikh.zyonpay.model.AccountBalance;
import me.sebastiancheikh.zyonpay.model.AccountRow;
import me.sebastiancheikh.zyonpay.model.LoadedAccount;
import me.sebastiancheikh.zyonpay.model.WalletRow;
import me.sebastiancheikh.zyonpay.money.Currency;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * SQL access to {@code accounts} (identity), {@code balances} (one per currency) and {@code pay_blocks}.
 * Every method blocks: call off the main thread.
 * <p>{@code balances} rows are created on the fly with the starting balance, so a currency added after an
 * account existed also works for players who have not joined in a while.
 */
public final class AccountRepository {

    private static final String IDENTITY = "SELECT uuid, name, accepts_payments FROM accounts ";

    private final Database database;

    public AccountRepository(Database database) {
        this.database = database;
    }

    /**
     * Creates the account and a starting balance per currency if missing, updates the name and returns its state.
     */
    public LoadedAccount loadOrCreate(UUID uuid, String name, Collection<Currency> currencies) {
        byte[] id = Uuids.toBytes(uuid);
        try (Connection c = database.connection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO accounts (uuid, name) VALUES (?, ?) ON DUPLICATE KEY UPDATE name = VALUES(name)")) {
                ps.setBytes(1, id);
                ps.setString(2, name);
                ps.executeUpdate();
            }
            for (Currency currency : currencies) {
                ensureWallet(c, uuid, currency);
            }
            AccountRow row;
            try (PreparedStatement ps = c.prepareStatement(IDENTITY + "WHERE uuid = ?")) {
                ps.setBytes(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new StorageException("Account not found right after creating it: " + uuid, null);
                    }
                    row = row(rs);
                }
            }
            Map<String, WalletRow> wallets = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT currency, balance, max_balance FROM balances WHERE uuid = ?")) {
                ps.setBytes(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        wallets.put(rs.getString("currency"), wallet(rs));
                    }
                }
            }
            Set<UUID> blocked = new HashSet<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT blocked FROM pay_blocks WHERE owner = ?")) {
                ps.setBytes(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        blocked.add(Uuids.fromBytes(rs.getBytes(1)));
                    }
                }
            }
            return new LoadedAccount(row.uuid(), row.name(), row.acceptsPayments(), wallets, blocked);
        } catch (SQLException e) {
            throw new StorageException("loadOrCreate failed for " + uuid, e);
        }
    }

    public Optional<AccountRow> findByUuid(UUID uuid) {
        try (Connection c = database.connection(); PreparedStatement ps = c.prepareStatement(IDENTITY + "WHERE uuid = ?")) {
            ps.setBytes(1, Uuids.toBytes(uuid));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(row(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new StorageException("findByUuid failed for " + uuid, e);
        }
    }

    /** If several accounts share a name (name change), the most recently used wins. */
    public Optional<AccountRow> findByName(String name) {
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement(IDENTITY + "WHERE name = ? ORDER BY updated_at DESC LIMIT 1")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(row(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new StorageException("findByName failed for " + name, e);
        }
    }

    /** The account's balance in a currency (created with the starting balance if missing). Empty if the account does not exist. */
    public Optional<WalletRow> wallet(UUID uuid, Currency currency) {
        try (Connection c = database.connection()) {
            ensureWallet(c, uuid, currency);
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT balance, max_balance FROM balances WHERE uuid = ? AND currency = ?")) {
                ps.setBytes(1, Uuids.toBytes(uuid));
                ps.setString(2, currency.id());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(wallet(rs)) : Optional.empty();
                }
            }
        } catch (SQLException e) {
            throw new StorageException("wallet failed for " + uuid, e);
        }
    }

    /**
     * Adds or subtracts atomically. Returns false if it could not: the account does not exist, the balance is
     * too low (withdrawal) or the cap would be exceeded (deposit).
     */
    public boolean adjust(UUID uuid, Currency currency, long delta) {
        String sql = delta >= 0
                ? "UPDATE balances SET balance = balance + ? WHERE uuid = ? AND currency = ? "
                + "AND balance <= COALESCE(max_balance, ?) - ?"
                : "UPDATE balances SET balance = balance - ? WHERE uuid = ? AND currency = ? AND balance >= ?";
        long amount = Math.abs(delta);
        try (Connection c = database.connection()) {
            ensureWallet(c, uuid, currency);
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, amount);
                ps.setBytes(2, Uuids.toBytes(uuid));
                ps.setString(3, currency.id());
                if (delta >= 0) {
                    ps.setLong(4, currency.maxBalance());
                    ps.setLong(5, amount);
                } else {
                    ps.setLong(4, amount);
                }
                return ps.executeUpdate() == 1;
            }
        } catch (SQLException e) {
            throw new StorageException("adjust failed for " + uuid, e);
        }
    }

    public boolean exists(UUID uuid) {
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement("SELECT 1 FROM accounts WHERE uuid = ?")) {
            ps.setBytes(1, Uuids.toBytes(uuid));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new StorageException("exists failed for " + uuid, e);
        }
    }

    /**
     * Writes accumulated deltas of one currency in a single transaction. Deltas commute, so order does not matter;
     * the balance ends in [0, cap] (see the race window in the spec). The cap never lowers a balance that already
     * exceeded it (for example after lowering {@code setmax}): it only stops it from growing.
     */
    public void applyDeltas(Currency currency, Map<UUID, Long> deltas) {
        if (deltas.isEmpty()) {
            return;
        }
        try (Connection c = database.connection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE balances SET balance = GREATEST(LEAST(balance + ?, GREATEST(COALESCE(max_balance, ?), balance)), 0) "
                            + "WHERE uuid = ? AND currency = ?")) {
                for (Map.Entry<UUID, Long> entry : deltas.entrySet()) {
                    ps.setLong(1, entry.getValue());
                    ps.setLong(2, currency.maxBalance());
                    ps.setBytes(3, Uuids.toBytes(entry.getKey()));
                    ps.setString(4, currency.id());
                    ps.addBatch();
                }
                ps.executeBatch();
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new StorageException("applyDeltas failed (" + deltas.size() + " accounts, " + currency.id() + ")", e);
        }
    }

    public void setAcceptsPayments(UUID uuid, boolean accepts) {
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement("UPDATE accounts SET accepts_payments = ? WHERE uuid = ?")) {
            ps.setBoolean(1, accepts);
            ps.setBytes(2, Uuids.toBytes(uuid));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException("setAcceptsPayments failed for " + uuid, e);
        }
    }

    /** Sets an account's own cap ({@link WalletRow#NO_MAX} removes it). */
    public void setMaxBalance(UUID uuid, Currency currency, long max) {
        try (Connection c = database.connection()) {
            ensureWallet(c, uuid, currency);
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE balances SET max_balance = ? WHERE uuid = ? AND currency = ?")) {
                if (max == WalletRow.NO_MAX) {
                    ps.setNull(1, java.sql.Types.BIGINT);
                } else {
                    ps.setLong(1, max);
                }
                ps.setBytes(2, Uuids.toBytes(uuid));
                ps.setString(3, currency.id());
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new StorageException("setMaxBalance failed for " + uuid, e);
        }
    }

    // ---------------------------------------------------------------- pay blocks

    /** @return true if {@code owner} refuses payments from {@code payer} */
    public boolean isBlocked(UUID owner, UUID payer) {
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement("SELECT 1 FROM pay_blocks WHERE owner = ? AND blocked = ?")) {
            ps.setBytes(1, Uuids.toBytes(owner));
            ps.setBytes(2, Uuids.toBytes(payer));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new StorageException("isBlocked failed for " + owner, e);
        }
    }

    /** Blocks {@code target} if not blocked, unblocks otherwise. Returns whether now blocked. */
    public boolean toggleBlocked(UUID owner, UUID target) {
        try (Connection c = database.connection()) {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM pay_blocks WHERE owner = ? AND blocked = ?")) {
                ps.setBytes(1, Uuids.toBytes(owner));
                ps.setBytes(2, Uuids.toBytes(target));
                if (ps.executeUpdate() == 1) {
                    return false;
                }
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT IGNORE INTO pay_blocks (owner, blocked) VALUES (?, ?)")) {
                ps.setBytes(1, Uuids.toBytes(owner));
                ps.setBytes(2, Uuids.toBytes(target));
                ps.executeUpdate();
            }
            return true;
        } catch (SQLException e) {
            throw new StorageException("toggleBlocked failed for " + owner, e);
        }
    }

    public List<String> blockedNames(UUID owner) {
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT a.name FROM pay_blocks b JOIN accounts a ON a.uuid = b.blocked WHERE b.owner = ? ORDER BY a.name")) {
            ps.setBytes(1, Uuids.toBytes(owner));
            try (ResultSet rs = ps.executeQuery()) {
                List<String> names = new ArrayList<>();
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
                return names;
            }
        } catch (SQLException e) {
            throw new StorageException("blockedNames failed for " + owner, e);
        }
    }

    // ---------------------------------------------------------------- ranking and supply

    public List<AccountBalance> top(String currencyId, int limit) {
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT a.uuid, a.name, b.balance FROM balances b JOIN accounts a ON a.uuid = b.uuid "
                             + "WHERE b.currency = ? ORDER BY b.balance DESC LIMIT ?")) {
            ps.setString(1, currencyId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<AccountBalance> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(new AccountBalance(Uuids.fromBytes(rs.getBytes("uuid")), rs.getString("name"),
                            rs.getLong("balance")));
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new StorageException("top failed", e);
        }
    }

    /** Total money in circulation for a currency. */
    public long supply(String currencyId) {
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement("SELECT COALESCE(SUM(balance), 0) FROM balances WHERE currency = ?")) {
            ps.setString(1, currencyId);
            try (ResultSet rs = ps.executeQuery()) {
                BigDecimal total = rs.next() ? rs.getBigDecimal(1) : BigDecimal.ZERO;
                return total.min(BigDecimal.valueOf(Long.MAX_VALUE)).longValue();
            }
        } catch (SQLException e) {
            throw new StorageException("supply failed", e);
        }
    }

    // ---------------------------------------------------------------- internals

    /** Creates the starting balance if the account exists and has no row in this currency yet. */
    private static void ensureWallet(Connection c, UUID uuid, Currency currency) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT IGNORE INTO balances (uuid, currency, balance) SELECT uuid, ?, ? FROM accounts WHERE uuid = ?")) {
            ps.setString(1, currency.id());
            ps.setLong(2, currency.startingBalance());
            ps.setBytes(3, Uuids.toBytes(uuid));
            ps.executeUpdate();
        }
    }

    private static AccountRow row(ResultSet rs) throws SQLException {
        return new AccountRow(Uuids.fromBytes(rs.getBytes("uuid")), rs.getString("name"), rs.getBoolean("accepts_payments"));
    }

    private static WalletRow wallet(ResultSet rs) throws SQLException {
        long balance = rs.getLong("balance");
        long max = rs.getLong("max_balance");
        // wasNull() refers to the last column read, so it must come right after max_balance.
        return new WalletRow(balance, rs.wasNull() ? WalletRow.NO_MAX : max);
    }
}
